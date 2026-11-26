package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.EventSite;
import dev.wiktorfila.filasignal.core.analysis.ResolvedProgram;
import dev.wiktorfila.filasignal.core.analysis.Symbol;
import dev.wiktorfila.filasignal.core.ast.Ast.*;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateException;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateStore;
import java.io.IOException;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.runtime.value.ValueSummary;
import dev.wiktorfila.filasignal.core.source.SourceSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** A single controller's in-memory runtime. Invoke serially on the owning thread. */
public final class Interpreter {
    private static final int MAX_EXECUTION_NESTING = 256;
    private static final int MAX_CONCATENATED_LENGTH = 1_048_576;
    private static final int VARIABLE_SUMMARY = 120;

    private static final java.util.Set<String> LIST_METHODS = java.util.Set.of("append", "insert", "remove", "clear");
    private static final java.util.Set<String> DICTIONARY_METHODS = java.util.Set.of("remove", "clear", "keys", "values", "contains");

    private final ResolvedProgram program;
    private final ExecutionLimits limits;
    private final Consumer<String> output;
    private final java.util.Set<String> capabilities;
    private final List<EventDeclaration> events;
    private Environment globals;
    private boolean invoking;
    private byte[] pendingSnapshot;
    private StateStore.Migration migration;
    private Instrumentation instrumentation;

    public Interpreter(ResolvedProgram program, ExecutionLimits limits, Consumer<String> output) {
        this(program, limits, output, java.util.Set.of());
    }

    public Interpreter(ResolvedProgram program, ExecutionLimits limits, Consumer<String> output,
                       java.util.Set<String> capabilities) {
        this.program = Objects.requireNonNull(program);
        this.limits = Objects.requireNonNull(limits);
        this.output = Objects.requireNonNull(output);
        this.capabilities = java.util.Set.copyOf(capabilities);
        this.events = program.syntax().declarations().stream().filter(EventDeclaration.class::isInstance)
                .map(EventDeclaration.class::cast).toList();
    }

    /** Supply saved state before the first successful activation. The caller retains its original bytes. */
    public void restoreGlobals(byte[] snapshot, StateStore.Migration migration) throws StateException {
        if (globals != null || invoking) {
            throw new IllegalStateException("Restoration is only available before activation");
        }
        if (snapshot.length > GlobalSnapshot.MAX_BYTES) {
            throw new StateException(StateException.Reason.LIMIT, "Snapshot exceeds the storage limit");
        }
        pendingSnapshot = snapshot.clone();
        this.migration = migration;
    }

    /** Creates a complete snapshot before any storage adapter is called. */
    public byte[] snapshotGlobals() throws IOException {
        if (globals == null || invoking) {
            throw new IllegalStateException("Snapshot requires an activated and idle runtime");
        }
        var saved = new java.util.LinkedHashMap<String, GlobalSnapshot.Global>();
        for (Declaration declaration : program.syntax().declarations()) {
            if (declaration instanceof BindingDeclaration binding && binding.kind() == BindingKind.GLOBAL) {
                Symbol symbol = program.bindingOf(binding.binding().name());
                saved.put(symbol.name(), new GlobalSnapshot.Global(symbol.type() == null ? "" : symbol.type(),
                        globals.read(symbol)));
            }
        }
        return GlobalSnapshot.encode(saved);
    }

    /**
     * Installs or removes debugger hooks for later invocations. Uninstrumented execution performs no hook calls.
     *
     * @throws IllegalStateException while an invocation is in progress or suspended
     */
    public void instrument(Instrumentation hooks) {
        if (invoking) {
            throw new IllegalStateException("Instrumentation changes require an idle runtime");
        }
        instrumentation = hooks;
    }

    /** True while an invocation is running or suspended by a debugger. */
    public boolean busy() {
        return invoking;
    }

    public ExecutionResult invoke(String name, List<Value> arguments) {
        return invoke(name, arguments, limits.operations(), () -> false);
    }

    public ExecutionResult invoke(String name, List<Value> arguments, long workLimit, BooleanSupplier cancelled) {
        return execute(workLimit, cancelled, execution -> execution.run(name, List.copyOf(arguments)));
    }

    /**
     * One activated handler. A source event, such as {@code on gate.entityEnter(entity)}, carries the device reference
     * held by its constant in {@code source}; the host delivers it by index. Other events match by name and filters.
     */
    public record Subscription(int index, String name, List<Value> filters, HostHandle source, List<String> parameters) {
        public Subscription {
            filters = List.copyOf(filters);
            parameters = List.copyOf(parameters);
        }

        public Subscription(int index, String name, List<Value> filters) {
            this(index, name, filters, null, List.of());
        }
    }

    public record Preparation(List<Subscription> subscriptions, Optional<RuntimeIssue> issue) {
        public Preparation { subscriptions = List.copyOf(subscriptions); }
    }

    public Preparation prepareEvents() {
        var subscriptions = new ArrayList<Subscription>();
        ExecutionResult result = execute(limits.operations(), () -> false, execution -> {
            execution.prepare(subscriptions);
            return ReturnValues.EMPTY;
        });
        return new Preparation(result.issue().isPresent() ? List.of() : subscriptions, result.issue());
    }

    public ExecutionResult invokeEvent(int index, List<Value> arguments, Value payload, long budget,
                                       BooleanSupplier cancelled) {
        return invokeEvent(index, arguments, List.of(payload), budget, cancelled);
    }

    /**
     * Runs a handler. Filter arguments are scalars. Values bind to the handler's parameters in order and are copied
     * on delivery, so no two handlers share a mutable collection.
     */
    public ExecutionResult invokeEvent(int index, List<Value> arguments, List<Value> values, long budget,
                                       BooleanSupplier cancelled) {
        return execute(budget, cancelled, execution -> execution.event(index, List.copyOf(arguments), List.copyOf(values)));
    }

    private ExecutionResult execute(long workLimit, BooleanSupplier cancelled,
                                    java.util.function.Function<Execution, ReturnValues> action) {
        if (workLimit < 1) {
            throw new IllegalArgumentException("Work limit must be positive");
        }
        Execution execution = new Execution(Math.min(limits.operations(), workLimit), cancelled);
        try {
            if (invoking) {
                throw execution.fault(Code.HOST_FAILURE, "Synchronous re-entry is unavailable", program.syntax().span());
            }
            invoking = true;
            try {
                ReturnValues result = action.apply(execution);
                return new ExecutionResult(result, Optional.empty(), execution.workUsed());
            } finally {
                invoking = false;
            }
        } catch (Fault fault) {
            return new ExecutionResult(ReturnValues.EMPTY, Optional.of(fault.issue), execution.workUsed());
        }
    }

    private final class Execution {
        private final ArrayDeque<RuntimeIssue.Frame> stack = new ArrayDeque<>();
        private final Instrumentation hooks = instrumentation;
        private final long workLimit;
        private final BooleanSupplier cancelled;
        private long remaining;
        private long data;
        private int logLines;
        private long logUnits;
        private final java.util.Set<SourceSpan> warned = new java.util.HashSet<>();

        private Execution(long workLimit, BooleanSupplier cancelled) {
            this.workLimit = workLimit;
            this.remaining = workLimit;
            this.cancelled = Objects.requireNonNull(cancelled);
        }

        private long workUsed() {
            return workLimit - remaining;
        }
        private int nesting;

