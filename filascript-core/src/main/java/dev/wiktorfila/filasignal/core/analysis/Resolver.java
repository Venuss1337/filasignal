package dev.wiktorfila.filasignal.core.analysis;

import dev.wiktorfila.filasignal.core.analysis.Symbol.Kind;
import dev.wiktorfila.filasignal.core.ast.Ast.*;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.parser.ParseResult;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves names and validates executable syntax without evaluating expressions. */
public final class Resolver {
    private static final int MAX_DEPTH = 192;
    private static final Set<String> PRIMITIVE_TYPES = Set.of("number", "string", "bool", "list", "tuple", "dict", "error");

    private final Map<Name, Symbol> names = new IdentityHashMap<>();
    private final Map<Call, HostRegistry.Function> hostCalls = new IdentityHashMap<>();
    private static final HostRegistry.Slot TICKS = new HostRegistry.Slot("ticks", Set.of(HostRegistry.Type.NUMBER));
    private static final Map<String, HostRegistry.Event> BUILTIN_EVENTS = Map.of(
            "startup", new HostRegistry.Event("startup", List.of(), List.of(), "Runs once when the program starts."),
            "shutdown", new HostRegistry.Event("shutdown", List.of(), List.of(), "Runs once when the program stops."),
            "timer.every", new HostRegistry.Event("timer.every", List.of(TICKS), List.of(),
                    "Runs every given number of ticks. 20 ticks is about one second."));

    private final Map<EventDeclaration, EventSite> events = new IdentityHashMap<>();
    private final Map<String, Symbol> hostEnums = new java.util.LinkedHashMap<>();
    // File constants initialized by a host reference function, such as detectors.named("gate"), and their handle type.
    private final Map<Symbol, String> references = new HashMap<>();
    private final HostRegistry hosts;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<Symbol> availableConstants = new HashSet<>();
    private final Scope builtins = new Scope(null);
    private final Scope file = new Scope(builtins);
    private final Symbol print = new Symbol(0, "print", Kind.BUILTIN, null, null);
    private final Symbol json = new Symbol(1, "json", Kind.BUILTIN, null, null);
    private final Symbol str = new Symbol(2, "str", Kind.BUILTIN, null, null);
    private int nextId = 3;
    private int depth;
    private int loops;
    private boolean inInitializer;
    private boolean inClass;
    private boolean inConstructor;

    private Resolver(HostRegistry hosts) {
        this.hosts = hosts;
        builtins.symbols.put(print.name(), print);
        builtins.symbols.put("json", json);
        builtins.symbols.put("str", str);
        for (String name : new java.util.TreeSet<>(hosts.enums().keySet())) {
            Symbol enumeration = new Symbol(nextId++, name, Kind.ENUM, null, null);
            builtins.symbols.put(name, enumeration);
            hostEnums.put(name, enumeration);
        }
    }

    /** The built-in lifecycle and timer events every program can handle. */
    public static List<HostRegistry.Event> builtinEvents() {
        return BUILTIN_EVENTS.values().stream().sorted(Comparator.comparing(HostRegistry.Event::name)).toList();
    }

    public static AnalysisResult resolve(ParseResult parsed) {
        return resolve(parsed, HostRegistry.EMPTY);
    }

    public static AnalysisResult resolve(ParseResult parsed, HostRegistry hosts) {
        if (!parsed.diagnostics().isEmpty()) {
            return new AnalysisResult(Optional.empty(), parsed.diagnostics());
        }
        return new Resolver(hosts).resolveProgram(parsed.program());
    }

