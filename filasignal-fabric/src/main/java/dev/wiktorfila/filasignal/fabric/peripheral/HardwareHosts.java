package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Capability;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Status;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/**
 * Typed FilaScript objects for dedicated hardware, scoped to one computer's network.
 *
 * <p>Each hardware kind is described by a {@link Spec}: a factory such as {@code detectors.from(name)}, a host
 * object type and its methods. The factory and every method share one resolution path. A device is usable only
 * when it is a member of this computer's network, has the expected kind, is reachable through loaded relays and
 * its block entity is loaded. Methods run on the server thread and report expected failures as
 * {@code (nil, error)}. Every object also has the read-only properties {@code id}, {@code name}, {@code kind} and
 * {@code connected}.
 */
final class HardwareHosts {
    static final Set<String> ACCESS_ERRORS = Set.of("host.denied", "device.unknown", "device.type", "device.unavailable");
    private static final long LOOKUP_COST = 256;
    /** A reference handle has no fixed device; its name is resolved on every use. */
    static final UUID REFERENCE = new UUID(0, 0);

    /** Script type, named-reference factory, player-facing title and a typical device name per hardware kind. */
    record Naming(String type, String reference, String title, String sample) {}

    static final Map<Kind, Naming> NAMING = naming();

    private static Map<Kind, Naming> naming() {
        var names = new java.util.EnumMap<Kind, Naming>(Kind.class);
        names.put(Kind.REPEATER, new Naming("Repeater", "repeaters.named", "Network Repeater", "hub"));
        names.put(Kind.INVENTORY_MODULE, new Naming("InventoryModule", "inventoryModules.named", "Inventory Module", "chests"));
        names.put(Kind.ENTITY_DETECTOR, new Naming("EntityDetector", "detectors.named", "Entity Detector", "gate"));
        names.put(Kind.WEATHER_STATION, new Naming("WeatherStation", "weather.named", "Weather Station", "sky"));
        names.put(Kind.ENVIRONMENT_SENSOR, new Naming("EnvironmentSensor", "environment.named", "Environment Sensor", "probe"));
        names.put(Kind.REDSTONE_CONTROLLER, new Naming("RedstoneController", "redstoneControllers.named", "Redstone Controller", "lamps"));
        names.put(Kind.DISPLAY, new Naming("Display", "displays.named", "Display Module", "screen"));
        names.put(Kind.ALARM, new Naming("Alarm", "alarms.named", "Alarm Module", "siren"));
        names.put(Kind.STORAGE_SERVER, new Naming("StorageServer", "storageServers.named", "Storage Server", "records"));
        names.put(Kind.ACCESS_CONTROLLER, new Naming("AccessController", "accessControllers.named", "Access Controller", "front_door"));
        names.put(Kind.CONTROL_PANEL, new Naming("ControlPanel", "controlPanels.named", "Control Panel", "panel"));
        names.put(Kind.WIRELESS_BRIDGE, new Naming("WirelessBridge", "bridges.named", "Wireless Network Bridge", "link"));
        return java.util.Collections.unmodifiableMap(names);
    }