        private ReturnValues run(String name, List<Value> arguments) {
            Symbol entry = program.topLevel(name);
            if (entry == null || entry.kind() != Symbol.Kind.FUNCTION) {
                SourceSpan location = entry == null ? program.syntax().span() : entry.declaration().span();
                throw fault(Code.UNKNOWN_ENTRY_POINT, "Expected a top-level function named " + name, location);
            }
            SourceSpan location = entry.declaration().span();
            for (Value value : arguments) {
                if (value instanceof Callable) {
                    throw fault(Code.TYPE_MISMATCH, "Callable values cannot be passed as arguments", location);
                }
            }
            account(arguments, location);
            activate();
            return invokeCallable((Callable) globals.read(entry), arguments, location);
        }

        private void account(List<Value> arguments, SourceSpan location) {
            charge(1, location);
            var retained = new RetainedData(units -> {
                reserve(units, location);
                charge(units, location);
            });
            if (globals != null) {
                globals.forEachValue(retained::root);
            }
            arguments.forEach(retained::root);
            retained.measure();
        }

        private void prepare(List<Subscription> subscriptions) {
            account(List.of(), program.syntax().span());
            activate();
            if (events.size() > 256) {
                throw fault(Code.COLLECTION_LIMIT, "Controller exceeds 256 subscriptions", program.syntax().span());
            }
            for (int i = 0; i < events.size(); i++) {
                EventDeclaration event = events.get(i);
                charge(1, event.span());
                String name = eventName(event);
                if (name.length() > 128 || event.arguments().size() > 16) {
                    throw fault(Code.DATA_LIMIT, "Event name or filter count exceeds limits", event.span());
                }
                EventSite site = program.event(event);
                List<Value> filters = evaluateAll(event.arguments().subList(0, site.filters()), globals);
                for (int f = 0; f < filters.size(); f++) {
                    Value filter = filters.get(f);
                    if (!scalarEventValue(filter)) {
                        throw fault(Code.TYPE_MISMATCH, "Event filters must be immutable scalar values", event.span());
                    }
                    if (site.definition() != null && !site.definition().filters().get(f).accepts(filter)) {
                        throw fault(Code.TYPE_MISMATCH, "Invalid " + site.definition().filters().get(f).name()
                                + " filter for " + name, event.span());
                    }
                }
                HostHandle source = null;
                if (site.source() != null) {
                    if (!(globals.read(site.source()) instanceof HostHandle handle)) {
                        throw fault(Code.TYPE_MISMATCH, "Event source is not a device reference", event.span());
                    }
                    source = handle;
                    name = site.name();
                }
                if (name.equals("timer.every")) {
                    double period = number(filters.getFirst(), event.span());
                    if (period < 1 || period > Integer.MAX_VALUE || period != Math.rint(period)) {
                        throw fault(Code.TYPE_MISMATCH, "Timer period must be a positive integer tick count", event.span());
                    }
                }
                subscriptions.add(new Subscription(i, name, filters, source,
                        site.parameters().stream().map(Symbol::name).toList()));
            }
        }

        private ReturnValues event(int index, List<Value> arguments, List<Value> delivered) {
            if (index < 0 || index >= events.size() || arguments.size() > 16 || delivered.size() > 16
                    || delivered.stream().anyMatch(value -> !eventValue(value))
                    || arguments.stream().anyMatch(value -> !scalarEventValue(value))) {
                throw fault(Code.TYPE_MISMATCH, "Invalid event delivery", program.syntax().span());
            }
            EventDeclaration event = events.get(index);
            var values = new ArrayList<Value>();
            for (Value value : delivered) {
                values.add(detach(value));
            }
            var data = new ArrayList<>(arguments);
            data.addAll(values);
            account(data, event.span());
            activate();
            reserve(arguments.size() + values.size() + 7L, event.span());
            DictValue context = new DictValue();
            context.put("name", new Text(eventName(event)));
            context.put("arguments", new TupleValue(arguments));
            context.put("payload", values.isEmpty() ? Value.Nil.INSTANCE : values.getFirst());
            context.put("values", new TupleValue(values));
            Environment frame = new Environment(globals);
            frame.define(program.eventBinding(event), context);
            List<Symbol> parameters = program.event(event).parameters();
            for (int i = 0; i < parameters.size(); i++) {
                frame.define(parameters.get(i), i < values.size() ? values.get(i) : Value.Nil.INSTANCE);
            }
            stack.push(new RuntimeIssue.Frame(eventName(event), event.span()));
            try {
                Flow flow = block(event.body(), frame);
                return flow instanceof Returned returned ? returned.values() : ReturnValues.EMPTY;
            } finally {
                stack.pop();
            }
        }

        private String eventName(EventDeclaration event) {
            return String.join(".", event.name().stream().map(Name::text).toList());
        }

        private void activate() {
            if (globals != null) {
                return;
            }
            Environment initial = new Environment(null);
            initial.define(program.print(), Builtin.PRINT);
            initial.define(program.str(), Builtin.STR);
            initial.define(program.json(), Namespace.JSON);
            for (var entry : program.hostEnums().entrySet()) {
                var members = new java.util.HashMap<String, EnumMember>();
                for (String member : program.hostEnumMembers().get(entry.getKey())) {
                    members.put(member, new EnumMember(entry.getKey(), member));
                }
                charge(members.size(), program.syntax().span());
                initial.define(entry.getValue(), new EnumType(entry.getKey(), members));
            }
            for (Declaration declaration : program.syntax().declarations()) {
                charge(1, declaration.span());
                reserve(1, declaration.span());
                switch (declaration) {
                    case Function function -> initial.define(program.bindingOf(function.name()), new UserFunction(function));
                    case ClassDeclaration clazz -> initial.define(program.bindingOf(clazz.name()), new ClassType(clazz));
                    case EnumDeclaration enumeration -> {
                        var members = new java.util.HashMap<String, EnumMember>();
                        charge(enumeration.members().size(), enumeration.span());
                        for (Name member : enumeration.members()) {
                            members.put(member.text(), new EnumMember(enumeration.name().text(), member.text()));
                        }
                        initial.define(program.bindingOf(enumeration.name()), new EnumType(enumeration.name().text(), members));
                    }
                    default -> { }
                }
            }
            var restored = restore(initial);
            for (Declaration declaration : program.syntax().declarations()) {
                if (declaration instanceof BindingDeclaration binding) {
                    Symbol symbol = program.bindingOf(binding.binding().name());
                    Value state = restored.get(symbol.name());
                    if (state == null) {
                        define(binding, initial);
                    } else {
                        initial.define(symbol, state);
                    }
                }
            }
            globals = initial;
            pendingSnapshot = null;
            migration = null;
        }

        private java.util.Map<String, Value> restore(Environment initial) {
            if (pendingSnapshot == null) {
                return java.util.Map.of();
            }
            var schema = new java.util.LinkedHashMap<String, String>();
            for (Declaration declaration : program.syntax().declarations()) {
                if (declaration instanceof BindingDeclaration binding && binding.kind() == BindingKind.GLOBAL) {
                    Symbol symbol = program.bindingOf(binding.binding().name());
                    schema.put(symbol.name(), symbol.type() == null ? "" : symbol.type());
                }
            }
            java.util.function.Function<String, Value> types = name -> {
                Symbol declared = program.topLevel(name);
                Symbol host = declared == null ? program.hostEnums().get(name) : declared;
                return host == null ? null : initial.read(host);
            };
            SourceSpan source = program.syntax().span();
            try {
                java.util.Map<String, Value> restored;
                try {
                    restored = GlobalSnapshot.decode(pendingSnapshot, schema, types);
                } catch (StateException error) {
                    if (migration == null || error.reason() != StateException.Reason.INCOMPATIBLE) {
                        throw error;
                    }
                    byte[] upgraded = migration.migrate(pendingSnapshot.clone(), error);
                    if (upgraded == null) {
                        throw error;
                    }
                    restored = GlobalSnapshot.decode(upgraded, schema, types);
                }
                var retained = new RetainedData(units -> {
                    reserve(units, source);
                    charge(units, source);
                });
                restored.values().forEach(retained::root);
                retained.measure();
                return restored;
            } catch (StateException state) {
                throw fault(Code.PERSISTENCE_FAILURE, state.reason() + ": " + state.getMessage(), source);
            } catch (IOException | RuntimeException error) {
                if (error instanceof Fault fault) {
                    throw fault;
                }
                throw fault(Code.PERSISTENCE_FAILURE, "Snapshot migration failed", source);
            }
        }

