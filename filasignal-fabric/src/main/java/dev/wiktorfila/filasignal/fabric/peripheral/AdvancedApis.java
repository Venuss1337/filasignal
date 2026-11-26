package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Spec;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.method;
import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.slot;
import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/** Storage Server, Access Controller, Control Panel and Wireless Network Bridge: APIs and player interactions. */
final class AdvancedApis {
    static final String SERVER_READ = "server.read";
    static final String SERVER_WRITE = "server.write";
    static final String ACCESS = "access.control";
    static final String PANEL = "panel.control";
    static final Set<String> STORAGE_ERRORS = Set.of("storage.key", "storage.value", "storage.full", "storage.type",
            "storage.missing", "storage.damaged", "host.denied");
    private static final Slot KEY = new Slot("key", Set.of(Type.STRING));
    private static final Slot ANY = new Slot("value", EnumSet.allOf(Type.class));
    private static final List<String> BUTTON_NAMES = List.of("ONE", "TWO", "THREE", "FOUR");
    private static final List<String> BUTTON_EVENTS = List.of("buttonOne", "buttonTwo", "buttonThree", "buttonFour");

    private AdvancedApis() {}

    static List<Spec> specs(HardwareHosts hosts) {
        return List.of(storageServer(), accessController(hosts), controlPanel(), bridge(hosts));
    }

    // Storage server

    @FunctionalInterface
    interface StorageOperation {
        Value apply(NamedStorage storage, List<Value> args, Context context);
    }

    /** Storage methods. {@code write} marks operations that change data. */
    record StorageMethod(String name, List<Slot> parameters, Type result, String documentation, boolean write,
                         String example, StorageOperation operation) {}

    static List<StorageMethod> storageMethods(boolean clear) {
        var methods = new ArrayList<>(List.of(
                new StorageMethod("read", List.of(KEY), null, "Read a copy of the value stored under a key. "
                        + "A missing key gives the error storage.missing.", false, "count = server.read(\"diamonds\")",
                        (storage, args, ctx) -> {
                            String key = ((Text) args.getFirst()).value();
                            Value value = storage.read(key);
                            if (value == null) {
                                throw new NamedStorage.Failure("storage.missing", "Nothing is stored under " + key);
                            }
                            ctx.charge(storage.sizeOf(key) / 16L);
                            return value;
                        }),
                new StorageMethod("write", List.of(KEY, ANY), Type.BOOL, "Store a copy of a value under a key, replacing any old value.",
                        true, "server.write(\"diamonds\", 64)", (storage, args, ctx) -> {
                            ctx.charge(NamedStorage.encode(args.get(1)).length / 16L);
                            storage.write(((Text) args.getFirst()).value(), args.get(1));
                            return new Bool(true);
                        }),
                new StorageMethod("append", List.of(KEY, ANY), Type.BOOL, "Add a value to the end of the list under a key, "
                        + "creating the list when needed.", true, "server.append(\"visitors\", name)", (storage, args, ctx) -> {
                    String key = ((Text) args.getFirst()).value();
                    ctx.charge(Math.max(0, storage.sizeOf(key)) / 16L + NamedStorage.encode(args.get(1)).length / 16L);
                    storage.append(key, args.get(1));
                    return new Bool(true);
                }),
                new StorageMethod("delete", List.of(KEY), Type.BOOL, "Remove a key. Gives false when nothing was stored.", true,
                        "server.delete(\"old\")", (storage, args, ctx) -> new Bool(storage.delete(((Text) args.getFirst()).value()))),
                new StorageMethod("has", List.of(KEY), Type.BOOL, "True when something is stored under a key.", false,
                        "if server.has(\"diamonds\") { print(\"stocked\") }",
                        (storage, args, ctx) -> new Bool(storage.has(((Text) args.getFirst()).value()))),
                new StorageMethod("keys", List.of(), Type.LIST, "List the stored keys, oldest first.", false,
                        "names = server.keys()", (storage, args, ctx) -> {
                            ctx.charge(4L * storage.size());
                            return new ListValue(storage.keys().stream().<Value>map(Text::new).toList());
                        }),
                new StorageMethod("usage", List.of(), Type.DICT, "Read used and capacity bytes and entries and maxEntries.", false,
                        "space = server.usage()", (storage, args, ctx) -> usage(storage))));
        if (clear) {
            methods.add(new StorageMethod("clear", List.of(), Type.BOOL, "Remove everything stored on the server.", true,
                    "server.clear()", (storage, args, ctx) -> {
                        storage.clear();
                        return new Bool(true);
                    }));
        }
        return methods;
    }

