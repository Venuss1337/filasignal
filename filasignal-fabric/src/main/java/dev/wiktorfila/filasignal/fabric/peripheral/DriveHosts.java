package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Capability;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Status;
import dev.wiktorfila.filasignal.fabric.drive.DriveRecord;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import net.minecraft.server.level.ServerLevel;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/**
 * Lets a computer reach the drives installed in devices on its network. {@code drives.from(name)} resolves the
 * drive currently in a named device. The handle names the drive itself, so it stops working as soon as that drive
 * is removed, even if another drive is inserted. Reads are per key and bounded by the drive limits. Start, stop
 * and restart are queued and applied before the next scheduler dispatch. Remote writes and deletes are not offered.
 */
final class DriveHosts {
    static final String READ = "drives.read";
    static final String CONTROL = "drives.control";
    private static final String TYPE = "Drive";
    private static final Set<String> ERRORS = Set.of("host.denied", "device.unknown", "device.type", "device.unavailable",
            "drive.missing", "drive.removed", "drive.in_use", "drive.damaged");

    private final PeripheralRuntime runtime;
    private final UUID controller;

    DriveHosts(PeripheralRuntime runtime, UUID controller) {
        this.runtime = runtime;
        this.controller = controller;
    }

    private record Mounted(PeripheralEntity device, NetworkDirectory.Node node, DriveRecord record) {}

    Function factory() {
        return new Function("drives.from", List.of(ALIAS),
                List.of(new Slot("handle", Set.of(Type.HANDLE, Type.NIL), TYPE), new Slot("error", Set.of(Type.ERROR, Type.NIL))),
                "Resolve the drive installed in a named programmable device.", Set.of(READ), 16, (args, ctx) -> {
            String alias = ((Text) args.getFirst()).value();
            ctx.charge(256L + alias.length());
            var found = runtime.directory().discover(controller, alias);
            if (found.status() == Status.UNKNOWN) {
                return error("device.unknown", "No device named " + alias + " is in this computer's network");
            }
            var node = runtime.directory().node(found.device().orElseThrow().id());
            if (!node.kind().has(Capability.EXECUTION) || node.kind() == dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind.COMPUTER) {
                return error("device.type", alias + " cannot hold a drive");
            }
            if (found.status() != Status.AVAILABLE || !(runtime.live(node.id()) instanceof PeripheralEntity device)) {
                return error("device.unavailable", alias + " is unloaded or disconnected");
            }
            UUID drive = device.driveId();
            if (drive == null) {
                return error("drive.missing", alias + " has no drive installed");
            }
            return ok(new HostHandle(TYPE, controller, drive));
        }).withUsage(ERRORS, "drive, err = drives.from(\"gate\")");
    }