        private void define(BindingDeclaration binding, Environment environment) {
            Value value = evaluate(binding.initializer(), environment);
            Symbol symbol = program.bindingOf(binding.binding().name());
            checkType(symbol.type(), value, binding.initializer().span());
            reserve(1, binding.span());
            environment.define(symbol, value);
        }

        private Flow block(Block block, Environment environment) {
            enter(block.span());
            try {
                charge(1, block.span());
                reserve(1, block.span());
                for (Statement statement : block.statements()) {
                    Flow flow = statement(statement, environment);
                    if (flow != Control.NEXT) {
                        return flow;
                    }
                }
                return Control.NEXT;
            } finally {
                nesting--;
            }
        }

        private Flow statement(Statement statement, Environment environment) {
            if (hooks != null && hooks.beforeStatement(new Site(statement.span(), environment))) {
                throw fault(Code.CANCELLED, "Execution stopped by the debugger", statement.span());
            }
            charge(1, statement.span());
            switch (statement) {
                case BindingDeclaration binding -> define(binding, environment);
                case Assignment assignment -> assign(assignment, environment);
                case Update update -> {
                    Address target = address(update.target(), environment);
                    double value = number(requireValue(target.value(), update.target().span()), update.target().span());
                    double step = update.operator() == UpdateOperator.INCREMENT ? 1.0 : -1.0;
                    store(target, finite(value + step, update.span()), update.span());
                }
                case CallStatement call -> discard(invoke(call.call(), environment), call.span());
                case If conditional -> {
                    for (Branch branch : conditional.branches()) {
                        if (condition(branch.condition(), environment)) {
                            return block(branch.body(), new Environment(environment));
                        }
                    }
                    if (conditional.fallback() != null) {
                        return block(conditional.fallback(), new Environment(environment));
                    }
                }
                case While loop -> {
                    while (condition(loop.condition(), environment)) {
                        charge(1, loop.span());
                        Flow flow = block(loop.body(), new Environment(environment));
                        if (flow == Control.BREAK) {
                            break;
                        }
                        if (flow instanceof Returned) {
                            return flow;
                        }
                    }
                }
                case For loop -> {
                    Value iterable = evaluate(loop.iterable(), environment);
                    for (Value item : snapshot(iterable, loop.iterable().span())) {
                        charge(1, loop.span());
                        Environment locals = new Environment(environment);
                        locals.define(program.bindingOf(loop.variable()), item);
                        Flow flow = block(loop.body(), locals);
                        if (flow == Control.BREAK) {
                            break;
                        }
                        if (flow instanceof Returned) {
                            return flow;
                        }
                    }
                }
                case Break ignored -> { return Control.BREAK; }
                case Continue ignored -> { return Control.CONTINUE; }
                case Return returned -> {
                    if (returned.values().isEmpty()) {
                        return new Returned(ReturnValues.EMPTY);
                    }
                    return new Returned(new ReturnValues(evaluateAll(returned.values(), environment)));
                }
            }
            return Control.NEXT;
        }

        private void assign(Assignment assignment, Environment environment) {
            List<Target> targets = assignment.targets();
            List<Expression> expressions = assignment.values();
            if (assignment.operator() != AssignmentOperator.ASSIGN) {
                Address target = address(targets.getFirst(), environment);
                Value left = requireValue(target.value(), assignment.span());
                Value right = evaluate(expressions.getFirst(), environment);
                BinaryOperator operator = switch (assignment.operator()) {
                    case ADD -> BinaryOperator.ADD;
                    case SUBTRACT -> BinaryOperator.SUBTRACT;
                    case MULTIPLY -> BinaryOperator.MULTIPLY;
                    case DIVIDE -> BinaryOperator.DIVIDE;
                    case REMAINDER -> BinaryOperator.REMAINDER;
                    case ASSIGN -> throw new AssertionError("Unexpected plain assignment");
                };
                store(target, binary(operator, left, right, assignment.span()), assignment.span());
                return;
            }

            List<Value> values;
            if (targets.size() > 1 && expressions.size() == 1) {
                Expression expression = expressions.getFirst();
                if (expression instanceof Call direct) {
                    values = call(direct, environment).values();
                } else {
                    values = unpack(evaluate(expression, environment), targets, expression.span());
                }
            } else {
                values = evaluateAll(expressions, environment);
            }
            requireReturnCount(values.size(), targets.size(), assignment.span());
            charge(targets.size(), assignment.span());
            reserve(targets.size(), assignment.span());
            var addresses = new ArrayList<Address>();
            for (Target target : targets) {
                addresses.add(address(target, environment));
            }
            var inserts = new java.util.IdentityHashMap<DictValue, java.util.Set<String>>();
            for (int i = 0; i < addresses.size(); i++) {
                Address target = addresses.get(i);
                checkType(target.type(), values.get(i), targets.get(i).span());
                target.validate().run();
                if (target.dictionary() != null && target.dictionary().get(target.key()) == null) {
                    var keys = inserts.computeIfAbsent(target.dictionary(), ignored -> new java.util.HashSet<>());
                    if (keys.add(target.key())) {
                        reserve((long) target.key().length() + 1, assignment.span());
                    }
                    collectionSize(target.dictionary().size() + keys.size(), assignment.span());
                }
            }
            for (int index = 0; index < targets.size(); index++) {
                addresses.get(index).write().accept(values.get(index));
            }
        }

        private List<Value> evaluateAll(List<Expression> expressions, Environment environment) {
            List<Value> values = new ArrayList<>();
            for (Expression expression : expressions) {
                values.add(evaluate(expression, environment));
            }
            return values;
        }

        private boolean condition(Expression expression, Environment environment) {
            return bool(evaluate(expression, environment), expression.span());
        }

        private Value evaluate(Expression expression, Environment environment) {
            enter(expression.span());
            try {
                charge(1, expression.span());
                reserve(1, expression.span());
                return switch (expression) {
                    case NumberLiteral literal -> {
                        charge(literal.spelling().length(), literal.span());
                        yield finite(Double.parseDouble(literal.spelling()), literal.span());
                    }
                    case StringLiteral literal -> {
                        reserve(literal.value().length(), literal.span());
                        yield new Text(literal.value());
                    }
                    case BooleanLiteral literal -> new Bool(literal.value());
                    case dev.wiktorfila.filasignal.core.ast.Ast.Nil ignored -> Value.Nil.INSTANCE;
                    case Name name -> read(name, environment);
                    case Group group -> evaluate(group.expression(), environment);
                    case Unary unary -> unary(unary, environment);
                    case Binary binary -> evaluateBinary(binary, environment);
                    case Call call -> single(invoke(call, environment), call.span());
                    case Member member -> member(member, environment, false);
                    case Self ignored -> environment.self();
                    case Index index -> indexed(evaluate(index.receiver(), environment),
                            evaluate(index.index(), environment), index.span());
                    case ListLiteral list -> {
                        collectionSize(list.elements().size(), list.span());
                        reserve(list.elements().size(), list.span());
                        yield new ListValue(evaluateAll(list.elements(), environment));
                    }
                    case Tuple tuple -> {
                        collectionSize(tuple.elements().size(), tuple.span());
                        reserve(tuple.elements().size(), tuple.span());
                        yield new TupleValue(evaluateAll(tuple.elements(), environment));
                    }
                    case Dictionary dictionary -> {
                        collectionSize(dictionary.entries().size(), dictionary.span());
                        var result = new DictValue();
                        for (DictionaryEntry entry : dictionary.entries()) {
                            charge((long) entry.key().length() + 1, entry.span());
                            reserve((long) entry.key().length() + 1, entry.span());
                            result.put(entry.key(), evaluate(entry.value(), environment));
                        }
                        yield result;
                    }
                    default -> throw fault(Code.UNSUPPORTED_FEATURE, "Expression is unavailable in the scalar interpreter",
                            expression.span());
                };
            } finally {
                nesting--;
            }
        }

