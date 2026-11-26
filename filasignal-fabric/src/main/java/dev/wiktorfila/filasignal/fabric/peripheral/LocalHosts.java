package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.drive.DriveRecord;
import dev.wiktorfila.filasignal.fabric.drive.Drives;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/**
 * Host functions for a program running on a device from its drive. The program sees only:
 * <ul>
 *   <li>{@code device.*}: its own hardware, built from the same specs computers use, bound to this physical device;</li>
 *   <li>{@code storage.*}: the installed drive's named storage;</li>
 *   <li>{@code log.error} and the language built-ins.</li>
 * </ul>
 * There is no network discovery, no other device and no factory that takes a name. An inventory module's program
 * reaches only the faces the owner approved on that module.
 */
final class LocalHosts {
    static final String STORAGE_READ = "storage.read";
    static final String STORAGE_WRITE = "storage.write";
    private static final Slot KEY = new Slot("key", Set.of(Type.STRING));
    private static final Slot VALUE = new Slot("value", EnumSet.allOf(Type.class));
    private static final Set<String> STORAGE_ERRORS = Set.of("host.denied", "drive.missing", "drive.in_use", "drive.damaged",
            "storage.key", "storage.value", "storage.full", "storage.type", "storage.missing");

    private LocalHosts() {}

    /** The registry for a device's own program. Functions resolve the device at call time. */
    static HostRegistry registry(PeripheralRuntime runtime, PeripheralEntity device) {
        return build(runtime, device.identity(), device.kind(), device);
    }

    /** A registry for validating a drive program for a kind without a device, for example in a computer slot. */
    static HostRegistry validation(PeripheralRuntime runtime, Kind kind) {
        return build(runtime, new UUID(0, 0), kind, null);
    }

    /** Capabilities a device program holds: its kind's hardware capabilities and its drive storage. */
    static Set<String> capabilities(Kind kind) {
        var hardware = new HardwareHosts(null, new UUID(0, 0), new UUID(0, 0));
        var granted = new HashSet<String>(Set.of(STORAGE_READ, STORAGE_WRITE, "devices.read", EventHosts.EMIT,
                AdvancedApis.SERVER_READ, AdvancedApis.SERVER_WRITE));
        var spec = hardware.spec(kind);
        if (spec != null) {
            spec.methods().forEach(method -> granted.add(method.capability()));
        }
        if (kind == Kind.INVENTORY_MODULE) {
            granted.add(InventoryHosts.READ);
            granted.add(InventoryHosts.TRANSFER);
        }
        return Set.copyOf(granted);
    }

