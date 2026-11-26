package dev.wiktorfila.filasignal.core.host;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Only these explicit Java callbacks are visible to a script. */
public final class HostRegistry {
    public static final HostRegistry EMPTY = new HostRegistry(List.of());

    private final Map<String, Function> entries;
    private final Map<String, ObjectType> objects;
    private final Map<String, List<String>> enums;
    private final Map<String, Event> events;

    public HostRegistry(Collection<Function> functions) {
        this(functions, List.of());
    }

    /** Registers callable factories/functions and the separate allowlist of instance members. */
    public HostRegistry(Collection<Function> functions, Collection<ObjectType> objectTypes) {
        this(functions, objectTypes, Map.of(), List.of());
    }

    /**
     * Also registers named constant sets, such as {@code Direction.NORTH}, and the events a program may handle with
     * {@code on}. A registry that declares any events rejects handlers for undeclared events.
     */
    public HostRegistry(Collection<Function> functions, Collection<ObjectType> objectTypes,
                        Map<String, List<String>> enums, Collection<Event> events) {
        var registry = new HashMap<String, Function>();
        for (Function function : functions) {
            if (registry.putIfAbsent(function.name(), function) != null) {
                throw new IllegalArgumentException("Duplicate host function " + function.name());
            }
        }
        entries = Map.copyOf(registry);
        var types = new HashMap<String, ObjectType>();
        for (ObjectType type : objectTypes) {
            if (types.putIfAbsent(type.name(), type) != null) {
                throw new IllegalArgumentException("Duplicate host object type " + type.name());
            }
        }
        objects = Map.copyOf(types);
        var constants = new HashMap<String, List<String>>();
        enums.forEach((name, members) -> {
            if (!name.matches("[A-Z][A-Za-z0-9_]{0,31}") || members.isEmpty() || members.size() > 64
                    || members.stream().anyMatch(member -> !member.matches("[A-Z][A-Z0-9_]{0,31}"))
                    || members.stream().distinct().count() != members.size()) {
                throw new IllegalArgumentException("Invalid host enum " + name);
            }
            constants.put(name, List.copyOf(members));
        });
        this.enums = Map.copyOf(constants);
        var declared = new HashMap<String, Event>();
        for (Event event : events) {
            if (declared.putIfAbsent(event.name(), event) != null) {
                throw new IllegalArgumentException("Duplicate host event " + event.name());
            }
        }
        this.events = Map.copyOf(declared);
    }

    /** Named constant sets, such as {@code Direction}, keyed by name with members in declaration order. */
    public Map<String, List<String>> enums() { return enums; }

    /** A program-level event such as {@code timer.every}, or null. */
    public Event event(String qualifiedName) { return events.get(qualifiedName); }

    public List<Event> events() {
        return events.values().stream().sorted(java.util.Comparator.comparing(Event::name)).toList();
    }

    /** True when handlers must name a declared event. */
    public boolean strictEvents() { return !events.isEmpty(); }

    /**
     * An event a program can handle. Filters are activation-time constants written first in the handler's
     * parentheses, such as the period of {@code timer.every(20)}. The remaining names in the parentheses are handler
     * parameters bound to the event's values in order; a handler may declare fewer.
     */
    public record Event(String name, List<Slot> filters, List<String> parameters, String documentation) {
        public Event {
            if (!name.matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*") || name.length() > 128) {
                throw new IllegalArgumentException("Invalid host event name");
            }
            filters = List.copyOf(filters);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(documentation);
            if (filters.size() + parameters.size() > 16
                    || parameters.stream().anyMatch(parameter -> !parameter.matches("[a-z][A-Za-z0-9_]{0,31}"))) {
                throw new IllegalArgumentException("Invalid host event signature");
            }
        }
    }

    public Function lookup(String qualifiedName) {
        return entries.get(qualifiedName);
    }

    public List<Function> functions() {
        return entries.values().stream().sorted(java.util.Comparator.comparing(Function::name)).toList();
    }

    public ObjectType object(String name) { return objects.get(name); }

    public List<ObjectType> objects() {
        return objects.values().stream().sorted(java.util.Comparator.comparing(ObjectType::name)).toList();
    }