    ObjectType type() {
        var methods = new HashMap<String, Function>();
        methods.put("info", method("info", List.of(), Type.DICT, "Describe the drive: id, label, target, device, kind, "
                + "revision, state, running and storage usage.", READ, "meta, err = drive.info()", (mounted, args, ctx) -> {
            var tag = Drives.tag(mounted.device().drive());
            var values = new LinkedHashMap<String, Value>();
            values.put("id", new Text(tag.id().toString()));
            values.put("label", new Text(tag.label()));
            values.put("target", new Text(tag.target()));
            values.put("device", mounted.node().alias() == null ? Nil.INSTANCE : new Text(mounted.node().alias()));
            values.put("kind", new Text(mounted.node().kind().id()));
            values.put("revision", mounted.record() == null ? Nil.INSTANCE : new Text(mounted.record().revision().toString()));
            values.put("state", new Text(ComputerLifecycle.stateLine(level(mounted), mounted.device())));
            values.put("running", new Bool(ComputerLifecycle.active(level(mounted).getServer(), mounted.device())));
            values.put("used", new Num(mounted.record() == null ? 0 : mounted.record().storage().used()));
            values.put("entries", new Num(mounted.record() == null ? 0 : mounted.record().storage().size()));
            return new DictValue(values);
        }));
        methods.put("program", method("program", List.of(), Type.STRING, "Read the drive's program source.", READ,
                "source, err = drive.program()", (mounted, args, ctx) -> {
            String source = mounted.record() == null ? "" : mounted.record().source();
            ctx.charge(source.length() / 8L);
            return new Text(source);
        }));
        methods.put("keys", method("keys", List.of(), Type.LIST, "List the drive's stored keys.", READ,
                "keys, err = drive.keys()", (mounted, args, ctx) -> {
            List<String> keys = mounted.record() == null ? List.of() : mounted.record().storage().keys();
            ctx.charge(4L * keys.size());
            return new ListValue(keys.stream().<Value>map(Text::new).toList());
        }));
        methods.put("has", method("has", List.of(new Slot("key", Set.of(Type.STRING))), Type.BOOL,
                "Test whether the drive stores a key.", READ, "found, err = drive.has(\"history\")", (mounted, args, ctx) ->
                new Bool(mounted.record() != null && mounted.record().storage().has(((Text) args.getFirst()).value()))));
        methods.put("read", new Function(TYPE + ".read", List.of(handle("drive", TYPE), new Slot("key", Set.of(Type.STRING))),
                List.of(new Slot("value", EnumSet.allOf(Type.class)), new Slot("error", Set.of(Type.ERROR, Type.NIL))),
                "Read a copy of one stored value. A missing key returns storage.missing.", Set.of(READ), 32,
                (args, ctx) -> invoke(args, ctx, (mounted, rest, context) -> {
                    String key = ((Text) rest.getFirst()).value();
                    Value value = mounted.record() == null ? null : mounted.record().storage().read(key);
                    if (value == null) {
                        throw new HostFailure("storage.missing", "Nothing is stored under " + key);
                    }
                    context.charge(mounted.record().storage().sizeOf(key) / 16L);
                    return value;
                })).withUsage(union("storage.missing"), "history, err = drive.read(\"history\")"));
        methods.put("usage", method("usage", List.of(), Type.DICT, "Read storage used, capacity, entries and maxEntries.",
                READ, "usage, err = drive.usage()", (mounted, args, ctx) -> LocalHosts.usage(mounted.record())));
        methods.put("status", method("status", List.of(), Type.DICT, "Read the program state, last error, statistics and who "
                + "started it.", READ, "status, err = drive.status()", (mounted, args, ctx) -> {
            var values = new LinkedHashMap<String, Value>();
            values.put("state", new Text(ComputerLifecycle.stateLine(level(mounted), mounted.device())));
            values.put("running", new Bool(ComputerLifecycle.active(level(mounted).getServer(), mounted.device())));
            values.put("error", new Text(ComputerLifecycle.lastFault(level(mounted), mounted.device())));
            values.put("statistics", new Text(ComputerLifecycle.statistics(level(mounted), mounted.device())));
            values.put("startedBy", new Text(mounted.device().startedBy()));
            return new DictValue(values);
        }));
        for (String action : List.of("start", "stop", "restart")) {
            methods.put(action, method(action, List.of(), Type.BOOL, switch (action) {
                case "start" -> "Request that the drive's program start. Applied before the next scheduler tick.";
                case "stop" -> "Request that the drive's program stop, saving its globals.";
                default -> "Request a restart of the drive's program with its saved globals.";
            }, CONTROL, "ok, err = drive." + action + "()", (mounted, args, ctx) -> {
                boolean enable = !action.equals("stop");
                // A computer program acts with its network owner's authority.
                var access = runtime.directory().access(controller);
                if (!ComputerLifecycle.requestControl(level(mounted).getServer(), mounted.device(), enable, "computer " + controller,
                        access == null ? null : access.owner(), null)) {
                    throw new HostFailure("drive.busy", "Too many program changes are waiting; try again next tick");
                }
                return new Bool(true);
            }));
        }
        return new ObjectType(TYPE, java.util.Map.of(), methods);
    }

    private static ServerLevel level(Mounted mounted) {
        return (ServerLevel) mounted.device().getLevel();
    }

    @FunctionalInterface
    private interface Operation {
        Value apply(Mounted mounted, List<Value> args, Context context);
    }

    private Function method(String name, List<Slot> parameters, Type result, String documentation, String capability,
                            String example, Operation operation) {
        var all = new java.util.ArrayList<Slot>();
        all.add(handle("drive", TYPE));
        all.addAll(parameters);
        return new Function(TYPE + "." + name, all, result(result), documentation, Set.of(capability), 16,
                (args, ctx) -> invoke(args, ctx, operation)).withUsage(union("drive.busy"), example);
    }

    private static Set<String> union(String extra) {
        var errors = new java.util.HashSet<>(ERRORS);
        errors.add(extra);
        return errors;
    }

    private List<Value> invoke(List<Value> args, Context context, Operation operation) {
        context.charge(256);
        try {
            return ok(operation.apply(resolve((HostHandle) args.getFirst()), args.subList(1, args.size()), context));
        } catch (HostFailure failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    /** Finds the device that currently holds the handle's drive and checks it is reachable in this network. */
    private Mounted resolve(HostHandle handle) {
        Object holder = controller.equals(handle.scope()) ? runtime.drives().holder(handle.resource()) : null;
        if (!(holder instanceof PeripheralEntity device) || !handle.resource().equals(device.driveId())) {
            throw new HostFailure("drive.removed", "The drive was removed or moved to an unloaded block");
        }
        var node = runtime.directory().node(device.identity());
        if (node == null || !controller.equals(node.network())) {
            throw new HostFailure("device.unknown", "The drive is no longer in this computer's network");
        }
        if (!runtime.available(node)) {
            throw new HostFailure("device.unavailable", "The device holding the drive is unloaded or disconnected");
        }
        return new Mounted(device, node, device.driveRecord(false));
    }
}