        private Value member(Member member, Environment environment, boolean callTarget) {
            return member(member, evaluate(member.receiver(), environment), callTarget);
        }

        private Value member(Member member, Value receiver, boolean callTarget) {
            String name = member.name().text();
            if (receiver instanceof HostHandle handle) {
                var property = hostMember(handle, member, false);
                return host(property, List.of(handle), member.span()).values().getFirst();
            }
            if (receiver instanceof Instance instance) {
                Value field = instance.get(name);
                if (field != null) {
                    return field;
                }
                charge(instance.type().declaration().members().size(), member.span());
                for (ClassMember candidate : instance.type().declaration().members()) {
                    if (callTarget && candidate instanceof Function function && function.name().text().equals(name)) {
                        return new BoundMethod(instance, function);
                    }
                }
                throw fault(Code.MISSING_KEY, "Unknown or uninitialized instance member", member.span());
            }
            if (receiver instanceof EnumType enumeration) {
                return requireValue(enumeration.members().get(name), member.span());
            }
            if (receiver instanceof DictValue dict) {
                charge((long) name.length() + 1, member.span());
                Value value = dict.get(name);
                if (value == null && callTarget && DICTIONARY_METHODS.contains(name)) {
                    return new CollectionMethod(dict, name);
                }
                return requireValue(value, member.span());
            }
            if (receiver instanceof ListValue list) {
                if (name.equals("length")) {
                    return new Num(list.size());
                }
                if (callTarget && LIST_METHODS.contains(name)) {
                    return new CollectionMethod(list, name);
                }
            }
            if (receiver instanceof TupleValue tuple && name.equals("length")) {
                return new Num(tuple.elements().size());
            }
            if (receiver == Namespace.JSON && callTarget) {
                return switch (name) {
                    case "parse" -> Builtin.JSON_PARSE;
                    case "stringify" -> Builtin.JSON_STRINGIFY;
                    default -> throw fault(Code.MISSING_KEY, "Unknown JSON operation", member.span());
                };
            }
            if (receiver instanceof ErrorValue error) {
                return switch (member.name().text()) {
                    case "code" -> new Text(error.code());
                    case "message" -> new Text(error.message());
                    default -> throw fault(Code.TYPE_MISMATCH, "Error values expose code and message", member.name().span());
                };
            }
            throw fault(Code.UNSUPPORTED_FEATURE, "Only error values support member reads", member.span());
        }

        private Value unary(Unary unary, Environment environment) {
            Value operand = evaluate(unary.operand(), environment);
            return switch (unary.operator()) {
                case NOT -> new Bool(!bool(operand, unary.operand().span()));
                case PLUS -> finite(number(operand, unary.operand().span()), unary.span());
                case MINUS -> finite(-number(operand, unary.operand().span()), unary.span());
            };
        }

        private Value evaluateBinary(Binary binary, Environment environment) {
            Value left = evaluate(binary.left(), environment);
            return switch (binary.operator()) {
                case AND -> new Bool(bool(left, binary.left().span()) && condition(binary.right(), environment));
                case OR -> new Bool(bool(left, binary.left().span()) || condition(binary.right(), environment));
                default -> binary(binary.operator(), left, evaluate(binary.right(), environment), binary.span());
            };
        }

        private Value binary(BinaryOperator operator, Value left, Value right, SourceSpan span) {
            if (operator == BinaryOperator.IS || operator == BinaryOperator.IS_NOT) {
                return new Bool(equal(left, right, span) == (operator == BinaryOperator.IS));
            }
            if (operator == BinaryOperator.ADD && left instanceof Text a && right instanceof Text b) {
                long length = (long) a.value().length() + b.value().length();
                charge(length, span);
                if (length > MAX_CONCATENATED_LENGTH) {
                    throw fault(Code.EXECUTION_BUDGET, "String exceeds the supported storage limit", span);
                }
                reserve(length, span);
                return new Text(a.value() + b.value());
            }

            double a = number(left, span);
            double b = number(right, span);
            if ((operator == BinaryOperator.DIVIDE || operator == BinaryOperator.REMAINDER) && b == 0.0) {
                throw fault(Code.DIVISION_BY_ZERO, "Division or remainder by zero", span);
            }
            return switch (operator) {
                case ADD -> finite(a + b, span);
                case SUBTRACT -> finite(a - b, span);
                case MULTIPLY -> finite(a * b, span);
                case DIVIDE -> finite(a / b, span);
                case REMAINDER -> finite(a % b, span);
                case LESS -> new Bool(a < b);
                case LESS_EQUAL -> new Bool(a <= b);
                case GREATER -> new Bool(a > b);
                case GREATER_EQUAL -> new Bool(a >= b);
                default -> throw new AssertionError(operator);
            };
        }

        private boolean equal(Value left, Value right, SourceSpan span) {
            record Pair(Value left, Value right) {}
            var work = new ArrayDeque<Pair>();
            var seen = new java.util.IdentityHashMap<Value, java.util.Set<Value>>();
            work.push(new Pair(left, right));
            while (!work.isEmpty()) {
                Pair pair = work.pop();
                Value a = pair.left();
                Value b = pair.right();
                charge(1, span);
                if (a == b) {
                    continue;
                }
                if (a.getClass() != b.getClass()) {
                    return false;
                }
                if (a instanceof ListValue || a instanceof TupleValue || a instanceof DictValue) {
                    if (!seen.computeIfAbsent(a, ignored -> java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>())).add(b)) {
                        continue;
                    }
                }
                if (a instanceof ListValue || a instanceof TupleValue) {
                    List<Value> first = snapshot(a, span);
                    List<Value> second = snapshot(b, span);
                    if (first.size() != second.size()) {
                        return false;
                    }
                    charge(first.size(), span);
                    reserve(first.size(), span);
                    for (int i = first.size() - 1; i >= 0; i--) {
                        work.push(new Pair(first.get(i), second.get(i)));
                    }
                } else if (a instanceof DictValue first && b instanceof DictValue second) {
                    if (first.size() != second.size()) {
                        return false;
                    }
                    charge(first.size(), span);
                    reserve(first.size(), span);
                    for (String key : first.keys()) {
                        charge((long) key.length() + 1, span);
                        Value other = second.get(key);
                        if (other == null) {
                            return false;
                        }
                        work.push(new Pair(first.get(key), other));
                    }
                } else if (!equalScalar(a, b, span)) {
                    return false;
                }
            }
            return true;
        }

        private boolean equalScalar(Value left, Value right, SourceSpan span) {
            if (left instanceof Num a && right instanceof Num b) {
                return a.value() == b.value();
            }
            if (left instanceof Text a && right instanceof Text b) {
                return sameText(a.value(), b.value(), span);
            }
            if (left instanceof ErrorValue a && right instanceof ErrorValue b) {
                return sameText(a.code(), b.code(), span) && sameText(a.message(), b.message(), span);
            }
            if (left instanceof HostHandle a && right instanceof HostHandle b) {
                return a.equals(b);
            }
            return left == right || (left instanceof Bool a && right instanceof Bool b && a.value() == b.value())
                    || (left instanceof EnumMember a && right instanceof EnumMember b && a.equals(b));
        }