    static DictValue usage(NamedStorage storage) {
        var values = new LinkedHashMap<String, Value>();
        values.put("used", new Num(storage.used()));
        values.put("capacity", new Num(storage.capacity()));
        values.put("entries", new Num(storage.size()));
        values.put("maxEntries", new Num(storage.maxEntries()));
        return new DictValue(values);
    }

    /** Runs a storage method on a server, translating storage failures to errors. */
    static Value storage(PeripheralEntity server, StorageMethod method, List<Value> args, Context context) {
        try {
            Value value = method.operation().apply(server.served(), args, context);
            if (method.write()) {
                server.storageChanged();
            }
            return value;
        } catch (NamedStorage.Failure failure) {
            throw new HostFailure(failure.code(), failure.getMessage());
        } catch (IllegalArgumentException limit) {
            throw new HostFailure("storage.full", limit.getMessage());
        }
    }

    private static Spec storageServer() {
        var methods = new ArrayList<HardwareHosts.Method>();
        for (StorageMethod storage : storageMethods(true)) {
            methods.add(new HardwareHosts.Method(storage.name(), storage.parameters(), storage.result(), storage.documentation(),
                    storage.write() ? SERVER_WRITE : SERVER_READ, 24, STORAGE_ERRORS, storage.example(),
                    (target, args, ctx) -> storage(target.device(), storage, args, ctx)));
        }
        return new Spec(Kind.STORAGE_SERVER, "StorageServer", "storageServers.from", SERVER_READ,
                "Resolve a storage server by name.", "server, err = storageServers.from(\"warehouse\")", methods);
    }

    /**
     * Storage methods on a storage-server reference held by a device program. The program must be on the same network,
     * connected to the server, and the server must allow device programs to write before any change.
     */
    static Map<String, Function> remoteStorage(PeripheralRuntime runtime, UUID self) {
        var methods = new LinkedHashMap<String, Function>();
        String type = "StorageServer";
        for (StorageMethod storage : storageMethods(false)) {
            var parameters = new ArrayList<Slot>();
            parameters.add(handle(HardwareHosts.receiver(type), type));
            parameters.addAll(storage.parameters());
            var errors = new java.util.HashSet<>(STORAGE_ERRORS);
            errors.addAll(HardwareHosts.ACCESS_ERRORS);
            methods.put(storage.name(), new Function(type + "." + storage.name(), parameters, result(storage.result()),
                    storage.documentation(), Set.of(storage.write() ? SERVER_WRITE : SERVER_READ), 24, (args, ctx) -> {
                ctx.charge(256);
                var handle = (HostHandle) args.getFirst();
                try {
                    if (runtime == null || handle.reference() == null || !self.equals(handle.scope())) {
                        throw new HostFailure("device.unknown", "Use a storage server reference made by this program");
                    }
                    Node node = runtime.events().resolve(self, false, handle.reference(), Kind.STORAGE_SERVER);
                    if (!(runtime.live(node.id()) instanceof PeripheralEntity server)) {
                        throw new HostFailure("device.unavailable", handle.reference() + " is disconnected or its area isn't loaded");
                    }
                    if (storage.write() && !server.deviceWrites()) {
                        throw new HostFailure("host.denied", handle.reference() + " only lets device programs read");
                    }
                    return ok(storage(server, storage, args.subList(1, args.size()), ctx));
                } catch (HostFailure failure) {
                    return error(failure.code(), failure.getMessage());
                }
            }).withUsage(errors, storage.example()));
        }
        return methods;
    }

    // Access controller

