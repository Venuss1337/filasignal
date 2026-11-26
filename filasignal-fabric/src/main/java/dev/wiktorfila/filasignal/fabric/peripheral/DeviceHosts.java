package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Status;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.*;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/** Describes device and redstone objects and delegates member and procedural calls through the same checks. */
final class DeviceHosts {
    static final Set<String> CAPABILITIES = Set.of("devices.read", "redstone.read", "redstone.write", InventoryHosts.READ,
            InventoryHosts.TRANSFER, SensingApis.DETECTOR, SensingApis.WEATHER, SensingApis.ENVIRONMENT,
            ControlApis.DISPLAY_READ, ControlApis.DISPLAY_WRITE, ControlApis.ALARM, DriveHosts.READ, DriveHosts.CONTROL,
            EventHosts.EMIT, AdvancedApis.SERVER_READ, AdvancedApis.SERVER_WRITE, AdvancedApis.ACCESS, AdvancedApis.PANEL);
    private static final String DEVICE = "Device";
    private static final String REDSTONE = "RedstonePort";
    private static final Set<String> PORT_ERRORS = Set.of("port.unknown", "device.unavailable", "world.unloaded", "host.denied");
    private static final Slot STRENGTH = new Slot("strength", Set.of(Type.NUMBER));
    private final PeripheralRuntime runtime;
    private final UUID controller;

    private DeviceHosts(PeripheralRuntime runtime, UUID controller) {
        this.runtime = runtime;
        this.controller = controller;
    }

    static HostRegistry registry(PeripheralRuntime runtime, UUID controller) {
        return new DeviceHosts(runtime, controller).registry();
    }

    private HostRegistry registry() {
        var inventory = new InventoryHosts(runtime, controller);
        var hardware = new HardwareHosts(runtime, controller);
        var drives = new DriveHosts(runtime, controller);
        var functions = new java.util.ArrayList<>(hardware.functions());
        functions.add(drives.factory());
        functions.addAll(List.of(
                inventory.factory(),
                new Function("devices.get", List.of(ALIAS), result(Type.DICT),
                        "Read a reachable device description.", Set.of("devices.read"), 16,
                        (args, ctx) -> discover((Text) args.getFirst(), false, ctx)),
                new Function("devices.connected", List.of(ALIAS), result(Type.BOOL),
                        "Test connectivity of a known device.", Set.of("devices.read"), 16,
                        (args, ctx) -> discover((Text) args.getFirst(), true, ctx)),
                new Function("redstone.read", List.of(ALIAS), result(Type.NUMBER),
                        "Read a configured input or output port.", Set.of("redstone.read"), 12,
                        (args, ctx) -> byAlias((Text) args.getFirst(), null, false, ctx)),
                new Function("redstone.set", List.of(ALIAS, STRENGTH), result(Type.BOOL),
                        "Set an output port to an integer strength from 0 through 15.", Set.of("redstone.write"), 30,
                        (args, ctx) -> byAlias((Text) args.getFirst(), (Num) args.get(1), false, ctx)),
                factory("devices.from", DEVICE, "devices.read", "Resolve an available device alias.",
                        (args, ctx) -> deviceHandle((Text) args.getFirst(), ctx))
                        .withUsage(Set.of("host.denied", "device.unknown", "device.unavailable"), "router, err = devices.from(\"farm_router\")"),
                factory("redstone.from", REDSTONE, "redstone.read", "Resolve an available redstone port alias.",
                        (args, ctx) -> byAlias((Text) args.getFirst(), null, true, ctx))
                        .withUsage(PORT_ERRORS, "door, err = redstone.from(\"door\")"),
                new Function("log.error", List.of(new Slot("message", Set.of(Type.STRING, Type.ERROR))), List.of(),
                        "Log an error using the controller output allowance.", Set.of(), 1, (args, ctx) -> {
                    ctx.print(args.getFirst());
                    return List.of();
                })
        ));
        functions.addAll(EventHosts.functions(runtime, controller.toString()));
        var types = new java.util.ArrayList<>(hardware.types());
        types.addAll(List.of(deviceType(hardware), redstoneType(), inventory.type(), drives.type()));
        return HardwareEvents.registry(functions, types, HardwareEvents.computerEvents());
    }

    private ObjectType deviceType(HardwareHosts hardware) {
        var properties = new java.util.HashMap<>(hardware.common(DEVICE));
        properties.put("capabilities", deviceProperty("capabilities", Type.LIST, (args, ctx) -> {
            ctx.charge(64);
            Node node = device((HostHandle) args.getFirst());
            return List.of(node == null ? Nil.INSTANCE : hardware.describe(node).get("capabilities"));
        }));
        properties.putAll(Map.of(
                "position", deviceProperty("position", Type.DICT, (args, ctx) -> {
                    ctx.charge(256);
                    Node node = device((HostHandle) args.getFirst());
                    return List.of(runtime.available(node) ? position(node.location()) : Nil.INSTANCE);
                })
        ));
        return new ObjectType(DEVICE, properties, Map.of());
    }

