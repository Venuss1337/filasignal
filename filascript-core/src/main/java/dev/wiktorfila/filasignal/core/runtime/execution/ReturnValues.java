package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.runtime.value.Value;

import java.util.List;

/** A return sequence belongs to the call boundary and is never a script value. */
public record ReturnValues(List<Value> values) {
    public static final ReturnValues EMPTY = new ReturnValues(List.of());

    public ReturnValues { values = List.copyOf(values); }
}
