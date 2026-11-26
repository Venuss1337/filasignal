package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafetyTest {
    @Test
    void boundsTemporaryAllocationsAndResetsInvocationAccounting() {
        Interpreter interpreter = interpreter("""
                class Box { payload = [1, 2, 3] }
                fn stress() { while true { temporary = Box() } }
                fn small() { return Box().payload.length }
                """, new ExecutionLimits(64, 1_000_000, 256, 8, 100), new ArrayList<>());
        assertEquals(Code.DATA_LIMIT, code(interpreter.invoke("stress", List.of())));
        for (int i = 0; i < 30; i++) {
            assertTrue(interpreter.invoke("small", List.of()).issue().isEmpty());
        }
    }

    @Test
    void retainsCyclesWithoutRecursionAcrossInvocations() {
        Interpreter interpreter = interpreter("""
                global root = []
                fn build() { root.append(root) }
                fn inspect() { return root[0] is root }
                """, ExecutionLimits.DEFAULT, new ArrayList<>());
        assertTrue(interpreter.invoke("build", List.of()).issue().isEmpty());
        assertTrue(interpreter.invoke("inspect", List.of()).issue().isEmpty());
    }

    @Test
    void limitsOutputAndHonorsCooperativeCancellationAndCallerBudget() {
        var output = new ArrayList<String>();
        Interpreter interpreter = interpreter("fn spam() { while true { print(1) } }",
                new ExecutionLimits(64, 1000, 1000, 2, 100), output);
        assertEquals(Code.OUTPUT_LIMIT, code(interpreter.invoke("spam", List.of())));
        assertEquals(List.of("1", "1"), output);
        assertEquals(Code.CANCELLED, code(interpreter.invoke("spam", List.of(), 100, () -> true)));
        var bounded = interpreter.invoke("spam", List.of(), 3, () -> false);
        assertEquals(Code.EXECUTION_BUDGET, code(bounded));
        assertEquals(3, bounded.operationsUsed());
    }

    private static Code code(ExecutionResult result) {
        return result.issue().orElseThrow().diagnostic().code();
    }

    private static Interpreter interpreter(String source, ExecutionLimits limits, List<String> output) {
        var compiled = Resolver.resolve(Parser.parse(new SourceText("safety", "safety.fsig", source)));
        assertTrue(compiled.diagnostics().isEmpty(), compiled.diagnostics()::toString);
        return new Interpreter(compiled.program().orElseThrow(), limits, output::add);
    }
}
