package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns common-side hooks and the per-server peripheral service. */
public final class PeripheralLifecycle {
    private static final Map<MinecraftServer, PeripheralRuntime> RUNTIMES =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    private PeripheralLifecycle() {}

    /** Registers passive-device events and developer configuration commands. */
    public static void register() {
        InventoryService.register();
        ServerLifecycleEvents.SERVER_STOPPED.register(RUNTIMES::remove);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof PeripheralEntity peripheral) {
                runtime(level.getServer()).enqueue(peripheral);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) ->
                detached(level.getServer(), entity));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PeripheralRuntime runtime = RUNTIMES.get(server);
            if (runtime != null) {
                runtime.tick();
            }
            LinkingTool.expire(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(LinkingTool::clear);
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
            if (!(world instanceof ServerLevel level) || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                    || !(PeripheralBlocks.isPeripheral(state)
                    || state.is(dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks.block()))) {
                return true;
            }
            PeripheralRuntime runtime = runtime(level.getServer());
            var actor = NetworkPermission.Actor.from(serverPlayer);
            if (actor.administrator()) {
                return true;
            }
            try {
                NetworkPermission.require(runtime.directory(), runtime.requireDevice(level, pos), actor, true);
                return true;
            } catch (IllegalArgumentException error) {
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal(error.getMessage()));
                return false;
            }
        });
        // Breaking a Storage Server deletes its data, so warn as soon as a player starts breaking one that holds data.
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world instanceof ServerLevel && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && world.getBlockEntity(pos) instanceof PeripheralEntity entity && entity.storedKeys() > 0) {
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(
                        "filasignal.storage.warning", entity.storedKeys()));
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        PeripheralCommands.register();
        dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.observe(new dev.wiktorfila.filasignal.fabric.computer.ProgramObserver() {
            @Override
            public void attached(MinecraftServer server, dev.wiktorfila.filasignal.fabric.computer.ProgramHost host,
                                 dev.wiktorfila.filasignal.core.runtime.event.Simulation.Controller controller) {
                runtime(server).events().attached(host, controller);
            }

            @Override
            public void detached(MinecraftServer server, dev.wiktorfila.filasignal.fabric.computer.ProgramHost host) {
                PeripheralRuntime runtime = RUNTIMES.get(server);
                if (runtime != null) {
                    runtime.events().detached(host);
                }
            }

            @Override
            public void failed(MinecraftServer server, dev.wiktorfila.filasignal.fabric.computer.ProgramHost host, String message) {
                PeripheralRuntime runtime = RUNTIMES.get(server);
                if (runtime != null) {
                    runtime.events().failed(host, message);
                }
            }

            @Override
            public void ticked(MinecraftServer server) { }

            @Override
            public String note(MinecraftServer server, dev.wiktorfila.filasignal.fabric.computer.ProgramHost host) {
                PeripheralRuntime runtime = RUNTIMES.get(server);
                var problems = runtime == null ? java.util.List.<String>of() : runtime.events().problems(host.executionId());
                return problems.isEmpty() ? "" : problems.getFirst() + (problems.size() > 1 ? " (+" + (problems.size() - 1) + ")" : "");
            }
        });
    }

    /** Admits already loaded peripherals before dispatching controller startup. */
    public static void attachPending(MinecraftServer server) {
        PeripheralRuntime runtime = RUNTIMES.get(server);
        if (runtime != null) {
            runtime.attachPending();
        }
    }

    static void inputChanged(ServerLevel level, BlockPos pos) {
        PeripheralRuntime runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) {
            runtime.inputChanged(level, pos);
        }
    }

    /** Records a player-placed computer and its owner on the server thread. */
    public static void placedComputer(ServerLevel level, BlockEntity entity, UUID identity, UUID owner) {
        runtime(level.getServer()).placed(level, entity, identity,
                dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.COMPUTER, owner);
    }

    /** Device-side time in the latest tick (sampling, alarms, event routing) in nanoseconds, or 0. */
    public static long tickNanos(MinecraftServer server) {
        PeripheralRuntime runtime = RUNTIMES.get(server);
        return runtime == null ? 0 : runtime.latestNanos();
    }

    /** Loaded devices attached to the topology, or 0. */
    public static int loadedDevices(MinecraftServer server) {
        PeripheralRuntime runtime = RUNTIMES.get(server);
        return runtime == null ? 0 : runtime.loadedDevices();
    }

    /** Registers a computer as the sole execution root and returns its typed host registry. */
    public static HostRegistry computer(ServerLevel level, BlockEntity entity, UUID identity) {
        PeripheralRuntime runtime = runtime(level.getServer());
        runtime.computer(level, entity, identity);
        return DeviceHosts.registry(runtime, identity);
    }

    /** Clears loaded transient outputs when an authorized player stops or restarts execution. */
    public static void clearOutputs(MinecraftServer server, UUID controller) {
        var runtime = runtime(server);
        for (var node : runtime.directory().nodes()) {
            if (controller.equals(node.network()) && runtime.live(node.id()) instanceof PeripheralEntity entity
                    && entity.getLevel() instanceof ServerLevel level) {
                entity.clearOutputs(level);
            }
        }
    }

    /** Resets peripheral observation baselines once a computer has installed its subscriptions. */
    public static void activated(MinecraftServer server, UUID identity) {
        runtime(server).activated(identity);
    }

    /** Capabilities granted by the prototype's operator-managed network policy. */
    public static java.util.Set<String> capabilities() { return DeviceHosts.CAPABILITIES; }

    /** Marks a block entity unavailable between chunk loads or program activations. */
    public static void detached(MinecraftServer server, BlockEntity entity) {
        PeripheralRuntime runtime = RUNTIMES.get(server);
        if (runtime != null) {
            runtime.unload(entity);
        }
    }

    /** Deletes topology only on physical block removal, not chunk unload. */
    public static void removed(ServerLevel level, BlockPos pos) {
        PeripheralRuntime runtime = RUNTIMES.get(level.getServer());
        if (runtime != null) {
            runtime.removed(level, pos);
        }
    }

    /** Uses the same ACL as commands and the linking tool before disclosing program source. */
    public static void authorizeEditor(net.minecraft.server.level.ServerPlayer player, BlockPos position) {
        var level = player.level();
        var runtime = runtime(level.getServer());
        var node = runtime.requireDevice(level, position);
        if (node.kind() != dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.COMPUTER) {
            throw new IllegalArgumentException("Select a Programmable Computer");
        }
        NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), false);
    }

    /** Requires the owner or an administrator, as for discarding a computer's saved globals. */
    public static void authorizeReset(net.minecraft.server.level.ServerPlayer player, BlockPos position) {
        var runtime = runtime(player.level().getServer());
        NetworkPermission.require(runtime.directory(), runtime.requireDevice(player.level(), position),
                NetworkPermission.Actor.from(player), true);
    }

    /**
     * A nearby programmable device the player may manage: same checks as editing a computer, applied to the device's
     * own ACL, which follows its network when linked.
     */
    public static PeripheralEntity programmableDevice(net.minecraft.server.level.ServerPlayer player, BlockPos position) {
        var level = player.level();
        if (!level.getServer().isSameThread() || player.isSpectator()
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) > 64) {
            throw new IllegalArgumentException("Approach the device to manage its program");
        }
        var runtime = runtime(level.getServer());
        var node = runtime.requireDevice(level, position);
        NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), false);
        if (!(runtime.live(node.id()) instanceof PeripheralEntity device) || !device.programmable()) {
            throw new IllegalArgumentException("This device cannot run programs");
        }
        return device;
    }

    /**
     * A programmable device on a computer's network, found by name. The caller has already authorized the player
     * for that computer, whose ACL governs every member of its network.
     */
    public static PeripheralEntity remoteDevice(ServerLevel level, UUID computer, String alias) {
        var runtime = runtime(level.getServer());
        var found = runtime.directory().discover(computer, alias);
        if (found.status() == dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Status.UNKNOWN) {
            throw new IllegalArgumentException("No device named " + alias + " is in this computer's network");
        }
        var node = runtime.directory().node(found.device().orElseThrow().id());
        if (found.status() != dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Status.AVAILABLE
                || !(runtime.live(node.id()) instanceof PeripheralEntity device)) {
            throw new IllegalArgumentException(alias + " is unloaded or disconnected");
        }
        if (!device.programmable()) {
            throw new IllegalArgumentException(alias + " cannot run programs");
        }
        return device;
    }

    /** A player-facing name for a device editing target. */
    public static String describeTarget(PeripheralEntity device, boolean remote) {
        var node = device.getLevel() == null ? null : runtime(device.getLevel().getServer()).directory().node(device.identity());
        var tag = dev.wiktorfila.filasignal.fabric.drive.Drives.tag(device.drive());
        String name = DeviceReadings.title(device.kind()) + (node == null || node.alias() == null ? "" : " " + node.alias());
        String drive = tag == null ? " · no drive" : " · drive" + (tag.label().isEmpty() ? "" : " " + tag.label());
        return name + drive + (remote ? " · remote" : "");
    }

    /** The validation registry of programs written for a device kind ID such as {@code entity_detector}. */
    public static HostRegistry driveHosts(MinecraftServer server, String kind) {
        return LocalHosts.validation(runtime(server), programmableKind(kind));
    }

    /** Capabilities of programs written for a device kind ID. */
    public static java.util.Set<String> driveCapabilities(String kind) {
        return LocalHosts.capabilities(programmableKind(kind));
    }

    /** Kind IDs a drive can be written for. */
    public static java.util.List<String> programmableKinds() {
        return java.util.Arrays.stream(dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.values())
                .filter(kind -> kind.has(dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Capability.EXECUTION)
                        && kind != dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.COMPUTER)
                .map(dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind::id).toList();
    }

    private static dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind programmableKind(String id) {
        for (var kind : dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.values()) {
            if (kind.id().equals(id) && programmableKinds().contains(id)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Drives can target " + String.join(", ", programmableKinds()));
    }

    /** Read-only lines of a loaded Display Module, for diagnostics and tests. Empty when none is loaded there. */
    public static java.util.List<String> displayText(ServerLevel level, BlockPos position) {
        return LoadedChunks.contains(level, position) && level.getBlockEntity(position) instanceof PeripheralEntity entity
                && entity.kind() == dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.DISPLAY
                ? entity.display() : java.util.List.of();
    }

    /** Updates the existing persisted device alias after checking the computer ACL. */
    public static void renameComputer(net.minecraft.server.level.ServerPlayer player, BlockPos position, String name) {
        authorizeEditor(player, position);
        var runtime = runtime(player.level().getServer());
        runtime.alias(runtime.requireDevice(player.level(), position), name.isEmpty() ? null : name);
    }

    /** Returns a bounded controller summary after rechecking access. */
    public static String editorSummary(net.minecraft.server.level.ServerPlayer player, BlockPos position) {
        authorizeEditor(player, position);
        var runtime = runtime(player.level().getServer());
        var root = runtime.requireDevice(player.level(), position);
        var access = runtime.directory().access(root.id());
        var server = player.level().getServer();
        var members = new java.util.ArrayList<NetworkDirectory.Node>();
        for (var node : runtime.directory().nodes()) {
            if (!node.id().equals(root.id()) && root.id().equals(node.network())) {
                members.add(node);
            }
        }
        long online = members.stream().filter(runtime::available).count();
        int programs = 0;
        var programmable = new java.util.ArrayList<String>();
        var rows = new java.util.ArrayList<String>();
        for (var node : members) {
            var live = runtime.live(node.id()) instanceof PeripheralEntity device ? device : null;
            boolean running = live != null && live.programmable()
                    && dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.active(server, live);
            if (running) {
                programs++;
            }
            if (node.alias() != null && node.kind().has(dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Capability.EXECUTION)) {
                programmable.add(node.alias());
            }
            rows.add(row(runtime, node, live, running));
        }
        // Problems first, so a broken device is visible without scrolling.
        rows.sort(java.util.Comparator.comparing((String row) -> !row.contains("offline") && !row.contains("failed")));
        String owner = access == null ? "unclaimed" : java.util.Optional.ofNullable(server.getPlayerList().getPlayer(access.owner()))
                .map(found -> found.getName().getString()).orElse(access.owner().toString().substring(0, 8));
        var lines = new java.util.ArrayList<String>();
        lines.add(root.alias() == null ? "" : root.alias());
        lines.add(members.size() + " devices · " + online + " online · " + programs + " device programs running");
        lines.add("Owner " + owner + " · " + (access == null ? 0 : access.trusted().size()) + " trusted");
        lines.addAll(rows.subList(0, Math.min(5, rows.size())));
        if (rows.size() > 5) {
            lines.add("… and " + (rows.size() - 5) + " more devices");
        }
        lines.add("Diagnostics: " + runtime.events().summary());
        lines.add("Programmable: " + String.join(",", programmable.subList(0, Math.min(24, programmable.size()))));
        return String.join("\n", lines);
    }

    /** One readable dashboard row: kind, name, connectivity and what matters for that kind. */
    private static String row(PeripheralRuntime runtime, NetworkDirectory.Node node, PeripheralEntity live, boolean running) {
        var parts = new java.util.ArrayList<String>();
        parts.add(HardwareHosts.title(node.kind()) + " " + (node.alias() == null ? "(unnamed)" : node.alias()));
        parts.add(runtime.available(node) ? "online" : "offline");
        if (live != null && live.programmable()) {
            var tag = dev.wiktorfila.filasignal.fabric.drive.Drives.tag(live.drive());
            if (tag == null) {
                parts.add("no drive");
            } else {
                var level = (ServerLevel) live.getLevel();
                String fault = dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.lastFault(level, live);
                parts.add(running ? (fault.isEmpty() ? "program running" : "program failed: " + fault)
                        : "program stopped");
            }
        }
        if (live != null && node.kind() == dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.STORAGE_SERVER) {
            try {
                parts.add(live.served().used() / 1024 + " of " + live.served().capacity() / 1024 + " KiB used");
            } catch (HostFailure damaged) {
                parts.add("data damaged");
            }
        }
        if (node.kind() == dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.WIRELESS_BRIDGE) {
            var status = AdvancedApis.bridgeStatus(runtime, node);
            parts.add(status.get("paired") instanceof dev.wiktorfila.filasignal.core.runtime.value.Value.Bool paired
                    && paired.value() ? "paired" : "not paired");
        }
        String row = String.join(" · ", parts);
        return row.length() <= 160 ? row : row.substring(0, 159) + "…";
    }

    static PeripheralRuntime runtime(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Peripheral access requires the Minecraft server thread");
        }
        return RUNTIMES.computeIfAbsent(server, PeripheralRuntime::new);
    }
}
