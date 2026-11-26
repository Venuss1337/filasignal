package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.Symbol;
import dev.wiktorfila.filasignal.core.runtime.value.Value;

import java.util.IdentityHashMap;
import java.util.Map;

/** One activation of a lexical scope. Nil is stored explicitly. */
final class Environment {
    private final Environment parent;
    private final Value.Instance self;
    private final Map<Symbol, Value> values = new IdentityHashMap<>();

    Environment(Environment parent) {
        this(parent, parent == null ? null : parent.self);
    }

    Environment(Environment parent, Value.Instance self) {
        this.parent = parent;
        this.self = self;
    }

    Value.Instance self() {
        return self;
    }

    void define(Symbol symbol, Value value) {
        values.put(symbol, value);
    }

    void forEachValue(java.util.function.Consumer<Value> consumer) {
        values.values().forEach(consumer);
    }

    Environment parent() {
        return parent;
    }

    /** This scope's own bindings in declaration order. Used only for bounded debugger snapshots. */
    java.util.List<Map.Entry<Symbol, Value>> entries() {
        var ordered = new java.util.ArrayList<>(values.entrySet());
        ordered.sort(java.util.Comparator.comparingInt(entry -> entry.getKey().id()));
        return ordered;
    }

    Value read(Symbol symbol) {
        Environment owner = ownerOf(symbol);
        return owner == null ? null : owner.values.get(symbol);
    }

    boolean update(Symbol symbol, Value value) {
        Environment owner = ownerOf(symbol);
        if (owner == null || !symbol.mutable()) {
            return false;
        }
        owner.values.put(symbol, value);
        return true;
    }

    private Environment ownerOf(Symbol symbol) {
        for (Environment scope = this; scope != null; scope = scope.parent) {
            if (scope.values.containsKey(symbol)) {
                return scope;
            }
        }
        return null;
    }
}
