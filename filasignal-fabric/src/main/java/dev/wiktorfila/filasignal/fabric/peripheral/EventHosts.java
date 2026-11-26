package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.wiktorfila.filasignal.fabric.peripheral.HostContracts.*;

/**
 * Advanced messaging and the device references available to programs running on a device.
 *
 * <p>{@code events.emit(name, value)} and {@code events.send(device, name, value)} deliver a custom event to other
 * programs on the same network that handle {@code on events.custom(name, value, sender)}. Delivery is best effort and
 * happens on a later tick. The sender details are filled in by the server.
 */
final class EventHosts {
    static final String EMIT = "events.emit";
    private static final Set<String> SEND_ERRORS = Set.of("event.invalid", "event.limit", "device.unknown", "device.unavailable");

    private EventHosts() {}

    static List<Function> functions(PeripheralRuntime runtime, String executionId) {
        var value = new Slot("value", EnumSet.allOf(Type.class));
        var name = new Slot("event", Set.of(Type.STRING));
        return List.of(
                new Function("events.emit", List.of(name, value), result(Type.NUMBER),
                        "Advanced: send a custom event to every program on this network that handles "
                                + "on events.custom(\"name\", value). Returns how many programs received it.",
                        Set.of(EMIT), 32, (args, ctx) -> send(runtime, executionId, null, args.get(0), args.get(1), ctx))
                        .withUsage(SEND_ERRORS, "events.emit(\"gate.open\", true)"),
                new Function("events.send", List.of(ALIAS, name, value), result(Type.NUMBER),
                        "Advanced: send a custom event to the program running on one named device.",
                        Set.of(EMIT), 48, (args, ctx) -> send(runtime, executionId, ((Text) args.getFirst()).value(),
                        args.get(1), args.get(2), ctx))
                        .withUsage(SEND_ERRORS, "events.send(\"siren\", \"alarm.test\", {seconds: 5})"));
    }

    private static List<Value> send(PeripheralRuntime runtime, String executionId, String target, Value name, Value value,
                                    Context context) {
        context.charge(64);
        try {
            return ok(new Num(runtime.events().send(executionId, target, ((Text) name).value(), value)));
        } catch (HostFailure failure) {
            return error(failure.code(), failure.getMessage());
        }
    }

    /**
     * References a device program can hold: every named-reference factory, with objects that expose only the
     * device's name, whether it is connected, and its events. Receiving events never grants control of a device.
     */
    static List<Function> references(UUID self) {
        var functions = new ArrayList<Function>();
        for (Kind kind : HardwareHosts.NAMING.keySet()) {
            functions.add(HardwareHosts.reference(kind, self));
        }
        return functions;
    }

    /** Events-only object types for {@link #references}. Storage servers add their storage methods separately. */
    static List<ObjectType> types(PeripheralRuntime runtime, UUID self, Map<Kind, Map<String, Function>> methods) {
        var types = new ArrayList<ObjectType>();
        for (var entry : HardwareHosts.NAMING.entrySet()) {
            String type = entry.getValue().type();
            var properties = new LinkedHashMap<String, Function>();
            var receiver = handle(HardwareHosts.receiver(type), type);
            properties.put("name", new Function(type + ".name", List.of(receiver), List.of(new Slot("name", Set.of(Type.STRING, Type.NIL))),
                    "The device name this reference uses.", Set.of(), 2, (args, ctx) -> {
                String reference = ((HostHandle) args.getFirst()).reference();
                return List.of(reference == null ? Nil.INSTANCE : new Text(reference));
            }));
            properties.put("connected", new Function(type + ".connected", List.of(receiver), List.of(new Slot("connected", Set.of(Type.BOOL))),
                    "True while the device can be reached from this device.", Set.of(), 8, (args, ctx) -> {
                ctx.charge(128);
                var handle = (HostHandle) args.getFirst();
                if (runtime == null || handle.reference() == null || !self.equals(handle.scope())) {
                    return List.of(new Bool(false));
                }
                try {
                    runtime.events().resolve(self, false, handle.reference(), entry.getKey());
                    return List.of(new Bool(true));
                } catch (HostFailure unavailable) {
                    return List.of(new Bool(false));
                }
            }));
            types.add(new ObjectType(type, properties, methods.getOrDefault(entry.getKey(), Map.of()),
                    HardwareEvents.objectEvents(entry.getKey())));
        }
        return types;
    }
}