        private boolean sameText(String left, String right, SourceSpan span) {
            charge((long) left.length() + right.length(), span);
            return left.equals(right);
        }

        private ReturnValues call(Call call, Environment environment) {
            return invoke(call, environment).values();
        }

        /**
         * A {@code (value, err)} host call used where one value is expected yields the value, or stops the handler
         * with the host's explanation. Other calls must produce exactly one value.
         */
        private Value single(Called called, SourceSpan span) {
            List<Value> values = called.values().values();
            if (called.host() != null && called.host().valueOrError() && values.size() == 2) {
                if (values.getLast() instanceof ErrorValue error) {
                    throw fault(Code.HOST_FAILURE, describe(called.host(), error), span);
                }
                return values.getFirst();
            }
            requireReturnCount(values.size(), 1, span);
            return values.getFirst();
        }

        /** Reports an ignored host failure once per call site and invocation, without ending the handler. */
        private void discard(Called called, SourceSpan span) {
            if (called.host() != null && called.host().hasErrorResult()
                    && called.values().values().getLast() instanceof ErrorValue error && warned.add(span)) {
                String line = "Warning: " + describe(called.host(), error);
                if (logLines < limits.logLines() && line.length() + 1L <= limits.logUnits() - logUnits) {
                    emit(line, span);
                }
            }
        }

        private static String describe(HostRegistry.Function host, ErrorValue error) {
            return host.name() + " failed: " + error.message() + " (" + error.code() + ")";
        }

        private Called invoke(Call call, Environment environment) {
            charge(1, call.span());
            HostRegistry.Function host = program.hostCall(call);
            if (host != null) {
                return new Called(host(host, evaluateAll(call.arguments(), environment), call.span()), host);
            }
            Expression target = call.callee();
            while (target instanceof Group group) {
                charge(1, group.span());
                target = group.expression();
            }
            Value callee;
            if (target instanceof Member member) {
                Value receiver = evaluate(member.receiver(), environment);
                if (receiver instanceof HostHandle handle) {
                    var method = hostMember(handle, member, true);
                    var arguments = new ArrayList<Value>();
                    arguments.add(handle);
                    arguments.addAll(evaluateAll(call.arguments(), environment));
                    return new Called(host(method, arguments, call.span()), method);
                }
                callee = member(member, receiver, true);
            } else {
                callee = evaluate(target, environment);
            }
            if (!(callee instanceof Callable callable)) {
                throw fault(Code.NOT_CALLABLE, "Target is not a callable function", call.callee().span());
            }
            return new Called(invokeCallable(callable, evaluateAll(call.arguments(), environment), call.span()), null);
        }

        private HostRegistry.Function hostMember(HostHandle handle, Member site, boolean method) {
            var object = program.hostObject(handle.type());
            var member = object == null ? null : (method ? object.methods() : object.properties()).get(site.name().text());
            if (member == null) {
                throw fault(Code.MISSING_KEY, "Unknown host member: " + site.name().text(), site.span());
            }
            return member;
        }

        private ReturnValues host(HostRegistry.Function host, List<Value> arguments, SourceSpan site) {
            requireArguments(arguments.size(), host.parameters().size(), site);
            if (stack.size() >= limits.maxCallDepth()) {
                throw fault(Code.CALL_DEPTH_LIMIT, "Function call depth exceeds " + limits.maxCallDepth(), site);
            }
            stack.push(new RuntimeIssue.Frame(host.name(), site));
            try {
                charge(host.cost(), site);
                for (int i = 0; i < arguments.size(); i++) {
                    if (!host.parameters().get(i).accepts(arguments.get(i))) {
                        throw fault(Code.TYPE_MISMATCH, "Invalid host argument " + host.parameters().get(i).name(), site);
                    }
                }
                if (!capabilities.containsAll(host.capabilities())) {
                    if (!host.hasErrorResult()) {
                        throw fault(Code.HOST_FAILURE, "Host capability denied", site);
                    }
                    reserve(host.returns().size() + 33L, site);
                    var denied = new ArrayList<Value>(java.util.Collections.nCopies(host.returns().size(), Value.Nil.INSTANCE));
                    denied.set(denied.size() - 1, new ErrorValue("host.denied", "Host capability denied"));
                    return new ReturnValues(denied);
                }
                List<Value> values;
                try {
                    values = List.copyOf(onOwner(() -> host.handler().invoke(List.copyOf(arguments), new HostRegistry.Context() {
                        @Override
                        public void charge(long operations) {
                            if (operations < 0) {
                                throw new IllegalArgumentException("Host work must be nonnegative");
                            }
                            Execution.this.charge(operations, site);
                        }

                        @Override
                        public void log(String line) {
                            emit(line, site);
                        }

                        @Override
                        public void print(Value value) {
                            Execution.this.print(value, site);
                        }
                    })));
                } catch (Fault fault) {
                    throw fault;
                } catch (RuntimeException exception) {
                    throw fault(Code.HOST_FAILURE, "Host implementation failed", site);
                }
                if (values.size() != host.returns().size()) {
                    throw fault(Code.HOST_FAILURE, "Host returned the wrong number of values", site);
                }
                var retained = new RetainedData(units -> {
                    charge(units, site);
                    reserve(units, site);
                });
                for (int i = 0; i < values.size(); i++) {
                    if (!host.returns().get(i).accepts(values.get(i))) {
                        throw fault(Code.HOST_FAILURE, "Host returned a value outside its contract", site);
                    }
                    retained.root(values.get(i));
                }
                retained.measure();
                return new ReturnValues(values);
            } finally {
                stack.pop();
            }
        }

        private ReturnValues invokeCallable(Callable callable, List<Value> arguments, SourceSpan site) {
            if (stack.size() >= limits.maxCallDepth()) {
                throw fault(Code.CALL_DEPTH_LIMIT, "Function call depth exceeds " + limits.maxCallDepth(), site);
            }
            enter(site);
            String name = switch (callable) {
                case UserFunction user -> user.declaration().name().text();
                case CollectionMethod method -> method.name();
                case BoundMethod method -> method.declaration().name().text();
                case ClassType type -> type.declaration().name().text();
                case Builtin builtin -> switch (builtin) {
                    case PRINT -> "print";
                    case JSON_PARSE -> "json.parse";
                    case JSON_STRINGIFY -> "json.stringify";
                    case STR -> "str";
                };
            };
            stack.push(new RuntimeIssue.Frame(name, site));
            try {
                charge(1, site);
                if (callable instanceof UserFunction user) {
                    return executeBody(user.declaration().parameters(), user.declaration().body(), null, arguments, site);
                }
                if (callable instanceof BoundMethod method) {
                    return executeBody(method.declaration().parameters(), method.declaration().body(), method.receiver(),
                            arguments, site);
                }
                if (callable instanceof ClassType type) {
                    return single(construct(type, arguments, site));
                }
                if (callable instanceof CollectionMethod method) {
                    return collectionCall(method, arguments, site);
                }
                requireArguments(arguments.size(), 1, site);
                if (callable == Builtin.PRINT) {
                    print(arguments.getFirst(), site);
                    return ReturnValues.EMPTY;
                }
                if (callable == Builtin.STR) {
                    String formatted = format(arguments.getFirst(), site);
                    reserve(formatted.length() + 1L, site);
                    return single(new Text(formatted));
                }
                return json((Builtin) callable, arguments.getFirst(), site);
            } finally {
                stack.pop();
                nesting--;
            }
        }

