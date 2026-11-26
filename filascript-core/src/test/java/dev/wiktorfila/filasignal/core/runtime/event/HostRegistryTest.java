package dev.wiktorfila.filasignal.core.runtime.event;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HostRegistryTest {
    @Test
    void invokesOpaquePropertiesAndMethodsWithTypedArgumentsAndBudget() throws Exception {
        var id = java.util.UUID.randomUUID();
        var token = new HostHandle("Resource", id, id);
        var slot = new Slot("self", Set.of(Type.HANDLE), "Resource");
        var result = new Slot("value", Set.of(Type.NUMBER));
        var lookup = new Function("test.from", List.of(),
                List.of(new Slot("handle", Set.of(Type.HANDLE), "Resource")), "Resolve a resource.", Set.of(), 1,
                (args, ctx) -> List.of(token));
        var property = new Function("Resource.size", List.of(slot), List.of(result), "Size.", Set.of(), 1,
                (args, ctx) -> List.of(new Num(7)));
        var method = new Function("Resource.match", List.of(slot, slot), List.of(result), "Match.",
                Set.of("resource.read"), 1, (args, ctx) -> List.of(new Num(args.get(0).equals(args.get(1)) ? 1 : 0)));
        var expensive = new Function("Resource.expensive", List.of(slot), List.of(result), "Charge work.",
                Set.of(), 1, (args, ctx) -> {
                    ctx.charge(20_000);
                    return List.of(new Num(0));
                });
        var type = new ObjectType("Resource", java.util.Map.of("size", property),
                java.util.Map.of("match", method, "expensive", expensive));
        var registry = new HostRegistry(List.of(lookup), List.of(type));
        var syntax = Parser.parse(new SourceText("object", "object.fsig", """
                global stored = nil
                fn main() {
                    a = test.from()
                    b = test.from()
                    return a.size, a.match(b), a is b
                }
                fn bad() { test.from().match(4) }
                fn write() { test.from().size = 3 }
                fn expensive() { test.from().expensive() }
                fn hold() { stored = [test.from()] }
                """));
        var analysis = Resolver.resolve(syntax, registry);
        assertTrue(analysis.diagnostics().isEmpty(), analysis.diagnostics()::toString);
        var program = analysis.program().orElseThrow();
        var runtime = new Interpreter(program, new ExecutionLimits(16, 1000), ignored -> {}, Set.of("resource.read"));
        var success = runtime.invoke("main", List.of());
        assertEquals(List.of(new Num(7), new Num(1), new Bool(true)), success.returns().values());
        assertEquals(Code.TYPE_MISMATCH, runtime.invoke("bad", List.of()).issue().orElseThrow().diagnostic().code());
        assertEquals(Code.IMMUTABLE_VALUE, runtime.invoke("write", List.of()).issue().orElseThrow().diagnostic().code());
        assertEquals(Code.EXECUTION_BUDGET, runtime.invoke("expensive", List.of()).issue().orElseThrow().diagnostic().code());
        var denied = new Interpreter(program, new ExecutionLimits(16, 1000), ignored -> {}, Set.of());
        assertEquals(Code.HOST_FAILURE, denied.invoke("main", List.of()).issue().orElseThrow().diagnostic().code());
        assertTrue(runtime.invoke("hold", List.of()).issue().isEmpty());
        assertTrue(assertThrows(java.io.IOException.class, runtime::snapshotGlobals).getMessage().contains("transient"));
    }

    @Test
    void validatesMetadataArgumentsResultsPermissionAndDynamicWork() {
        var value = new Slot("value", Set.of(Type.NUMBER));
        var invalid = new Function("math.bad", List.of(value), List.of(value), "A contract violation.",
                Set.of(), 1, (args, ctx) -> List.of(new Text("wrong")));
        var finish = new Function("tools.slow", List.of(), List.of(), "Charge expensive work.",
                Set.of("tools.work"), 1, (args, ctx) -> {
                    ctx.charge(20_000);
                    return List.of();
                });
        HostRegistry registry = new HostRegistry(List.of(finish, invalid));
        assertEquals(List.of("math.bad", "tools.slow"), registry.functions().stream().map(Function::name).toList());
        assertThrows(IllegalArgumentException.class, () -> new HostRegistry(List.of(finish, finish)));
        String script = """
                fn main() { math.bad(1) }
                fn badArg() { math.bad(false) }
                fn heavy() { tools.slow() }
                """;
        var result = Resolver.resolve(Parser.parse(new SourceText("host", "host.fsig", script)), registry);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics()::toString);
        var runtime = new Interpreter(result.program().orElseThrow(), new ExecutionLimits(16, 1000),
                ignored -> {}, Set.of("tools.work"));
        assertEquals(Code.HOST_FAILURE, runtime.invoke("main", List.of()).issue().orElseThrow().diagnostic().code());
        assertEquals(Code.TYPE_MISMATCH, runtime.invoke("badArg", List.of()).issue().orElseThrow().diagnostic().code());
        assertEquals(Code.EXECUTION_BUDGET, runtime.invoke("heavy", List.of()).issue().orElseThrow().diagnostic().code());
    }
}