    private static Spec accessController(HardwareHosts hosts) {
        return new Spec(Kind.ACCESS_CONTROLLER, "AccessController", "accessControllers.from", ACCESS,
                "Resolve an access controller by name.", "door, err = accessControllers.from(\"front_door\")", List.of(
                method("open", Type.BOOL, "Power the outputs for the configured number of seconds, as if access was granted.",
                        ACCESS, 24, "door.open()", Set.of("access.limit"), (target, args, ctx) -> {
                            open(hosts.runtime(), target.device(), target.level(), target.device().pulseSeconds());
                            return new Bool(true);
                        }),
                method("openFor", Type.BOOL, "Power the outputs for 1 to 30 seconds.", ACCESS, 24, "door.openFor(10)",
                        Set.of("access.limit", "argument.range"), (target, args, ctx) -> {
                            open(hosts.runtime(), target.device(), target.level(),
                                    HardwareHosts.integer(args.getFirst(), 1, 30, "Open for 1 to 30 seconds"));
                            return new Bool(true);
                        }, slot("seconds", Type.NUMBER)),
                method("close", Type.BOOL, "Turn the outputs off now.", ACCESS, 16, "door.close()", (target, args, ctx) -> {
                    hosts.runtime().pulses().close(target.device(), target.level());
                    return new Bool(true);
                }),
                method("isOpen", Type.BOOL, "True while the outputs are powered.", ACCESS, 4, "if door.isOpen() { print(\"open\") }",
                        (target, args, ctx) -> new Bool(hosts.runtime().pulses().open(target.device()))),
                method("allowed", Type.LIST, "List the names of players on the access list.", ACCESS, 8,
                        "names = door.allowed()", (target, args, ctx) -> new ListValue(target.device().allowed().values()
                                .stream().<Value>map(Text::new).toList())),
                method("isPublic", Type.BOOL, "True when anyone may open the door.", ACCESS, 4, "open = door.isPublic()",
                        (target, args, ctx) -> new Bool(target.device().publicUse())),
                method("recent", Type.LIST, "List up to 10 recent attempts, newest first, as text.", ACCESS, 8,
                        "for line in door.recent() { print(line) }", (target, args, ctx) -> new ListValue(target.device()
                                .recent().stream().<Value>map(Text::new).toList()))));
    }

    private static void open(PeripheralRuntime runtime, PeripheralEntity device, ServerLevel level, int seconds) {
        runtime.pulses().open(device, level, runtime.tickCount() + seconds * 20L);
    }

    /**
     * A player used an Access Controller. Owners, trusted players and players on its list are let in; anyone is when
     * it is public. A program can react to the attempt, but cannot weaken this rule.
     */
    static void attempt(PeripheralRuntime runtime, ServerPlayer player, PeripheralEntity controller) {
        if (!(controller.getLevel() instanceof ServerLevel level) || controller.identity() == null) {
            return;
        }
        Node node = runtime.directory().node(controller.identity());
        boolean granted = controller.publicUse() || controller.allowed().containsKey(player.getUUID())
                || NetworkPermission.allows(runtime.directory(), node, NetworkPermission.Actor.from(player), false);
        String name = player.getName().getString();
        if (granted) {
            try {
                open(runtime, controller, level, controller.pulseSeconds());
            } catch (HostFailure busy) {
                player.sendOverlayMessage(Component.literal(busy.getMessage()));
                return;
            }
        }
        level.playSound(null, controller.getBlockPos(), granted ? SoundEvents.NOTE_BLOCK_CHIME.value()
                : SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.6f, granted ? 1.4f : 0.7f);
        player.sendOverlayMessage(Component.literal(granted ? "Access granted" : "Access denied"));
        long day = WorldReadings.time(level).get("day") instanceof Num number ? (long) number.value() : 0;
        controller.remember(name + (granted ? " granted" : " denied") + " · day " + day);
        var values = new LinkedHashMap<String, Value>();
        values.put("name", new Text(name));
        values.put("uuid", new Text(player.getUUID().toString()));
        values.put("result", new EnumMember("Access", granted ? "GRANTED" : "DENIED"));
        values.put("granted", new Bool(granted));
        Value who = new DictValue(values);
        runtime.events().publish(controller.identity(), "accessAttempted", List.of(who));
        runtime.events().publish(controller.identity(), granted ? "accessGranted" : "accessDenied", List.of(who));
    }