        private ReturnValues executeBody(List<Binding> parameters, Block body, Instance self,
                                         List<Value> arguments, SourceSpan site) {
            requireArguments(arguments.size(), parameters.size(), site);
            charge(arguments.size(), site);
            reserve(arguments.size() + 1L, site);
            Environment frame = new Environment(globals, self);
            for (int i = 0; i < parameters.size(); i++) {
                Symbol symbol = program.bindingOf(parameters.get(i).name());
                Value argument = arguments.get(i);
                checkType(symbol.type(), argument, site);
                frame.define(symbol, argument);
            }
            Flow flow = block(body, frame);
            return flow instanceof Returned returned ? returned.values() : ReturnValues.EMPTY;
        }

        private Value construct(ClassType type, List<Value> arguments, SourceSpan site) {
            charge((long) type.declaration().members().size() * 2 + 1, site);
            Constructor constructor = null;
            for (ClassMember member : type.declaration().members()) {
                if (member instanceof Constructor found) {
                    constructor = found;
                }
            }
            requireArguments(arguments.size(), constructor == null ? 0 : constructor.parameters().size(), site);
            if (constructor != null) {
                for (int i = 0; i < arguments.size(); i++) {
                    checkType(program.bindingOf(constructor.parameters().get(i).name()).type(), arguments.get(i), site);
                }
            }
            reserve(1, site);
            Instance instance = new Instance(type);
            Environment fields = new Environment(globals, instance);
            for (ClassMember member : type.declaration().members()) {
                if (member instanceof BindingDeclaration field) {
                    Value value = evaluate(field.initializer(), fields);
                    checkType(annotation(field.binding()), value, field.initializer().span());
                    reserve((long) field.binding().name().text().length() + 1, site);
                    instance.put(field.binding().name().text(), value);
                }
            }
            if (constructor != null) {
                executeBody(constructor.parameters(), constructor.body(), instance, arguments, site);
            }
            return instance;
        }

        private void checkType(String expected, Value value, SourceSpan span) {
            if (expected != null && !expected.equals(typeName(value))) {
                throw fault(Code.TYPE_MISMATCH, "Expected " + expected + ", got " + typeName(value), span);
            }
        }

        private String annotation(Binding binding) {
            return binding.type() == null ? null : binding.type().text();
        }

        private void print(Value value, SourceSpan span) {
            emit(format(value, span), span);
        }

        /** Text for print and str: whole numbers without a fraction and collections as bounded readable summaries. */
        private String format(Value value, SourceSpan span) {
            return switch (value) {
                case Num number -> ValueSummary.number(number.value());
                case Bool bool -> Boolean.toString(bool.value());
                case Text text -> text.value();
                case Value.Nil ignored -> "nil";
                case ErrorValue error -> {
                    charge((long) error.code().length() + error.message().length(), span);
                    yield error.code() + ": " + error.message();
                }
                case ListValue ignored -> summary(value, span);
                case TupleValue ignored -> summary(value, span);
                case DictValue ignored -> summary(value, span);
                case Callable ignored -> throw fault(Code.TYPE_MISMATCH, "Cannot print a callable value", span);
                case Namespace ignored -> throw fault(Code.TYPE_MISMATCH, "Cannot print a namespace", span);
                case EnumMember enumeration -> enumeration.enumeration() + "." + enumeration.name();
                case EnumType ignored -> throw fault(Code.TYPE_MISMATCH, "Cannot print an enum type", span);
                case Instance instance -> instance.type().declaration().name().text() + "(instance)";
                case HostHandle handle -> handle.reference() == null ? handle.type() + "(handle)"
                        : handle.type() + "(\"" + handle.reference() + "\")";
            };
        }

        private String summary(Value value, SourceSpan span) {
            charge(64, span);
            String text = ValueSummary.describe(value, 400);
            charge(text.length(), span);
            return text;
        }

        private void emit(String message, SourceSpan span) {
            charge((long) message.length() + 1, span);
            if (logLines >= limits.logLines() || message.length() + 1L > limits.logUnits() - logUnits) {
                throw fault(Code.OUTPUT_LIMIT, "Invocation output limit exceeded", span);
            }
            logLines++;
            logUnits += message.length() + 1L;
            try {
                onOwner(() -> {
                    output.accept(message);
                    return null;
                });
            } catch (RuntimeException exception) {
                throw fault(Code.HOST_FAILURE, "The print host failed", span);
            }
        }

        /** Host and output callbacks run on the owning thread when a debugger executes this invocation. */
        private <T> T onOwner(java.util.function.Supplier<T> callback) {
            return hooks == null ? callback.get() : hooks.call(callback);
        }

        /** Debugger view of one statement boundary. Reads happen while this execution is blocked in the hook. */
        private final class Site implements Instrumentation.Location {
            private final SourceSpan span;
            private final Environment environment;

            private Site(SourceSpan span, Environment environment) {
                this.span = span;
                this.environment = environment;
            }

            @Override public SourceSpan span() { return span; }
            @Override public List<RuntimeIssue.Frame> frames() { return List.copyOf(stack); }
            @Override public long operationsUsed() { return workUsed(); }

            @Override
            public List<Instrumentation.Variable> locals(int limit) {
                var result = new ArrayList<Instrumentation.Variable>();
                var seen = new java.util.HashSet<String>();
                if (environment.self() != null && limit > 0) {
                    seen.add("self");
                    result.add(variable("local", "self", environment.self()));
                }
                for (Environment scope = environment; scope != null && scope != globals && result.size() < limit;
                     scope = scope.parent()) {
                    for (var entry : scope.entries()) {
                        if (result.size() < limit && local(entry.getKey()) && seen.add(entry.getKey().name())) {
                            result.add(variable("local", entry.getKey().name(), entry.getValue()));
                        }
                    }
                }
                return result;
            }

            @Override
            public List<Instrumentation.Variable> globals(int limit) {
                var result = new ArrayList<Instrumentation.Variable>();
                if (globals == null) {
                    return result;
                }
                for (var entry : globals.entries()) {
                    Symbol symbol = entry.getKey();
                    if (result.size() < limit && (symbol.kind() == Symbol.Kind.GLOBAL || symbol.kind() == Symbol.Kind.CONSTANT)) {
                        result.add(variable(symbol.kind() == Symbol.Kind.GLOBAL ? "global" : "const", symbol.name(),
                                entry.getValue()));
                    }
                }
                return result;
            }

            private boolean local(Symbol symbol) {
                return switch (symbol.kind()) {
                    case LOCAL, PARAMETER, LOOP_VARIABLE, CONSTANT -> true;
                    default -> false;
                };
            }

            private Instrumentation.Variable variable(String scope, String name, Value value) {
                return new Instrumentation.Variable(scope, name,
                        dev.wiktorfila.filasignal.core.runtime.value.ValueSummary.describe(value, VARIABLE_SUMMARY));
            }
        }