    private AnalysisResult resolveProgram(Program program) {
        for (Declaration declaration : program.declarations()) {
            switch (declaration) {
                case Function function -> declare(file, function.name(), Kind.FUNCTION);
                case BindingDeclaration binding -> {
                    switch (binding.kind()) {
                        case GLOBAL -> declare(file, binding.binding(), Kind.GLOBAL);
                        case CONSTANT -> declare(file, binding.binding(), Kind.CONSTANT);
                        default -> issue(Code.INVALID_DECLARATION, "Expected a global or constant at file scope", binding.span());
                    }
                }
                case ClassDeclaration clazz -> declare(file, clazz.name(), Kind.CLASS);
                case EnumDeclaration enumeration -> declare(file, enumeration.name(), Kind.ENUM);
                case EventDeclaration ignored -> { }
            }
        }

        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof BindingDeclaration binding) {
                annotation(binding.binding());
                inInitializer = true;
                expression(binding.initializer(), file);
                inInitializer = false;
                if (binding.kind() == BindingKind.CONSTANT) {
                    Symbol symbol = names.get(binding.binding().name());
                    if (symbol != null) {
                        availableConstants.add(symbol);
                        Expression initializer = binding.initializer();
                        while (initializer instanceof Group group) {
                            initializer = group.expression();
                        }
                        HostRegistry.Function reference = initializer instanceof Call call ? hostCalls.get(call) : null;
                        if (reference != null && reference.reference() && reference.returns().getFirst().handleType() != null) {
                            references.put(symbol, reference.returns().getFirst().handleType());
                        }
                    }
                }
            } else if (declaration instanceof Function function) {
                resolveFunction(function);
            } else if (declaration instanceof ClassDeclaration clazz) {
                resolveClass(clazz);
            } else if (declaration instanceof EnumDeclaration enumeration) {
                Scope members = new Scope(null);
                for (Name member : enumeration.members()) {
                    declare(members, member, Kind.CONSTANT);
                }
            }
        }

        // Events resolve last so a handler may use a device constant declared further down the file.
        for (Declaration declaration : program.declarations()) {
            if (declaration instanceof EventDeclaration event) {
                resolveEvent(event);
            }
        }

        diagnostics.sort(Comparator.comparingInt(problem -> problem.span().startOffset()));
        if (!diagnostics.isEmpty()) {
            return new AnalysisResult(Optional.empty(), diagnostics);
        }
        return new AnalysisResult(Optional.of(new ResolvedProgram(program, names, file.symbols, print, json, str, hostCalls,
                events, hostEnums, hosts)), List.of());
    }

    private void resolveEvent(EventDeclaration event) {
        String name = String.join(".", event.name().stream().map(Name::text).toList());
        String delivered = name;
        Symbol source = null;
        HostRegistry.Event definition;
        Symbol root = event.name().size() == 2 ? file.lookup(event.name().getFirst().text()) : null;
        if (root != null && references.containsKey(root)) {
            // on gate.entityEnter(entity): the constant names the device and the event belongs to its type.
            names.put(event.name().getFirst(), root);
            source = root;
            delivered = event.name().get(1).text();
            HostRegistry.ObjectType type = hosts.object(references.get(root));
            definition = type == null ? null : type.events().get(delivered);
            if (definition == null) {
                issue(Code.INVALID_DECLARATION, root.name() + " has no event named " + delivered
                        + ". Type \"on " + root.name() + ".\" and open completion to list its events", event.span());
            }
        } else if (root != null && root.kind() == Kind.CONSTANT && root.declaration() != null) {
            issue(Code.INVALID_DECLARATION, root.name() + " is not a device. Declare it with a reference such as "
                    + "const " + root.name() + " = detectors.named(\"" + root.name() + "\")", event.span());
            definition = null;
        } else {
            definition = BUILTIN_EVENTS.getOrDefault(name, hosts.event(name));
            if (definition == null && hosts.strictEvents()) {
                issue(Code.INVALID_DECLARATION, "Unknown event " + name
                        + ". Type \"on \" and open completion to list the available events", event.span());
            }
        }
        List<Expression> arguments = event.arguments();
        int filters = definition == null ? arguments.size() : Math.min(definition.filters().size(), arguments.size());
        if (definition != null && arguments.size() < definition.filters().size()) {
            issue(Code.INVALID_DECLARATION, name + " needs " + definition.filters().size() + " filter"
                    + (definition.filters().size() == 1 ? "" : "s") + ": " + definition.filters().stream()
                    .map(HostRegistry.Slot::name).collect(java.util.stream.Collectors.joining(", ")), event.span());
        } else if (definition != null && arguments.size() - filters > definition.parameters().size()) {
            issue(Code.INVALID_DECLARATION, definition.parameters().isEmpty() ? name + " provides no values"
                    : name + " provides at most " + definition.parameters().size() + " value"
                    + (definition.parameters().size() == 1 ? "" : "s") + ": "
                    + String.join(", ", definition.parameters()), event.span());
        }
        inInitializer = true;
        for (Expression argument : arguments.subList(0, filters)) {
            expression(argument, file);
        }
        inInitializer = false;
        Scope body = new Scope(file);
        var binding = new Symbol(nextId++, "event", Kind.CONSTANT, null, null);
        body.symbols.put(binding.name(), binding);
        var parameters = new ArrayList<Symbol>();
        for (Expression argument : arguments.subList(filters, arguments.size())) {
            if (argument instanceof Name parameter) {
                declare(body, parameter, Kind.PARAMETER);
                Symbol declared = body.symbols.get(parameter.text());
                if (declared != null && declared.declaration() == parameter) {
                    parameters.add(declared);
                }
            } else {
                issue(Code.INVALID_DECLARATION, "Handler values are received into plain names, such as entity",
                        argument.span());
            }
        }
        events.put(event, new EventSite(binding, delivered, filters, parameters, source, definition));
        block(event.body(), body);
    }

    private void resolveFunction(Function function) {
        resolveBody(function.parameters(), function.body());
    }

    private void resolveBody(List<Binding> parameters, Block body) {
        Scope locals = new Scope(file);
        for (Binding parameter : parameters) {
            annotation(parameter);
            declare(locals, parameter, Kind.PARAMETER);
        }
        block(body, locals);
    }

    private void resolveClass(ClassDeclaration clazz) {
        Scope members = new Scope(null);
        boolean constructorSeen = false;
        inClass = true;
        for (ClassMember member : clazz.members()) {
            switch (member) {
                case BindingDeclaration field -> {
                    declare(members, field.binding().name(), Kind.CONSTANT);
                    annotation(field.binding());
                    expression(field.initializer(), file);
                }
                case Function method -> {
                    declare(members, method.name(), Kind.FUNCTION);
                    resolveFunction(method);
                }
                case Constructor constructor -> {
                    if (constructorSeen) {
                        issue(Code.DUPLICATE_BINDING, "Only one constructor is allowed in 0.1", constructor.name().span());
                    }
                    constructorSeen = true;
                    inConstructor = true;
                    resolveBody(constructor.parameters(), constructor.body());
                    inConstructor = false;
                }
            }
        }
        inClass = false;
    }

    private void block(Block block, Scope scope) {
        for (Statement statement : block.statements()) {
            statement(statement, scope);
        }
    }

    private void statement(Statement statement, Scope scope) {
        if (!enter(statement.span())) {
            return;
        }
        try {
            switch (statement) {
                case BindingDeclaration binding -> {
                    expression(binding.initializer(), scope);
                    annotation(binding.binding());
                    if (binding.kind() == BindingKind.CONSTANT) {
                        declare(scope, binding.binding(), Kind.CONSTANT);
                    } else if (binding.kind() == BindingKind.ANNOTATED) {
                        declare(scope, binding.binding(), Kind.LOCAL);
                    } else {
                        issue(Code.INVALID_DECLARATION, "Global declarations are only allowed at file scope", binding.span());
                    }
                }
                case Assignment assignment -> {
                    for (Expression value : assignment.values()) {
                        expression(value, scope);
                    }
                    boolean implicit = assignment.operator() == AssignmentOperator.ASSIGN;
                    for (Target target : assignment.targets()) {
                        write(target, scope, implicit);
                    }
                }
                case Update update -> write(update.target(), scope, false);
                case CallStatement call -> expression(call.call(), scope);
                case If conditional -> {
                    for (Branch branch : conditional.branches()) {
                        expression(branch.condition(), scope);
                        block(branch.body(), new Scope(scope));
                    }
                    if (conditional.fallback() != null) {
                        block(conditional.fallback(), new Scope(scope));
                    }
                }
                case While loop -> {
                    expression(loop.condition(), scope);
                    loopBody(loop.body(), new Scope(scope));
                }
                case For loop -> {
                    expression(loop.iterable(), scope);
                    Scope body = new Scope(scope);
                    declare(body, loop.variable(), Kind.LOOP_VARIABLE);
                    loopBody(loop.body(), body);
                }
                case Break ignored -> checkLoop("break", statement.span());
                case Continue ignored -> checkLoop("continue", statement.span());
                case Return returned -> {
                    if (inConstructor && !returned.values().isEmpty()) {
                        issue(Code.INVALID_CONTROL_FLOW, "Constructors cannot return values", returned.span());
                    }
                    for (Expression value : returned.values()) {
                        expression(value, scope);
                    }
                }
            }
        } finally {
            depth--;
        }
    }

    private void loopBody(Block body, Scope scope) {
        loops++;
        try {
            block(body, scope);
        } finally {
            loops--;
        }
    }

    private void checkLoop(String keyword, SourceSpan span) {
        if (loops == 0) {
            issue(Code.INVALID_CONTROL_FLOW, keyword + " requires an enclosing loop", span);
        }
    }

    private void expression(Expression expression, Scope scope) {
        if (!enter(expression.span())) {
            return;
        }
        try {
            switch (expression) {
                case Name name -> read(name, scope, false);
                case NumberLiteral ignored -> { }
                case StringLiteral ignored -> { }
                case BooleanLiteral ignored -> { }
                case Nil ignored -> { }
                case Group group -> expression(group.expression(), scope);
                case Unary unary -> expression(unary.operand(), scope);
                case Binary binary -> {
                    expression(binary.left(), scope);
                    expression(binary.right(), scope);
                }
                case Call call -> {
                    HostRegistry.Function reference = inInitializer ? host(call.callee(), scope) : null;
                    if (reference != null && reference.reference()) {
                        hostCalls.put(call, reference);
                        for (Expression argument : call.arguments()) {
                            expression(argument, scope);
                        }
                    } else if (inInitializer) {
                        issue(Code.INVALID_INITIALIZER, "File-scope initializers cannot call functions", call.span());
                    } else {
                        Expression callee = call.callee();
                        while (callee instanceof Group group) {
                            callee = group.expression();
                        }
                        HostRegistry.Function host = host(callee, scope);
                        if (host != null) {
                            hostCalls.put(call, host);
                        } else if (callee instanceof Name name) {
                            read(name, scope, true);
                        } else {
                            expression(callee, scope);
                        }
                        for (Expression argument : call.arguments()) {
                            expression(argument, scope);
                        }
                    }
                }
                case Member member -> {
                    if (member.receiver() instanceof Name name && qualifiedName(scope.lookup(name.text()))) {
                        read(name, scope, true);
                    } else {
                        expression(member.receiver(), scope);
                    }
                }
                case Self self -> {
                    if (!inClass) {
                        unsupported("self outside a class", self.span());
                    }
                }
                case Index index -> {
                    expression(index.receiver(), scope);
                    expression(index.index(), scope);
                }
                case ListLiteral list -> list.elements().forEach(item -> expression(item, scope));
                case Tuple tuple -> tuple.elements().forEach(item -> expression(item, scope));
                case Dictionary dictionary -> dictionary.entries().forEach(entry -> expression(entry.value(), scope));
            }
        } finally {
            depth--;
        }
    }

    private HostRegistry.Function host(Expression callee, Scope scope) {
        var path = new java.util.ArrayDeque<String>();
        while (callee instanceof Member member) {
            if (path.size() >= MAX_DEPTH) {
                return null;
            }
            path.addFirst(member.name().text());
            callee = member.receiver();
        }
        if (!(callee instanceof Name root)) {
            return null;
        }
        Symbol binding = scope.lookup(root.text());
        if (binding != null && binding != print) {
            return null;
        }
        path.addFirst(root.text());
        return hosts.lookup(String.join(".", path));
    }

    private void annotation(Binding binding) {
        if (binding.type() != null) {
            Name type = binding.type();
            Symbol symbol = file.lookup(type.text());
            if (!PRIMITIVE_TYPES.contains(type.text())
                    && (symbol == null || (symbol.kind() != Kind.CLASS && symbol.kind() != Kind.ENUM))) {
                issue(Code.UNKNOWN_BINDING, "Unknown annotation type", type.span());
            }
        }
    }

    private void read(Name name, Scope scope, boolean callPosition) {
        Symbol symbol = scope.lookup(name.text());
        if (symbol == null) {
            unknown(name);
            return;
        }
        names.put(name, symbol);
        if (inInitializer && !availableConstants.contains(symbol) && !(symbol.kind() == Kind.ENUM && callPosition)) {
            issue(Code.INVALID_INITIALIZER, "File-scope initializers can only read earlier constants", name.span());
        } else if (!callPosition && (symbol.kind() == Kind.FUNCTION || symbol.kind() == Kind.BUILTIN
                || symbol.kind() == Kind.CLASS || symbol.kind() == Kind.ENUM)) {
            issue(Code.FUNCTION_AS_VALUE, "Named functions can only be used as call targets", name.span());
        }
    }

    private void write(Target target, Scope scope, boolean implicit) {
        if (!(target instanceof Name name)) {
            expression(target, scope);
            return;
        }
        Symbol symbol = scope.lookup(name.text());
        if (symbol == null) {
            if (implicit) {
                declare(scope, name, Kind.LOCAL);
            } else {
                unknown(name);
            }
        } else {
            names.put(name, symbol);
            if (!symbol.mutable()) {
                issue(Code.IMMUTABLE_BINDING, "Cannot assign to an immutable binding", name.span());
            }
        }
    }

    private boolean qualifiedName(Symbol symbol) {
        return symbol != null && (symbol == json || symbol.kind() == Kind.ENUM);
    }

    private void declare(Scope scope, Binding binding, Kind kind) {
        declare(scope, binding.name(), kind, binding.type() == null ? null : binding.type().text());
    }

    private void declare(Scope scope, Name name, Kind kind) {
        declare(scope, name, kind, null);
    }

    private void declare(Scope scope, Name name, Kind kind, String type) {
        if ((kind == Kind.CLASS || kind == Kind.ENUM) && PRIMITIVE_TYPES.contains(name.text())) {
            issue(Code.DUPLICATE_BINDING, "Type name is reserved", name.span());
            return;
        }
        if (scope.symbols.containsKey(name.text())) {
            issue(Code.DUPLICATE_BINDING, "Name is already declared in this scope", name.span());
            return;
        }
        Symbol symbol = new Symbol(nextId++, name.text(), kind, name, type);
        names.put(name, symbol);
        scope.symbols.put(name.text(), symbol);
    }

    private boolean enter(SourceSpan span) {
        if (depth == MAX_DEPTH) {
            issue(Code.ANALYSIS_DEPTH_LIMIT, "Syntax tree exceeds " + MAX_DEPTH + " nested nodes", span);
            return false;
        }
        depth++;
        return true;
    }

    private void unknown(Name name) {
        issue(Code.UNKNOWN_BINDING, "No visible binding for this name", name.span());
    }

    private void unsupported(String feature, SourceSpan span) {
        issue(Code.UNSUPPORTED_FEATURE, "Unavailable in the scalar interpreter: " + feature, span);
    }

    private void issue(Code code, String message, SourceSpan span) {
        diagnostics.add(new Diagnostic(code, message, span));
    }

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Symbol> symbols = new HashMap<>();

        private Scope(Scope parent) {
            this.parent = parent;
        }

        private Symbol lookup(String name) {
            for (Scope scope = this; scope != null; scope = scope.parent) {
                Symbol found = scope.symbols.get(name);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }
}