    // Control panel

    private static Spec controlPanel() {
        var button = new Slot("button", Set.of(Type.NUMBER, Type.ENUM));
        return new Spec(Kind.CONTROL_PANEL, "ControlPanel", "controlPanels.from", PANEL,
                "Resolve a control panel by name.", "panel, err = controlPanels.from(\"gate_panel\")", List.of(
                method("labels", Type.LIST, "List the four button labels.", PANEL, 4, "names = panel.labels()",
                        (target, args, ctx) -> new ListValue(target.device().labels().stream().<Value>map(Text::new).toList())),
                method("label", Type.STRING, "Read one button's label. Use Button.ONE to Button.FOUR or 1 to 4.", PANEL, 4,
                        "name = panel.label(Button.ONE)", Set.of("argument.range"),
                        (target, args, ctx) -> new Text(target.device().labels().get(button(args.getFirst()) - 1)), button),
                method("setLabel", Type.BOOL, "Change a button's label: 1 to 24 characters.", PANEL, 16,
                        "panel.setLabel(Button.ONE, \"Open Gate\")", Set.of("argument.range"), (target, args, ctx) -> {
                            try {
                                target.device().label(button(args.getFirst()), ((Text) args.get(1)).value());
                            } catch (IllegalArgumentException invalid) {
                                throw new HostFailure("argument.range", invalid.getMessage());
                            }
                            return new Bool(true);
                        }, button, slot("label", Type.STRING))));
    }

    /** A button number from 1 to 4, given as Button.ONE or a number. */
    static int button(Value value) {
        if (value instanceof EnumMember member && member.enumeration().equals("Button")) {
            return BUTTON_NAMES.indexOf(member.name()) + 1;
        }
        if (value instanceof Num) {
            return HardwareHosts.integer(value, 1, 4, "Choose button 1 to 4, such as Button.ONE");
        }
        throw new HostFailure("argument.range", "Choose button 1 to 4, such as Button.ONE");
    }

    /** True when the player may press this panel's buttons. */
    static boolean mayPress(PeripheralRuntime runtime, ServerPlayer player, PeripheralEntity panel) {
        Node node = runtime.directory().node(panel.identity());
        return panel.publicUse() || NetworkPermission.allows(runtime.directory(), node, NetworkPermission.Actor.from(player), false);
    }