        private Address address(Target target, Environment environment) {
            if (target instanceof Name name) {
                Symbol symbol = program.bindingOf(name);
                if (symbol.declaration() == name) {
                    reserve(1, name.span());
                }
                return new Address(environment.read(symbol), value -> write(name, value, environment),
                        () -> {}, null, null, symbol.type());
            }
            Value receiver;
            Value key;
            if (target instanceof Index index) {
                receiver = evaluate(index.receiver(), environment);
                key = evaluate(index.index(), environment);
            } else {
                Member member = (Member) target;
                receiver = evaluate(member.receiver(), environment);
                key = new Text(member.name().text());
            }
            SourceSpan span = target.span();
            if (receiver instanceof Instance instance && target instanceof Member) {
                String field = key(key, span);
                charge(instance.type().declaration().members().size(), span);
                for (ClassMember member : instance.type().declaration().members()) {
                    if (member instanceof BindingDeclaration declaration && declaration.binding().name().text().equals(field)) {
                        Value value = requireValue(instance.get(field), span);
                        return new Address(value, update -> instance.put(field, update), () -> {}, null, null,
                                annotation(declaration.binding()));
                    }
                }
                throw fault(Code.MISSING_KEY, "Unknown instance field", span);
            }
            if (receiver instanceof HostHandle) {
                throw fault(Code.IMMUTABLE_VALUE, "Host properties are read-only", span);
            }
            if (receiver instanceof EnumMember || receiver instanceof EnumType) {
                throw fault(Code.IMMUTABLE_VALUE, "Enums are immutable", span);
            }
            if (receiver instanceof DictValue dict) {
                if (!dict.writable()) {
                    throw fault(Code.IMMUTABLE_VALUE, "Snapshot is read-only", span);
                }
                String name = key(key, span);
                return new Address(dict.get(name), value -> {
                    if (dict.get(name) == null) {
                        collectionSize(dict.size() + 1, span);
                    }
                    dict.put(name, value);
                }, () -> {}, dict, name, null);
            }
            if (receiver instanceof TupleValue) {
                throw fault(Code.IMMUTABLE_VALUE, "Tuples are immutable", span);
            }
            if (receiver instanceof ListValue list && target instanceof Index) {
                int at = index(key, list.size(), false, span);
                Runnable validate = () -> index(key, list.size(), false, span);
                return new Address(list.get(at), value -> {
                    validate.run();
                    list.set(at, value);
                }, validate, null, null, null);
            }
            throw fault(Code.TYPE_MISMATCH, "Expected a writable collection target", span);
        }

        private Value indexed(Value receiver, Value key, SourceSpan span) {
            return switch (receiver) {
                case DictValue dict -> requireValue(dict.get(key(key, span)), span);
                case ListValue list -> list.get(index(key, list.size(), false, span));
                case TupleValue tuple -> tuple.elements().get(index(key, tuple.elements().size(), false, span));
                default -> throw fault(Code.TYPE_MISMATCH, "Expected list, tuple or dictionary", span);
            };
        }

        private Value requireValue(Value value, SourceSpan span) {
            if (value == null) {
                throw fault(Code.MISSING_KEY, "Collection key is absent", span);
            }
            return value;
        }

        private String key(Value value, SourceSpan span) {
            if (value instanceof Text text) {
                charge((long) text.value().length() + 1, span);
                return text.value();
            }
            throw fault(Code.TYPE_MISMATCH, "Dictionary keys must be strings", span);
        }

        private int index(Value value, int size, boolean allowEnd, SourceSpan span) {
            double n = number(value, span);
            if (n != Math.rint(n) || n < 0 || n >= size + (allowEnd ? 1 : 0)) {
                throw fault(Code.INDEX_OUT_OF_BOUNDS, "Expected an integer index within bounds", span);
            }
            return (int) n;
        }

        private List<Value> snapshot(Value value, SourceSpan span) {
            return switch (value) {
                case ListValue list -> {
                    charge(list.size(), span);
                    reserve(list.size(), span);
                    yield list.snapshot();
                }
                case TupleValue tuple -> tuple.elements();
                case DictValue dict -> {
                    charge(dict.size(), span);
                    reserve(dict.size() * 2L, span);
                    yield dict.keys().stream().<Value>map(Text::new).toList();
                }
                default -> throw fault(Code.UNSUPPORTED_ITERABLE, "Cannot iterate over " + typeName(value), span);
            };
        }

        private List<Value> unpack(Value value, List<Target> targets, SourceSpan span) {
            if (value instanceof ListValue || value instanceof TupleValue) {
                return snapshot(value, span);
            }
            if (value instanceof DictValue dict) {
                requireReturnCount(dict.size(), targets.size(), span);
                var names = new java.util.HashSet<String>();
                var values = new ArrayList<Value>();
                for (Target target : targets) {
                    if (!(target instanceof Name name) || !names.add(name.text())) {
                        throw fault(Code.UNSUPPORTED_DESTRUCTURING, "Expected distinct bare target names", target.span());
                    }
                    charge((long) name.text().length() + 1, target.span());
                    values.add(requireValue(dict.get(name.text()), target.span()));
                }
                return values;
            }
            throw fault(Code.UNSUPPORTED_DESTRUCTURING, "Cannot destructure " + typeName(value), span);
        }

        private void collectionSize(int size, SourceSpan span) {
            if (size > Value.MAX_COLLECTION_SIZE) {
                throw fault(Code.COLLECTION_LIMIT, "Collection exceeds " + Value.MAX_COLLECTION_SIZE + " entries", span);
            }
        }

        private ReturnValues collectionCall(CollectionMethod method, List<Value> args, SourceSpan span) {
            int arity = switch (method.name()) {
                case "clear", "keys", "values" -> 0;
                case "insert" -> 2;
                default -> 1;
            };
            requireArguments(args.size(), arity, span);
            if (method.receiver() instanceof ListValue list) {
                charge((long) list.size() + 1, span);
                switch (method.name()) {
                    case "append" -> {
                        collectionSize(list.size() + 1, span);
                        reserve(1, span);
                        list.insert(list.size(), args.getFirst());
                    }
                    case "insert" -> {
                        collectionSize(list.size() + 1, span);
                        int position = index(args.getFirst(), list.size(), true, span);
                        reserve(1, span);
                        list.insert(position, args.get(1));
                    }
                    case "remove" -> {
                        return single(list.remove(index(args.getFirst(), list.size(), false, span)));
                    }
                    case "clear" -> list.clear();
                    default -> throw fault(Code.NOT_CALLABLE, "Unknown list method", span);
                }
            } else {
                DictValue dict = (DictValue) method.receiver();
                if (!dict.writable() && (method.name().equals("remove") || method.name().equals("clear"))) {
                    throw fault(Code.IMMUTABLE_VALUE, "Snapshot is read-only", span);
                }
                charge((long) dict.size() + 1, span);
                switch (method.name()) {
                    case "keys" -> { return single(new ListValue(snapshot(dict, span))); }
                    case "values" -> {
                        reserve(dict.size() * 2L + 1, span);
                        return single(new ListValue(dict.values()));
                    }
                    case "contains" -> { return single(new Bool(dict.get(key(args.getFirst(), span)) != null)); }
                    case "remove" -> {
                        String key = key(args.getFirst(), span);
                        Value old = requireValue(dict.get(key), span);
                        dict.remove(key);
                        return single(old);
                    }
                    case "clear" -> dict.clear();
                    default -> throw fault(Code.NOT_CALLABLE, "Unknown dictionary method", span);
                }
            }
            return ReturnValues.EMPTY;
        }

        private ReturnValues json(Builtin operation, Value value, SourceSpan span) {
            try {
                Value result;
                if (operation == Builtin.JSON_PARSE) {
                    if (!(value instanceof Text text)) {
                        throw fault(Code.TYPE_MISMATCH, "json.parse expects a string", span);
                    }
                    charge(text.value().length(), span);
                    reserve(text.value().length() * 3L + 1, span);
                    result = JsonCodec.parse(text.value());
                } else {
                    result = new Text(JsonCodec.stringify(value, units -> {
                        charge(units, span);
                        reserve(units, span);
                    }));
                }
                return new ReturnValues(List.of(result, Value.Nil.INSTANCE));
            } catch (JsonCodec.Invalid error) {
                return new ReturnValues(List.of(Value.Nil.INSTANCE, new ErrorValue("json.invalid", error.getMessage())));
            }
        }