    private static HostRegistry build(PeripheralRuntime runtime, UUID id, Kind kind, PeripheralEntity device) {
        var hardware = new HardwareHosts(runtime, id, id);
        var functions = new ArrayList<Function>();
        var spec = hardware.spec(kind);
        if (spec != null) {
            for (HardwareHosts.Method method : spec.methods()) {
                var errors = new HashSet<>(HardwareHosts.ACCESS_ERRORS);
                errors.addAll(method.errors());
                functions.add(new Function("device." + method.name(), method.parameters(), result(method.result()),
                        method.documentation(), Set.of(method.capability()), method.cost(), (args, ctx) -> {
                    ctx.charge(64);
                    try {
                        return ok(method.operation().apply(hardware.self(kind), args, ctx));
                    } catch (HostFailure failure) {
                        return error(failure.code(), failure.getMessage());
                    }
                }).withUsage(errors, method.example().replaceFirst("= \\w+\\.", "= device.")));
            }
        }
        functions.add(new Function("device.info", List.of(), result(Type.DICT),
                "Describe this device and its drive: id, name, kind, drive, driveLabel.", Set.of("devices.read"), 8,
                (args, ctx) -> info(runtime, id, kind, device)).withUsage(Set.of(), "me, err = device.info()"));
        // The weather station's own time() already covers this.
        if (functions.stream().noneMatch(function -> function.name().equals("device.time"))) functions.add(new Function("device.time", List.of(), result(Type.DICT),
                "Read world time where the device is: dayTime (0–23999, 0 is sunrise), day, gameTime, isDay, isNight.",
                Set.of("devices.read"), 8, (args, ctx) -> {
            if (device == null || !(device.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
                return error("device.unavailable", "The device is unloaded");
            }
            return ok(WorldReadings.time(level));
        }).withUsage(Set.of("device.unavailable"), "now, err = device.time()"));
        functions.addAll(storage(device));
        functions.add(new Function("log.error", List.of(new Slot("message", Set.of(Type.STRING, Type.ERROR))), List.of(),
                "Log an error using the program output allowance.", Set.of(), 1, (args, ctx) -> {
            ctx.print(args.getFirst());
            return List.of();
        }));
        // Other devices: events only, plus shared storage on storage servers.
        functions.addAll(EventHosts.references(id));
        functions.addAll(EventHosts.functions(runtime, "device:" + id));
        var types = new ArrayList<ObjectType>();
        if (kind == Kind.INVENTORY_MODULE) {
            types.add(new InventoryHosts(runtime, id, id).type());
        }
        types.addAll(EventHosts.types(runtime, id, java.util.Map.of(Kind.STORAGE_SERVER, AdvancedApis.remoteStorage(runtime, id))));
        return HardwareEvents.registry(functions, types, HardwareEvents.localEvents(kind));
    }

    private static List<Value> info(PeripheralRuntime runtime, UUID id, Kind kind, PeripheralEntity device) {
        var node = device == null ? null : runtime.directory().node(id);
        var tag = device == null ? null : Drives.tag(device.drive());
        var values = new LinkedHashMap<String, Value>();
        values.put("id", new Text(id.toString()));
        values.put("name", node == null || node.alias() == null ? Nil.INSTANCE : new Text(node.alias()));
        values.put("kind", new Text(kind.id()));
        values.put("drive", tag == null ? Nil.INSTANCE : new Text(tag.id().toString()));
        values.put("driveLabel", tag == null ? Nil.INSTANCE : new Text(tag.label()));
        return ok(new DictValue(values));
    }

    private static List<Function> storage(PeripheralEntity device) {
        return List.of(
                storage("read", List.of(KEY), null, "Read a copy of a stored value. A missing key returns storage.missing.",
                        STORAGE_READ, "last, err = storage.read(\"last_detection\")", device, (record, args, ctx) -> {
                    String key = ((Text) args.getFirst()).value();
                    Value value = record.storage().read(key);
                    if (value == null) {
                        throw new NamedStorage.Failure("storage.missing", "Nothing is stored under " + key);
                    }
                    ctx.charge(record.storage().sizeOf(key) / 16L);
                    return value;
                }),
                storage("write", List.of(KEY, VALUE), Type.BOOL, "Store a copy of a value under a key, replacing any old value.",
                        STORAGE_WRITE, "ok, err = storage.write(\"last_detection\", entry)", device, (record, args, ctx) -> {
                    byte[] encoded = NamedStorage.encode(args.get(1));
                    ctx.charge(encoded.length / 16L);
                    record.storage().write(((Text) args.getFirst()).value(), args.get(1));
                    return new Bool(true);
                }),
                storage("append", List.of(KEY, VALUE), Type.BOOL, "Append a value to the list under a key, creating it when absent.",
                        STORAGE_WRITE, "ok, err = storage.append(\"history\", entry)", device, (record, args, ctx) -> {
                    String key = ((Text) args.getFirst()).value();
                    ctx.charge(Math.max(0, record.storage().sizeOf(key)) / 16L + NamedStorage.encode(args.get(1)).length / 16L);
                    record.storage().append(key, args.get(1));
                    return new Bool(true);
                }),
                storage("has", List.of(KEY), Type.BOOL, "Test whether a key is stored.", STORAGE_READ,
                        "found, err = storage.has(\"history\")", device,
                        (record, args, ctx) -> new Bool(record.storage().has(((Text) args.getFirst()).value()))),
                storage("delete", List.of(KEY), Type.BOOL, "Remove a key. Returns false when it was absent.", STORAGE_WRITE,
                        "removed, err = storage.delete(\"history\")", device,
                        (record, args, ctx) -> new Bool(record.storage().delete(((Text) args.getFirst()).value()))),
                storage("keys", List.of(), Type.LIST, "List stored keys in insertion order.", STORAGE_READ,
                        "keys, err = storage.keys()", device, (record, args, ctx) -> {
                    ctx.charge(4L * record.storage().size());
                    return new ListValue(record.storage().keys().stream().<Value>map(Text::new).toList());
                }),
                storage("usage", List.of(), Type.DICT, "Read used and capacity bytes and entries and maxEntries.", STORAGE_READ,
                        "usage, err = storage.usage()", device, (record, args, ctx) -> usage(record)),
                storage("clear", List.of(), Type.BOOL, "Remove every stored key.", STORAGE_WRITE, "ok, err = storage.clear()",
                        device, (record, args, ctx) -> {
                    record.storage().clear();
                    return new Bool(true);
                }));
    }

    static DictValue usage(DriveRecord record) {
        var values = new LinkedHashMap<String, Value>();
        values.put("used", new Num(record == null ? 0 : record.storage().used()));
        values.put("capacity", new Num(NamedStorage.CAPACITY));
        values.put("entries", new Num(record == null ? 0 : record.storage().size()));
        values.put("maxEntries", new Num(NamedStorage.MAX_ENTRIES));
        return new DictValue(values);
    }

    @FunctionalInterface
    private interface StorageOperation {
        Value apply(DriveRecord record, List<Value> args, Context context);
    }

    private static Function storage(String name, List<Slot> parameters, Type resultType, String documentation, String capability,
                                    String example, PeripheralEntity device, StorageOperation operation) {
        // A read can return any storable value, so its result slot accepts every value type.
        List<Slot> returns = resultType == null
                ? List.of(new Slot("value", EnumSet.allOf(Type.class)), new Slot("error", Set.of(Type.ERROR, Type.NIL)))
                : result(resultType);
        return new Function("storage." + name, parameters, returns,
                documentation, Set.of(capability), 16, (args, ctx) -> {
            if (device == null) {
                return error("drive.missing", "Storage is only available on a device");
            }
            try {
                DriveRecord record = device.driveRecord(true);
                Value value = operation.apply(record, args, ctx);
                if (capability.equals(STORAGE_WRITE)) {
                    record.touch(device.getLevel().getGameTime());
                }
                return ok(value);
            } catch (HostFailure failure) {
                return error(failure.code(), failure.getMessage());
            } catch (NamedStorage.Failure failure) {
                return error(failure.code(), failure.getMessage());
            } catch (IllegalArgumentException limit) {
                return error("storage.full", limit.getMessage());
            }
        }).withUsage(STORAGE_ERRORS, example);
    }
}