    /** A player pressed a button: give feedback and raise buttonPressed and the button's own event. */
    static String press(PeripheralRuntime runtime, ServerPlayer player, PeripheralEntity panel, int number) {
        if (number < 1 || number > 4) {
            throw new IllegalArgumentException("Choose button 1 to 4");
        }
        if (!mayPress(runtime, player, panel)) {
            throw new IllegalArgumentException("This panel only accepts its owner and trusted players");
        }
        String label = panel.labels().get(number - 1);
        if (panel.getLevel() instanceof ServerLevel level) {
            level.playSound(null, panel.getBlockPos(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.5f, 1.0f);
        }
        var values = new LinkedHashMap<String, Value>();
        values.put("id", new EnumMember("Button", BUTTON_NAMES.get(number - 1)));
        values.put("number", new Num(number));
        values.put("label", new Text(label));
        values.put("player", new Text(player.getName().getString()));
        runtime.events().publish(panel.identity(), "buttonPressed", List.of(new DictValue(values)));
        runtime.events().publish(panel.identity(), BUTTON_EVENTS.get(number - 1), List.of(new Text(player.getName().getString())));
        return "Pressed: " + label;
    }

    // Wireless bridge

    private static Spec bridge(HardwareHosts hosts) {
        return new Spec(Kind.WIRELESS_BRIDGE, "WirelessBridge", "bridges.from", "devices.read",
                "Resolve a wireless network bridge by name.", "link, err = bridges.from(\"north_link\")", List.of(
                method("status", Type.DICT, "Describe the pairing: paired, peer (its name), connected and distance in blocks.",
                        "devices.read", 16, "info = link.status()",
                        (target, args, ctx) -> bridgeStatus(hosts.runtime(), target.node()))));
    }

    static DictValue bridgeStatus(PeripheralRuntime runtime, Node node) {
        var directory = runtime.directory();
        UUID peer = directory.bridgePeer(node.id());
        Node other = peer == null ? null : directory.node(peer);
        var values = new LinkedHashMap<String, Value>();
        values.put("paired", new Bool(other != null));
        values.put("peer", other == null || other.alias() == null ? Nil.INSTANCE : new Text(other.alias()));
        values.put("connected", new Bool(other != null && runtime.live(peer) != null && runtime.live(node.id()) != null));
        values.put("distance", other == null ? Nil.INSTANCE : new Num(Math.round(distance(node, other))));
        return new DictValue(values);
    }

    static double distance(Node a, Node b) {
        double x = a.location().x() - b.location().x();
        double y = a.location().y() - b.location().y();
        double z = a.location().z() - b.location().z();
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Loaded, unpaired bridges the player owns within pairing range of this bridge, nearest first, at most 8. */
    static List<Node> candidates(PeripheralRuntime runtime, ServerPlayer player, Node bridge) {
        var directory = runtime.directory();
        var found = new ArrayList<Node>();
        var actor = NetworkPermission.Actor.from(player);
        for (Node node : directory.nodes()) {
            if (node.kind() != Kind.WIRELESS_BRIDGE || node.id().equals(bridge.id()) || runtime.live(node.id()) == null
                    || directory.bridgePeer(node.id()) != null
                    || !node.location().dimension().equals(bridge.location().dimension())
                    || distance(node, bridge) > DeviceNetwork.BRIDGE_RANGE
                    || !NetworkPermission.allows(directory, node, actor, true)
                    || (node.network() != null && bridge.network() != null && !node.network().equals(bridge.network()))
                    || (node.network() == null && bridge.network() == null)) {
                continue;
            }
            found.add(node);
        }
        found.sort(java.util.Comparator.comparingDouble(node -> distance(node, bridge)));
        return found.subList(0, Math.min(8, found.size()));
    }

    /** Pairs two bridges after the owner checks. Both must be loaded and in range; it never merges two networks. */
    static String pair(PeripheralRuntime runtime, ServerPlayer player, Node bridge, UUID target) {
        var directory = runtime.directory();
        var actor = NetworkPermission.Actor.from(player);
        NetworkPermission.require(directory, bridge, actor, true);
        Node other = directory.node(target);
        if (other == null || other.kind() != Kind.WIRELESS_BRIDGE || runtime.live(target) == null) {
            throw new IllegalArgumentException("The other bridge is missing or its area isn't loaded");
        }
        NetworkPermission.require(directory, other, actor, true);
        if (directory.bridgePeer(bridge.id()) != null || directory.bridgePeer(target) != null) {
            throw new IllegalArgumentException("Unpair a bridge before pairing it again");
        }
        if (bridge.network() != null && other.network() != null && !bridge.network().equals(other.network())) {
            throw new IllegalArgumentException("These bridges belong to different computers' networks and cannot be joined");
        }
        if (bridge.network() == null && other.network() == null) {
            throw new IllegalArgumentException("Link one of the bridges to a computer's network first");
        }
        if (!bridge.location().dimension().equals(other.location().dimension())) {
            throw new IllegalArgumentException("Bridges must be in the same dimension");
        }
        double distance = distance(bridge, other);
        if (distance > DeviceNetwork.BRIDGE_RANGE) {
            throw new IllegalArgumentException("Out of range: " + Math.round(distance) + " blocks apart, the limit is "
                    + DeviceNetwork.BRIDGE_RANGE);
        }
        runtime.link(bridge.id(), target, false);
        return "Paired " + (other.alias() == null ? "with the bridge" : "with " + other.alias()) + " · "
                + Math.round(distance) + " blocks";
    }

    static String unpair(PeripheralRuntime runtime, ServerPlayer player, Node bridge) {
        var directory = runtime.directory();
        NetworkPermission.require(directory, bridge, NetworkPermission.Actor.from(player), true);
        UUID peer = directory.bridgePeer(bridge.id());
        if (peer == null) {
            throw new IllegalArgumentException("This bridge is not paired");
        }
        directory.link(bridge.id(), peer, true);
        runtime.topologyChanged();
        return "Bridges unpaired";
    }
}