        private ReturnValues single(Value value) {
            return new ReturnValues(List.of(value));
        }

        private void store(Address target, Value value, SourceSpan span) {
            checkType(target.type(), value, span);
            target.validate().run();
            if (target.dictionary() != null && target.dictionary().get(target.key()) == null) {
                reserve((long) target.key().length() + 1, span);
            }
            target.write().accept(value);
        }

        private record Address(Value value, Consumer<Value> write, Runnable validate, DictValue dictionary, String key,
                               String type) {
        }

        private Value read(Name name, Environment environment) {
            Value found = environment.read(program.bindingOf(name));
            if (found == null) {
                throw fault(Code.UNKNOWN_BINDING, "Binding has no value in the current environment", name.span());
            }
            return found;
        }

        private void write(Name name, Value value, Environment environment) {
            Symbol symbol = program.bindingOf(name);
            if (symbol == null) {
                throw fault(Code.UNKNOWN_BINDING, "Missing resolution for assignment target", name.span());
            }
            if (!symbol.mutable()) {
                throw fault(Code.IMMUTABLE_BINDING, "Cannot assign to an immutable binding", name.span());
            }
            if (symbol.declaration() == name) {
                environment.define(symbol, value);
            } else if (!environment.update(symbol, value)) {
                throw fault(Code.UNKNOWN_BINDING, "Assignment target has no value in the current environment", name.span());
            }
        }

        private Num finite(double value, SourceSpan span) {
            if (!Double.isFinite(value)) {
                throw fault(Code.NON_FINITE_NUMBER, "Arithmetic result is outside the finite binary64 range", span);
            }
            return new Num(value);
        }

        private double number(Value value, SourceSpan span) {
            if (value instanceof Num number) {
                return number.value();
            }
            throw fault(Code.TYPE_MISMATCH, "Expected a number, got " + typeName(value), span);
        }

        private boolean bool(Value value, SourceSpan span) {
            if (value instanceof Bool bool) {
                return bool.value();
            }
            throw fault(Code.TYPE_MISMATCH, "Expected a Boolean, got " + typeName(value), span);
        }

        private void requireArguments(int actual, int expected, SourceSpan span) {
            if (actual != expected) {
                throw fault(Code.ARGUMENT_COUNT, "Expected " + expected + " arguments but got " + actual, span);
            }
        }

        private void requireReturnCount(int actual, int expected, SourceSpan span) {
            if (actual != expected) {
                throw fault(Code.RETURN_ARITY, "Expected " + expected + " values but got " + actual, span);
            }
        }

        private void enter(SourceSpan span) {
            if (nesting >= MAX_EXECUTION_NESTING) {
                throw fault(Code.EXECUTION_DEPTH_LIMIT, "Execution nesting exceeds " + MAX_EXECUTION_NESTING, span);
            }
            nesting++;
        }

        private void reserve(long units, SourceSpan span) {
            if (units > limits.dataUnits() - data) {
                throw fault(Code.DATA_LIMIT, "Controller logical data limit exceeded", span);
            }
            data += units;
        }

        private void charge(long operations, SourceSpan span) {
            if (cancelled.getAsBoolean()) {
                throw fault(Code.CANCELLED, "Execution cancelled", span);
            }
            if (operations > remaining) {
                remaining = 0;
                throw fault(Code.EXECUTION_BUDGET, "Execution operation budget exhausted", span);
            }
            remaining -= operations;
        }

        private Fault fault(Code code, String message, SourceSpan span) {
            return new Fault(new RuntimeIssue(new Diagnostic(code, message, span), List.copyOf(stack)));
        }
    }

    /** Bounds for structured event values: nested collections of scalars only, copied on every delivery. */
    public static final int MAX_EVENT_NODES = 512;
    public static final int MAX_EVENT_DEPTH = 8;
    public static final int MAX_EVENT_TEXT = 16_384;

    /**
     * True for a value a host or another program may deliver to a handler: a scalar, or lists, tuples and dictionaries
     * of them within {@link #MAX_EVENT_NODES} values, {@link #MAX_EVENT_DEPTH} levels and {@link #MAX_EVENT_TEXT}
     * characters. Device handles, functions and class instances never travel in events.
     */
    public static boolean eventValue(Value value) {
        long[] budget = {MAX_EVENT_NODES, MAX_EVENT_TEXT};
        return eventValue(value, 0, budget);
    }

    private static boolean eventValue(Value value, int depth, long[] budget) {
        if (--budget[0] < 0 || depth > MAX_EVENT_DEPTH) {
            return false;
        }
        switch (value) {
            case Text text -> budget[1] -= text.value().length();
            case ListValue list -> {
                for (Value element : list.snapshot()) {
                    if (!eventValue(element, depth + 1, budget)) {
                        return false;
                    }
                }
            }
            case TupleValue tuple -> {
                for (Value element : tuple.elements()) {
                    if (!eventValue(element, depth + 1, budget)) {
                        return false;
                    }
                }
            }
            case DictValue dict -> {
                for (String key : dict.keys()) {
                    budget[1] -= key.length();
                    if (!eventValue(dict.get(key), depth + 1, budget)) {
                        return false;
                    }
                }
            }
            case ErrorValue error -> budget[1] -= error.code().length() + error.message().length();
            default -> {
                if (!scalarEventValue(value)) {
                    return false;
                }
            }
        }
        return budget[1] >= 0;
    }

    /** An independent copy of a validated event value. Read-only dictionaries stay read-only. */
    static Value detach(Value value) {
        return switch (value) {
            case ListValue list -> new ListValue(list.snapshot().stream().map(Interpreter::detach).toList());
            case TupleValue tuple -> new TupleValue(tuple.elements().stream().map(Interpreter::detach).toList());
            case DictValue dict -> {
                var entries = new java.util.LinkedHashMap<String, Value>();
                for (String key : dict.keys()) {
                    entries.put(key, detach(dict.get(key)));
                }
                if (!dict.writable()) {
                    yield new DictValue(entries);
                }
                var copy = new DictValue();
                entries.forEach(copy::put);
                yield copy;
            }
            default -> value;
        };
    }

    /** True for immutable scalars: the values allowed as event filters and in queued event arguments. */
    public static boolean scalarEventValue(Value value) {
        return switch (value) {
            case Num ignored -> true;
            case Bool ignored -> true;
            case Value.Nil ignored -> true;
            case Text text -> text.value().length() <= 4096;
            case EnumMember member -> (long) member.name().length() + member.enumeration().length() <= 4096;
            case null, default -> false;
        };
    }

    static String typeName(Value value) {
        return switch (value) {
            case Num ignored -> "number";
            case Text ignored -> "string";
            case Bool ignored -> "bool";
            case Value.Nil ignored -> "nil";
            case ErrorValue ignored -> "error";
            case Callable ignored -> "function";
            case Namespace ignored -> "namespace";
            case ListValue ignored -> "list";
            case TupleValue ignored -> "tuple";
            case DictValue ignored -> "dict";
            case Instance instance -> instance.type().declaration().name().text();
            case EnumMember member -> member.enumeration();
            case EnumType ignored -> "enum type";
            case HostHandle handle -> handle.type();
        };
    }

    private sealed interface Flow {
    }

    /** A call's results and, for a direct host call, the host function that produced them. */
    private record Called(ReturnValues values, HostRegistry.Function host) {
    }

    private enum Control implements Flow { NEXT, BREAK, CONTINUE }

    private record Returned(ReturnValues values) implements Flow {
    }

    private static final class Fault extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient RuntimeIssue issue;

        private Fault(RuntimeIssue issue) {
            super(null, null, false, false);
            this.issue = issue;
        }
    }
}
