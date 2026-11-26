package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.function.LongConsumer;

/** Measures a reachable graph in logical units, without traversing cycles twice. */
final class RetainedData {
    private final ArrayDeque<Value> pending = new ArrayDeque<>();
    private final IdentityHashMap<Value, Boolean> visited = new IdentityHashMap<>();
    private final LongConsumer reserve;

    RetainedData(LongConsumer reserve) {
        this.reserve = reserve;
    }

    void root(Value value) {
        reserve.accept(1);
        visit(value);
    }

    private void visit(Value value) {
        if (!(value instanceof Callable || value instanceof EnumType || value instanceof Namespace)
                && visited.put(value, true) == null) {
            pending.push(value);
        }
    }

    void measure() {
        while (!pending.isEmpty()) {
            Value value = pending.pop();
            reserve.accept(1);
            switch (value) {
                case HostHandle handle -> reserve.accept(handle.type().length() + 8L);
                case Text text -> reserve.accept(text.value().length());
                case ErrorValue error -> reserve.accept((long) error.code().length() + error.message().length());
                case ListValue list -> {
                    reserve.accept(list.size());
                    for (int i = 0; i < list.size(); i++) {
                        visit(list.get(i));
                    }
                }
                case TupleValue tuple -> {
                    reserve.accept(tuple.elements().size());
                    tuple.elements().forEach(this::visit);
                }
                case DictValue dictionary -> {
                    reserve.accept(dictionary.size());
                    for (String key : dictionary.keys()) {
                        reserve.accept(key.length());
                        visit(dictionary.get(key));
                    }
                }
                case Instance instance -> {
                    for (String field : instance.keys()) {
                        reserve.accept((long) field.length() + 1);
                        visit(instance.get(field));
                    }
                }
                default -> { }
            }
        }
    }
}