    private Function deviceProperty(String name, Type type, Handler reader) {
        return new Function(DEVICE + "." + name, List.of(handle("device", DEVICE)),
                List.of(new Slot(name, Set.of(type, Type.NIL))), "Read the device's " + name + ".",
                Set.of("devices.read"), 8, reader);
    }

    private ObjectType redstoneType() {
        return new ObjectType(REDSTONE, Map.of(), Map.of(
                "read", new Function(REDSTONE + ".read", List.of(handle("port", REDSTONE)), result(Type.NUMBER),
                        "Read current port strength.", Set.of("redstone.read"), 12,
                        (args, ctx) -> byHandle((HostHandle) args.getFirst(), null, ctx))
                        .withUsage(PORT_ERRORS, "power, err = door.read()"),
                "set", new Function(REDSTONE + ".set", List.of(handle("port", REDSTONE), STRENGTH), result(Type.BOOL),
                        "Set an output strength from 0 through 15.", Set.of("redstone.write"), 30,
                        (args, ctx) -> byHandle((HostHandle) args.getFirst(), (Num) args.get(1), ctx))
                        .withUsage(Set.of("host.denied", "port.unknown", "device.unavailable", "world.unloaded", "port.mode", "argument.range"),
                                "ok, err = door.set(15)")));
    }

    private List<Value> deviceHandle(Text alias, Context context) {
        context.charge(alias.value().length() + 256L);
        var found = runtime.directory().discover(controller, alias.value());
        if (found.status() == Status.UNKNOWN) {
            return error("device.unknown", "No device has that alias in this network");
        }
        UUID id = found.device().orElseThrow().id();
        return found.status() == Status.AVAILABLE && runtime.live(id) != null
                ? ok(new HostHandle(DEVICE, controller, id))
                : error("device.unavailable", "Device is unloaded or disconnected");
    }

    private Node device(HostHandle handle) {
        Node node = runtime.directory().node(handle.resource());
        return node != null && controller.equals(handle.scope()) && controller.equals(node.network()) ? node : null;
    }

    private List<Value> discover(Text alias, boolean connected, Context context) {
        List<Value> result = deviceHandle(alias, context);
        if (result.getFirst() instanceof HostHandle handle) {
            if (connected) {
                return ok(new Bool(true));
            }
            var node = device(handle);
            var location = node.location();
            var info = new DictValue();
            info.put("alias", alias);
            info.put("id", new Text(node.id().toString()));
            info.put("kind", new Text(node.kind().name().toLowerCase(Locale.ROOT)));
            info.put("x", new Num(location.x()));
            info.put("y", new Num(location.y()));
            info.put("z", new Num(location.z()));
            return ok(info);
        }
        if (connected && ((ErrorValue) result.getLast()).code().equals("device.unavailable")) {
            return ok(new Bool(false));
        }
        return result;
    }

    private static DictValue position(dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Location location) {
        return new DictValue(Map.of("x", new Num(location.x()), "y", new Num(location.y()), "z", new Num(location.z()),
                "dimension", new Text(location.dimension())));
    }

    private List<Value> byAlias(Text alias, Num strength, boolean resolve, Context context) {
        context.charge(alias.value().length() + 256L);
        return redstone(runtime.directory().port(controller, alias.value()), strength, resolve);
    }

    private List<Value> byHandle(HostHandle handle, Num strength, Context context) {
        context.charge(256);
        PortTarget target = controller.equals(handle.scope()) ? runtime.directory().port(handle.resource()) : null;
        return redstone(target, strength, false);
    }

    private List<Value> redstone(PortTarget target, Num strength, boolean resolve) {
        if (target == null || !controller.equals(runtime.directory().node(target.device()).network())) {
            return error("port.unknown", "The redstone port no longer exists in this network");
        }
        if (!runtime.available(runtime.directory().node(target.device()))
                || !(runtime.live(target.device()) instanceof PeripheralEntity device)
                || !(device.getLevel() instanceof ServerLevel level)) {
            return error("device.unavailable", "Redstone interface is unloaded or disconnected");
        }
        Port port = target.port();
        var neighbor = device.getBlockPos().relative(port.face());
        if (!LoadedChunks.signalArea(level, neighbor)) {
            return error("world.unloaded", "The port's adjacent chunk is unloaded");
        }
        if (resolve) {
            return ok(new HostHandle(REDSTONE, controller, port.id()));
        }
        if (strength == null) {
            int power = port.mode() == Mode.INPUT ? level.getSignal(neighbor, port.face()) : device.output(port.face());
            return ok(new Num(power));
        }
        if (port.mode() != Mode.OUTPUT) {
            return error("port.mode", "Configure the selected face as an output before writing");
        }
        double number = strength.value();
        if (number < 0 || number > 15 || number != Math.rint(number)) {
            return error("argument.range", "Redstone strength must be an integer from 0 through 15");
        }
        device.output(level, port.face(), (int) number);
        return ok(new Bool(true));
    }
}
