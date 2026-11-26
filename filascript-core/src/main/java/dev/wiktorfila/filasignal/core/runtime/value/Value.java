package dev.wiktorfila.filasignal.core.runtime.value;

import dev.wiktorfila.filasignal.core.ast.Ast.Function;
import dev.wiktorfila.filasignal.core.ast.Ast.ClassDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Script values have no implicit conversions to or from Java objects. */
public sealed interface Value {
    record Num(double value) implements Value {
        public Num {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("FilaScript numbers must be finite");
            }
        }
    }

    record Text(String value) implements Value {
        public Text { Objects.requireNonNull(value); }
    }

    record Bool(boolean value) implements Value {
    }

    enum Nil implements Value { INSTANCE }

    record ErrorValue(String code, String message) implements Value {
        public ErrorValue {
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
        }
    }

    int MAX_COLLECTION_SIZE = 4096;

    final class ListValue implements Value {
        private final List<Value> elements;

        public ListValue(List<Value> elements) {
            checkSize(elements.size());
            this.elements = new ArrayList<>(List.copyOf(elements));
        }

        public int size() { return elements.size(); }
        public Value get(int index) { return elements.get(index); }
        public void set(int index, Value value) { elements.set(index, Objects.requireNonNull(value)); }
        public void insert(int index, Value value) {
            checkSize(size() + 1);
            elements.add(index, Objects.requireNonNull(value));
        }
        public Value remove(int index) { return elements.remove(index); }
        public void clear() { elements.clear(); }
        public List<Value> snapshot() { return List.copyOf(elements); }
    }

    record TupleValue(List<Value> elements) implements Value {
        public TupleValue {
            checkSize(elements.size());
            elements = List.copyOf(elements);
        }
    }

    final class DictValue implements Value {
        private final Map<String, Value> entries = new LinkedHashMap<>();
        private final boolean writable;

        public DictValue() { writable = true; }

        /** Detached read-only snapshot. Callers provide only immutable field values. */
        public DictValue(Map<String, Value> snapshot) {
            checkSize(snapshot.size());
            entries.putAll(new java.util.TreeMap<>(Map.copyOf(snapshot)));
            writable = false;
        }

        public boolean writable() { return writable; }

        private void requireWritable() {
            if (!writable) {
                throw new IllegalStateException("Snapshot is read-only");
            }
        }

        public int size() { return entries.size(); }
        public Value get(String key) { return entries.get(key); }
        public void put(String key, Value value) {
            requireWritable();
            if (!entries.containsKey(key)) {
                checkSize(size() + 1);
            }
            entries.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
        }
        public Value remove(String key) { requireWritable(); return entries.remove(key); }
        public void clear() { requireWritable(); entries.clear(); }
        public List<String> keys() { return List.copyOf(entries.keySet()); }
        public List<Value> values() { return List.copyOf(entries.values()); }
    }

    private static void checkSize(int size) {
        if (size > MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException("Collection exceeds the size limit");
        }
    }

    /**
     * A transient host-issued identity. Contains no callback, world state or live Java resource. A reference handle
     * also carries the name it was created from, such as a device name that the host resolves on every use.
     */
    record HostHandle(String type, UUID scope, UUID resource, String reference) implements Value {
        public HostHandle(String type, UUID scope, UUID resource) {
            this(type, scope, resource, null);
        }

        public HostHandle {
            if (type == null || !type.matches("[A-Za-z_][A-Za-z_0-9]{0,31}")) {
                throw new IllegalArgumentException("Invalid host object type name");
            }
            Objects.requireNonNull(scope);
            Objects.requireNonNull(resource);
            if (reference != null && (reference.isEmpty() || reference.length() > 64)) {
                throw new IllegalArgumentException("Invalid host reference name");
            }
        }
    }

    enum Namespace implements Value { JSON }

    sealed interface Callable extends Value {
    }

    record UserFunction(Function declaration) implements Callable {
    }

    enum Builtin implements Callable { PRINT, JSON_PARSE, JSON_STRINGIFY, STR }

    record ClassType(ClassDeclaration declaration) implements Callable {
    }

    /** A declared or host-provided enumeration, such as {@code Direction}. */
    record EnumType(String name, Map<String, EnumMember> members) implements Value {
        public EnumType {
            Objects.requireNonNull(name);
            members = Map.copyOf(members);
        }
    }

    record EnumMember(String enumeration, String name) implements Value {
    }

    final class Instance implements Value {
        private final ClassType type;
        private final Map<String, Value> fields = new LinkedHashMap<>();

        public Instance(ClassType type) { this.type = Objects.requireNonNull(type); }
        public ClassType type() { return type; }
        public Value get(String name) { return fields.get(name); }
        public void put(String name, Value value) { fields.put(name, Objects.requireNonNull(value)); }
        public List<String> keys() { return List.copyOf(fields.keySet()); }
    }

    record BoundMethod(Instance receiver, Function declaration) implements Callable {
    }

    record CollectionMethod(Value receiver, String name) implements Callable {
    }
}
