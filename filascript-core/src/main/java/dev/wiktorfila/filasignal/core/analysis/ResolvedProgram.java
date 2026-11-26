package dev.wiktorfila.filasignal.core.analysis;

import dev.wiktorfila.filasignal.core.ast.Ast.Call;
import dev.wiktorfila.filasignal.core.ast.Ast.EventDeclaration;
import dev.wiktorfila.filasignal.core.ast.Ast.Name;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.ast.Ast.Program;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Executable syntax with a binding table keyed by individual AST name occurrences. */
public final class ResolvedProgram {
    private final Program syntax;
    private final Map<Name, Symbol> bindings;
    private final Map<String, Symbol> topLevel;
    private final Symbol print;
    private final Symbol str;
    private final HostRegistry hosts;
    private final Symbol json;
    private final Map<Call, HostRegistry.Function> hostCalls;
    private final Map<EventDeclaration, EventSite> events;
    private final Map<String, Symbol> hostEnums;

    ResolvedProgram(Program syntax, Map<Name, Symbol> bindings, Map<String, Symbol> topLevel, Symbol print, Symbol json,
                    Symbol str, Map<Call, HostRegistry.Function> hostCalls, Map<EventDeclaration, EventSite> events,
                    Map<String, Symbol> hostEnums, HostRegistry hosts) {
        this.hostEnums = Map.copyOf(hostEnums);
        this.syntax = syntax;
        this.hosts = hosts;
        this.bindings = Collections.unmodifiableMap(new IdentityHashMap<>(bindings));
        this.topLevel = Map.copyOf(topLevel);
        this.print = print;
        this.str = str;
        this.json = json;
        this.hostCalls = Collections.unmodifiableMap(new IdentityHashMap<>(hostCalls));
        this.events = Collections.unmodifiableMap(new IdentityHashMap<>(events));
    }

    public Program syntax() {
        return syntax;
    }

    public Symbol bindingOf(Name name) {
        return bindings.get(name);
    }

    public Symbol topLevel(String name) {
        return topLevel.get(name);
    }

    public HostRegistry.Function hostCall(Call call) {
        return hostCalls.get(call);
    }

    public HostRegistry.ObjectType hostObject(String type) { return hosts.object(type); }

    public Symbol eventBinding(EventDeclaration event) {
        return events.get(event).binding();
    }

    public EventSite event(EventDeclaration event) {
        return events.get(event);
    }

    /** Host-provided enumerations visible to the program, such as {@code Direction}, by name. */
    public Map<String, Symbol> hostEnums() { return hostEnums; }

    public Map<String, java.util.List<String>> hostEnumMembers() { return hosts.enums(); }

    public Symbol json() {
        return json;
    }

    /** The {@code str(value)} builtin that formats a value as text the way {@code print} shows it. */
    public Symbol str() {
        return str;
    }

    public Symbol print() {
        return print;
    }
}