    /** Explicit per-type dispatch table. Member functions take the handle as their first slot. */
    public record ObjectType(String name, Map<String, Function> properties, Map<String, Function> methods,
                             Map<String, Event> events) {
        public ObjectType(String name, Map<String, Function> properties, Map<String, Function> methods) {
            this(name, properties, methods, Map.of());
        }

        /**
         * Events are handled through a file constant that holds a device reference, as in
         * {@code on gate.entityEnter(entity)}. Their names are unqualified and they take no filters.
         */
        public ObjectType {
            Objects.requireNonNull(name);
            properties = Map.copyOf(properties);
            methods = Map.copyOf(methods);
            events = Map.copyOf(events);
            for (var entry : events.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().name()) || entry.getKey().contains(".")
                        || !entry.getValue().filters().isEmpty()) {
                    throw new IllegalArgumentException("Host object events need unqualified names and no filters");
                }
            }
            for (var entry : properties.entrySet()) {
                Function property = entry.getValue();
                if (methods.containsKey(entry.getKey()) || property.parameters().size() != 1
                        || property.returns().size() != 1) {
                    throw new IllegalArgumentException("Host properties require one receiver and one result");
                }
                requireReceiver(name, property);
            }
            for (Function method : methods.values()) {
                requireReceiver(name, method);
            }
        }

        private static void requireReceiver(String name, Function function) {
            if (function.parameters().isEmpty() || !name.equals(function.parameters().getFirst().handleType())) {
                throw new IllegalArgumentException("Host member requires a typed receiver slot");
            }
        }
    }

    public enum Type {
        NUMBER, STRING, BOOL, NIL, ERROR, LIST, TUPLE, DICT, INSTANCE, ENUM, HANDLE;

        public static Type of(Value value) {
            return switch (value) {
                case Num ignored -> NUMBER;
                case Text ignored -> STRING;
                case Bool ignored -> BOOL;
                case Nil ignored -> NIL;
                case ErrorValue ignored -> ERROR;
                case ListValue ignored -> LIST;
                case TupleValue ignored -> TUPLE;
                case DictValue ignored -> DICT;
                case Instance ignored -> INSTANCE;
                case HostHandle ignored -> HANDLE;
                case EnumMember ignored -> ENUM;
                default -> null;
            };
        }
    }

    public record Slot(String name, Set<Type> types, String handleType) {
        public Slot(String name, Set<Type> types) { this(name, types, null); }
        public Slot {
            Objects.requireNonNull(name);
            types = Set.copyOf(types);
            if (types.isEmpty() || handleType != null && !types.contains(Type.HANDLE)) {
                throw new IllegalArgumentException("A slot requires accepted types and may constrain a host handle type");
            }
        }

        public boolean accepts(Value value) {
            Type type = Type.of(value);
            return type != null && types.contains(type)
                    && (!(value instanceof HostHandle handle) || handleType == null || handleType.equals(handle.type()));
        }
    }

    /**
     * A host callback. A {@code reference} function only builds a value from its arguments without reading or
     * changing the world, such as {@code detectors.named("gate")}. File constants may call it.
     */
    public record Function(String name, List<Slot> parameters, List<Slot> returns, String documentation,
                           Set<String> capabilities, long cost, Handler handler, Set<String> errors, List<String> examples,
                           boolean reference) {
        public Function(String name, List<Slot> parameters, List<Slot> returns, String documentation,
                        Set<String> capabilities, long cost, Handler handler) {
            this(name, parameters, returns, documentation, capabilities, cost, handler, Set.of(), List.of(), false);
        }

        public Function(String name, List<Slot> parameters, List<Slot> returns, String documentation,
                        Set<String> capabilities, long cost, Handler handler, Set<String> errors, List<String> examples) {
            this(name, parameters, returns, documentation, capabilities, cost, handler, errors, examples, false);
        }

        public Function {
            if (!name.matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*") || cost < 1) {
                throw new IllegalArgumentException("Invalid host function name or cost");
            }
            parameters = List.copyOf(parameters);
            returns = List.copyOf(returns);
            Objects.requireNonNull(documentation);
            capabilities = Set.copyOf(capabilities);
            Objects.requireNonNull(handler);
            errors = Set.copyOf(errors);
            examples = List.copyOf(examples);
            if (reference && (returns.size() != 1 || returns.getFirst().types().contains(Type.ERROR))) {
                throw new IllegalArgumentException("A reference function returns exactly one value and cannot fail");
            }
        }

        /** Marks a function that only builds a reference, so file constants may call it. */
        public Function asReference() {
            return new Function(name, parameters, returns, documentation, capabilities, cost, handler, errors, examples, true);
        }

        /** Adds reference material consumed by tooling alongside the executable signature. */
        public Function withUsage(Set<String> expectedErrors, String example) {
            return new Function(name, parameters, returns, documentation, capabilities, cost, handler, expectedErrors,
                    List.of(example), reference);
        }

        public boolean hasErrorResult() {
            return !returns.isEmpty() && returns.getLast().types().containsAll(Set.of(Type.NIL, Type.ERROR))
                    && returns.subList(0, returns.size() - 1).stream().allMatch(slot -> slot.types().contains(Type.NIL));
        }

        /** True for the common {@code (value, err)} shape, which may also be used where one value is expected. */
        public boolean valueOrError() {
            return returns.size() == 2 && hasErrorResult();
        }
    }

    @FunctionalInterface
    public interface Handler {
        List<Value> invoke(List<Value> arguments, Context context);
    }

    public interface Context {
        void charge(long operations);
        void log(String line);
        void print(Value value);
    }
}
