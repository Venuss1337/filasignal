package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.*;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Samples registered loaded endpoints on a bounded round-robin schedule. */
final class PeripheralSampler {
    private static final int VISITS_PER_TICK = 16;
    private static final int MAX_PLAYERS = 128;
    private static final int NOTIFICATION_VISITS_PER_TICK = 96;
    static final java.util.Set<String> CONTROLLER_EVENTS = java.util.Set.of("inputChanged", "signalOn", "signalOff");
    private int notificationTick = -1;
    private int notificationVisits;

    private final Map<UUID, SensorSample> sensors = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> inputs = new HashMap<>();
    private final Map<UUID, EnumSet<Direction>> refreshedOutputs = new HashMap<>();
    private int cursor;

    private record SensorSample(int lastTick, boolean detected) {}

    void forget(UUID id) {
        sensors.remove(id);
        inputs.remove(id);
        refreshedOutputs.remove(id);
    }

    void tick(PeripheralRuntime runtime, MinecraftServer server, Map<UUID, BlockEntity> loaded) {
        var devices = new ArrayList<>(loaded.keySet());
        for (int i = 0; i < Math.min(VISITS_PER_TICK, devices.size()); i++) {
            cursor = Math.floorMod(cursor, devices.size());
            UUID id = devices.get(cursor++);
            Node node = runtime.directory().node(id);
            if (!(runtime.live(id) instanceof PeripheralEntity peripheral)
                    || !(peripheral.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            if (node.kind() == Kind.INTERFACE || node.kind() == Kind.REDSTONE_CONTROLLER) {
                refreshOutputs(level, node, peripheral);
            }
            if (runtime.available(node)) {
                switch (node.kind()) {
                    case SENSOR -> sensor(server, level, node, peripheral);
                    case INTERFACE -> redstone(server, level, node, peripheral);
                    default -> { }
                }
            }
            if (node.kind() == Kind.REDSTONE_CONTROLLER && (runtime.available(node) || local(server, peripheral)
                    || runtime.events().wantsAny(id, CONTROLLER_EVENTS))) {
                controller(runtime, server, level, node, peripheral, runtime.available(node));
            }
        }
    }

    private void sensor(MinecraftServer server, ServerLevel level, Node node, PeripheralEntity entity) {
        if (node.alias() == null) {
            return;
        }
        SensorSample prior = sensors.get(node.id());
        int tick = server.getTickCount();
        if (prior != null && tick - prior.lastTick() < entity.period()) {
            return;
        }
        if (level.players().size() > MAX_PLAYERS) {
            // An incomplete observation cannot safely clear a prior detection.
            return;
        }
        var center = net.minecraft.world.phys.Vec3.atCenterOf(entity.getBlockPos());
        double radiusSquared = entity.range() * entity.range();
        boolean detected = level.players().stream().anyMatch(player -> !player.isSpectator() && player.isAlive()
                && player.distanceToSqr(center) <= radiusSquared);
        sensors.put(node.id(), new SensorSample(tick, detected));
        if (detected != (prior != null && prior.detected())) {
            emit(server, node, detected ? "sensor.detected" : "sensor.cleared", node.alias(), new Bool(detected));
        }
    }

    private void refreshOutputs(ServerLevel level, Node node, PeripheralEntity entity) {
        for (Direction face : outputFaces(node, entity)) {
            var refreshed = refreshedOutputs.computeIfAbsent(node.id(), ignored -> EnumSet.noneOf(Direction.class));
            if (!LoadedChunks.signalArea(level, entity.getBlockPos().relative(face))) {
                refreshed.remove(face);
            } else if (refreshed.add(face)) {
                entity.refreshOutput(level, face);
            }
        }
    }

    private static java.util.Set<Direction> outputFaces(Node node, PeripheralEntity entity) {
        if (node.kind() == Kind.REDSTONE_CONTROLLER) {
            return entity.outputFaces();
        }
        var faces = EnumSet.noneOf(Direction.class);
        node.ports().stream().filter(port -> port.mode() == Mode.OUTPUT).forEach(port -> faces.add(port.face()));
        return faces;
    }

    /** A Network Interface's input ports, keyed by port alias, which is also the event argument. */
    private static Map<String, Direction> inputs(Node node, PeripheralEntity entity) {
        var faces = new java.util.LinkedHashMap<String, Direction>();
        node.ports().stream().filter(port -> port.mode() == Mode.INPUT).forEach(port -> faces.put(port.alias(), port.face()));
        return faces;
    }

    void neighborChanged(PeripheralRuntime runtime, MinecraftServer server, ServerLevel level, Node node, PeripheralEntity entity,
                         boolean routed) {
        int tick = server.getTickCount();
        if (notificationTick != tick) {
            notificationTick = tick;
            notificationVisits = 0;
        }
        if (notificationVisits < NOTIFICATION_VISITS_PER_TICK) {
            notificationVisits++;
            if (node.kind() == Kind.REDSTONE_CONTROLLER) {
                controller(runtime, server, level, node, entity, routed);
            } else {
                redstone(server, level, node, entity);
            }
        }
    }

    /** True while the device runs its own program from a drive. */
    static boolean local(MinecraftServer server, PeripheralEntity entity) {
        return entity.programmable() && ComputerLifecycle.active(server, entity);
    }

    /**
     * Samples a redstone controller's input faces. A named controller on a reachable network raises
     * {@code redstone.*("name:face")} for the computer. A controller running its own program receives
     * {@code redstone.*("face")}. Output faces are never sampled.
     */
    private void controller(PeripheralRuntime runtime, MinecraftServer server, ServerLevel level, Node node, PeripheralEntity entity,
                            boolean routed) {
        boolean local = local(server, entity);
        boolean watched = runtime.events().wantsAny(node.id(), CONTROLLER_EVENTS);
        for (Direction face : Direction.values()) {
            if (entity.outputFaces().contains(face)) {
                continue;
            }
            var neighbor = entity.getBlockPos().relative(face);
            if (!LoadedChunks.signalArea(level, neighbor)) {
                continue;
            }
            int strength = level.getSignal(neighbor, face);
            var previous = inputs.computeIfAbsent(node.id(), ignored -> new HashMap<>());
            int old = previous.getOrDefault(face.getSerializedName(), 0);
            previous.put(face.getSerializedName(), strength);
            if (strength == old) {
                continue;
            }
            if (routed && node.alias() != null) {
                transitions(server, node.network().toString(), node.alias() + ":" + face.getSerializedName(), old, strength);
            }
            if (local) {
                transitions(server, entity.executionId(), face.getSerializedName(), old, strength);
            }
            if (watched) {
                // Native events carry the face as a Direction constant, such as Direction.NORTH.
                Value direction = new EnumMember("Direction", face.getSerializedName().toUpperCase(java.util.Locale.ROOT));
                runtime.events().publish(node.id(), "inputChanged", List.of(direction, new Num(strength), new Num(old)));
                if (old == 0 && strength > 0) {
                    runtime.events().publish(node.id(), "signalOn", List.of(direction, new Num(strength)));
                } else if (old > 0 && strength == 0) {
                    runtime.events().publish(node.id(), "signalOff", List.of(direction));
                }
            }
        }
    }

    private static void transitions(MinecraftServer server, String program, String name, int old, int strength) {
        var argument = List.<Value>of(new Text(name));
        ComputerLifecycle.emit(server, program, "redstone.changed", argument, new Num(strength));
        if (old == 0 && strength > 0) {
            ComputerLifecycle.emit(server, program, "redstone.rising", argument, new Num(strength));
        } else if (old > 0 && strength == 0) {
            ComputerLifecycle.emit(server, program, "redstone.falling", argument, new Num(0));
        }
    }

    private void redstone(MinecraftServer server, ServerLevel level, Node node, PeripheralEntity entity) {
        for (var input : inputs(node, entity).entrySet()) {
            String name = input.getKey();
            Direction face = input.getValue();
            var neighbor = entity.getBlockPos().relative(face);
            if (!LoadedChunks.signalArea(level, neighbor)) {
                continue;
            }
            int strength = level.getSignal(neighbor, face);
            var previous = inputs.computeIfAbsent(node.id(), ignored -> new HashMap<>());
            int old = previous.getOrDefault(name, 0);
            previous.put(name, strength);
            if (strength != old) {
                emit(server, node, "redstone.changed", name, new Num(strength));
            }
            if (old == 0 && strength > 0) {
                emit(server, node, "redstone.rising", name, new Num(strength));
            } else if (old > 0 && strength == 0) {
                emit(server, node, "redstone.falling", name, new Num(0));
            }
        }
    }

    private static void emit(MinecraftServer server, Node node, String name, String alias, Value value) {
        ComputerLifecycle.emit(server, node.network(), name, List.of(new Text(alias)), value);
    }
}
