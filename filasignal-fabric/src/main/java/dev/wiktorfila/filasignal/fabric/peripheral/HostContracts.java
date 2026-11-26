package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.Function;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Handler;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Slot;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Type;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.ErrorValue;
import dev.wiktorfila.filasignal.core.runtime.value.Value.Nil;

import java.util.List;
import java.util.Set;

final class HostContracts {
    static final Slot ALIAS = new Slot("alias", Set.of(Type.STRING));
    private static final Slot ERROR = new Slot("error", Set.of(Type.ERROR, Type.NIL));

    private HostContracts() {}

    static Slot handle(String name, String type) {
        return new Slot(name, Set.of(Type.HANDLE), type);
    }

    /** A {@code (value, err)} result. A null type accepts any value, as for reading stored data. */
    static List<Slot> result(Type type) {
        return List.of(new Slot("value", type == null ? java.util.EnumSet.allOf(Type.class) : Set.of(type, Type.NIL)), ERROR);
    }

    static Function factory(String name, String type, String capability, String documentation, Handler handler) {
        return new Function(name, List.of(ALIAS), List.of(new Slot("handle", Set.of(Type.HANDLE, Type.NIL), type), ERROR),
                documentation, Set.of(capability), 16, handler);
    }

    static List<Value> ok(Value value) { return List.of(value, Nil.INSTANCE); }

    static List<Value> error(String code, String message) {
        return List.of(Nil.INSTANCE, new ErrorValue(code, message));
    }
}