    /** The hardware kind for a script type name such as {@code EntityDetector}, or null. */
    static Kind kindOf(String type) {
        for (var entry : NAMING.entrySet()) {
            if (entry.getValue().type().equals(type)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** A player-facing kind name such as "Entity Detector". */
    static String title(Kind kind) {
        Naming naming = NAMING.get(kind);
        return naming != null ? naming.title() : switch (kind) {
            case COMPUTER -> "Programmable Computer";
            case INTERFACE -> "Network Interface";
            case SENSOR -> "Sensor";
            default -> kind.id();
        };
    }

    /**
     * {@code detectors.named("gate")}: a reference that names a device without looking it up. File constants may hold
     * it, so {@code on gate.entityEnter(entity)} can name the device whose events a handler receives.
     */
    static Function reference(Kind kind, UUID scope) {
        Naming naming = NAMING.get(kind);
        return new Function(naming.reference(), List.of(ALIAS), List.of(new Slot("device", Set.of(Type.HANDLE), naming.type())),
                "Refer to " + ("AEIOU".indexOf(naming.title().charAt(0)) >= 0 ? "an " : "a ") + naming.title()
                        + " by its name. Use it in a constant, then handle its events with on NAME.event(...).",
                Set.of(), 4, (args, ctx) -> {
            String name = ((Text) args.getFirst()).value();
            if (name.isEmpty() || name.length() > 32) {
                // A reference cannot fail; an invalid name simply never resolves.
                name = name.isEmpty() ? "_" : name.substring(0, 32);
            }
            return List.of(new HostHandle(naming.type(), scope, REFERENCE, name));
        }).withUsage(Set.of(), "const " + naming.sample() + " = " + naming.reference() + "(\"" + naming.sample() + "\")")
                .asReference();
    }

    /** Accepts a constant such as Direction.NORTH or the equivalent text "north". */
    static Slot choice(String name) {
        return new Slot(name, Set.of(Type.STRING, Type.ENUM));
    }

    /** The lowercase text of a constant or string argument: Direction.NORTH and "north" both give "north". */
    static String text(Value value) {
        return value instanceof EnumMember member ? member.name().toLowerCase(java.util.Locale.ROOT) : ((Text) value).value();
    }

    /** A resolved, available device for the duration of one host call. */
    record Target(Node node, PeripheralEntity device, ServerLevel level) {}

    @FunctionalInterface
    interface Operation {
        Value apply(Target target, List<Value> arguments, Context context);
    }

    /** One hardware method. Parameters exclude the receiver. Expected errors include the shared access errors. */
    record Method(String name, List<Slot> parameters, Type result, String documentation, String capability, long cost,
                  Set<String> errors, String example, Operation operation) {}

    /** A hardware kind's factory and object type. */
    record Spec(Kind kind, String type, String factory, String capability, String documentation, String example,
                List<Method> methods) {}

    private final PeripheralRuntime runtime;
    private final UUID controller;
    private final UUID local;
    private final List<Spec> specs;

    HardwareHosts(PeripheralRuntime runtime, UUID controller) {
        this(runtime, controller, null);
    }

    /**
     * With a {@code local} device, the scope is that device alone: handles and functions resolve only to it, it
     * needs no route to a computer, and no other device can be reached.
     */
    HardwareHosts(PeripheralRuntime runtime, UUID controller, UUID local) {
        this.runtime = runtime;
        this.controller = controller;
        this.local = local;
        var all = new ArrayList<Spec>();
        all.addAll(NetworkApis.specs(this));
        all.addAll(SensingApis.specs(this));
        all.addAll(ControlApis.specs(this));
        all.addAll(AdvancedApis.specs(this));
        specs = List.copyOf(all);
    }

    PeripheralRuntime runtime() { return runtime; }
    UUID controller() { return controller; }

    /** True for a device running its own program. */
    boolean local() { return local != null; }

    /** Specs for one kind, used to build a device's own functions. */
    Spec spec(Kind kind) {
        return specs.stream().filter(spec -> spec.kind() == kind).findFirst().orElse(null);
    }

    /**
     * Opens a granted inventory for an inventory module target: through the computer network, or for a local
     * program, directly on that device with no route requirement.
     */
    InventoryService.OpenInventory openInventory(InventoryService inventories, Target target, InventoryBindings.Binding binding) {
        return local() ? inventories.open(target.node().network(), binding, false, false)
                : inventories.open(controller, binding, false);
    }

    /** Resolves the local device for a device-scoped function. */
    Target self(Kind kind) {
        return resolve(new HostHandle("Device", controller, local), kind);
    }

    List<Function> functions() {
        var functions = new ArrayList<Function>();
        for (Spec spec : specs) {
            functions.add(new Function(spec.factory(), List.of(ALIAS),
                    List.of(new Slot("handle", Set.of(Type.HANDLE, Type.NIL), spec.type()), new Slot("error", Set.of(Type.ERROR, Type.NIL))),
                    spec.documentation(), Set.of(spec.capability()), 16,
                    (args, ctx) -> resolveAlias(((Text) args.getFirst()).value(), spec.kind(), spec.type(), ctx))
                    .withUsage(ACCESS_ERRORS, spec.example()));
            if (NAMING.containsKey(spec.kind())) {
                functions.add(reference(spec.kind(), controller));
            }
        }
        functions.addAll(NetworkApis.functions(this));
        return functions;
    }

    List<ObjectType> types() {
        var types = new ArrayList<ObjectType>();
        for (Spec spec : specs) {
            var methods = new HashMap<String, Function>();
            for (Method method : spec.methods()) {
                var parameters = new ArrayList<Slot>();
                parameters.add(handle(receiver(spec.type()), spec.type()));
                parameters.addAll(method.parameters());
                var errors = new HashSet<>(ACCESS_ERRORS);
                errors.addAll(method.errors());
                methods.put(method.name(), new Function(spec.type() + "." + method.name(), parameters, result(method.result()),
                        method.documentation(), Set.of(method.capability()), method.cost(),
                        (args, ctx) -> invoke(spec, method, args, ctx)).withUsage(errors, method.example()));
            }
            types.add(new ObjectType(spec.type(), common(spec.type()), methods, HardwareEvents.objectEvents(spec.kind())));
        }
        return types;
    }

    private HostHandle issue(Node node, String type) {
        return new HostHandle(type, controller, node.id());
    }

    /** Resolves a device name to a typed handle after checking network membership, kind and availability. */
    List<Value> resolveAlias(String alias, Kind kind, String type, Context context) {
        context.charge(alias.length() + LOOKUP_COST);
        try {
            return ok(issue(lookup(alias, kind), type));
        } catch (HostFailure failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    /** Finds a reachable device of a kind in this computer's network by name. */
    private Node lookup(String alias, Kind kind) {
        var found = runtime.directory().discover(controller, alias);
        if (found.status() == Status.UNKNOWN) {
            throw new HostFailure("device.unknown", "No device named " + alias + " is in this computer's network. "
                    + "Check the name in its device form");
        }
        Node node = runtime.directory().node(found.device().orElseThrow().id());
        if (node.kind() != kind) {
            throw new HostFailure("device.type", alias + " is a " + title(node.kind()) + ", not a " + title(kind));
        }
        if (found.status() != Status.AVAILABLE || runtime.live(node.id()) == null) {
            throw new HostFailure("device.unavailable", alias + " is disconnected or its area isn't loaded");
        }
        return node;
    }

    /**
     * Resolves a handle issued to this computer.
     *
     * @throws HostFailure with {@code device.unknown}, {@code device.type} or {@code device.unavailable}
     */
    Target resolve(HostHandle handle, Kind kind) {
        if (local != null) {
            Node node = controller.equals(handle.scope()) && local.equals(handle.resource())
                    ? runtime.directory().node(local) : null;
            if (node == null || node.kind() != kind) {
                throw new HostFailure("device.unknown", "Only this device's own hardware is available to its program");
            }
            if (!(runtime.live(local) instanceof PeripheralEntity device) || !(device.getLevel() instanceof ServerLevel level)) {
                throw new HostFailure("device.unavailable", "The device is unloaded");
            }
            return new Target(node, device, level);
        }
        if (handle.reference() != null) {
            if (!controller.equals(handle.scope())) {
                throw new HostFailure("device.unknown", "That device reference belongs to another program");
            }
            Node node = lookup(handle.reference(), kind);
            if (!(runtime.live(node.id()) instanceof PeripheralEntity device) || !(device.getLevel() instanceof ServerLevel level)) {
                throw new HostFailure("device.unavailable", handle.reference() + " is disconnected or its area isn't loaded");
            }
            return new Target(node, device, level);
        }
        Node node = controller.equals(handle.scope()) ? runtime.directory().node(handle.resource()) : null;
        if (node == null || !controller.equals(node.network())) {
            throw new HostFailure("device.unknown", "The device is no longer in this computer's network");
        }
        if (node.kind() != kind) {
            throw new HostFailure("device.type", "The device is a " + title(node.kind()) + ", not a " + title(kind));
        }
        if (!runtime.available(node) || !(runtime.live(node.id()) instanceof PeripheralEntity device)
                || !(device.getLevel() instanceof ServerLevel level)) {
            throw new HostFailure("device.unavailable", "The device is disconnected or its area isn't loaded");
        }
        return new Target(node, device, level);
    }

    private List<Value> invoke(Spec spec, Method method, List<Value> args, Context context) {
        context.charge(LOOKUP_COST);
        try {
            Target target = resolve((HostHandle) args.getFirst(), spec.kind());
            return ok(method.operation().apply(target, args.subList(1, args.size()), context));
        } catch (HostFailure failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    /** Read-only properties shared by every device object. They never fail; a missing device reads as nil or false. */
    Map<String, Function> common(String type) {
        var properties = new LinkedHashMap<String, Function>();
        properties.put("id", property(type, "id", Type.STRING, handle -> {
            Node node = member(handle);
            return node == null ? Nil.INSTANCE : new Text(node.id().toString());
        }));
        properties.put("name", property(type, "name", Type.STRING, handle -> {
            if (handle.reference() != null) {
                return new Text(handle.reference());
            }
            Node node = member(handle);
            return node == null || node.alias() == null ? Nil.INSTANCE : new Text(node.alias());
        }));
        properties.put("kind", property(type, "kind", Type.STRING, handle -> {
            Node node = member(handle);
            return node == null ? Nil.INSTANCE : new Text(node.kind().id());
        }));
        properties.put("connected", property(type, "connected", Type.BOOL, handle -> new Bool(runtime.available(member(handle)))));
        return properties;
    }

    private Function property(String type, String name, Type result, java.util.function.Function<HostHandle, Value> reader) {
        return new Function(type + "." + name, List.of(handle(receiver(type), type)),
                List.of(new Slot(name, Set.of(result, Type.NIL))), "Read the device's " + name + ".",
                Set.of("devices.read"), 8, (args, ctx) -> {
                    ctx.charge(LOOKUP_COST);
                    return List.of(reader.apply((HostHandle) args.getFirst()));
                });
    }

    private Node member(HostHandle handle) {
        if (!controller.equals(handle.scope())) {
            return null;
        }
        if (handle.reference() != null) {
            var found = runtime.directory().discover(controller, handle.reference());
            return found.device().map(device -> runtime.directory().node(device.id())).orElse(null);
        }
        Node node = runtime.directory().node(handle.resource());
        return node != null && controller.equals(node.network()) ? node : null;
    }

    /** Detached description of a network member. */
    DictValue describe(Node node) {
        var capabilities = new ArrayList<Value>();
        for (Capability capability : node.kind().capabilities()) {
            capabilities.add(new Text(capability.name().toLowerCase(java.util.Locale.ROOT)));
        }
        var values = new LinkedHashMap<String, Value>();
        values.put("id", new Text(node.id().toString()));
        values.put("name", node.alias() == null ? Nil.INSTANCE : new Text(node.alias()));
        values.put("kind", new Text(node.kind().id()));
        values.put("capabilities", new ListValue(capabilities));
        values.put("connected", new Bool(runtime.available(node)));
        return new DictValue(values);
    }

    static String receiver(String type) {
        return Character.toLowerCase(type.charAt(0)) + type.substring(1);
    }

    // Argument helpers shared by the hardware APIs.

    static Direction face(Value value) {
        String name = text(value);
        for (Direction direction : Direction.values()) {
            if (direction.getSerializedName().equals(name)) {
                return direction;
            }
        }
        throw new HostFailure("argument.invalid", "Use a face such as Direction.NORTH: north, south, east, west, up or down");
    }

    static int integer(Value value, int min, int max, String message) {
        double number = ((Num) value).value();
        if (number < min || number > max || number != Math.rint(number)) {
            throw new HostFailure("argument.range", message);
        }
        return (int) number;
    }

    static Slot slot(String name, Type type) {
        return new Slot(name, Set.of(type));
    }

    static Method method(String name, Type result, String documentation, String capability, long cost, String example,
                         Operation operation, Slot... parameters) {
        return new Method(name, List.of(parameters), result, documentation, capability, cost, Set.of(), example, operation);
    }

    static Method method(String name, Type result, String documentation, String capability, long cost, String example,
                         Set<String> errors, Operation operation, Slot... parameters) {
        return new Method(name, List.of(parameters), result, documentation, capability, cost, errors, example, operation);
    }
}
