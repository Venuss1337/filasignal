package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.AnalysisResult;
import dev.wiktorfila.filasignal.core.analysis.ResolvedProgram;
import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.Bool;
import dev.wiktorfila.filasignal.core.runtime.value.Value.ErrorValue;
import dev.wiktorfila.filasignal.core.runtime.value.Value.Num;
import dev.wiktorfila.filasignal.core.runtime.value.Value.Text;
import dev.wiktorfila.filasignal.core.source.SourcePosition;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class InterpreterTest {
    @Test
    void executesTheControllerFixtureAndKeepsGlobalsPerInstance() throws IOException {
        List<String> output = new ArrayList<>();
        ResolvedProgram program = compile(fixture("controller.fsig"));
        Interpreter first = new Interpreter(program, ExecutionLimits.DEFAULT, output::add);
        Interpreter second = new Interpreter(program, ExecutionLimits.DEFAULT, output::add);
        assertEquals(List.of(new Num(19), new Num(16)), invoke(first, "main"));
        assertEquals(List.of(new Num(35), new Num(16)), invoke(first, "main"));
        assertEquals(List.of(new Num(19), new Num(16)), invoke(second, "main"));
        assertEquals(List.of("tick ⚡", "tick ⚡", "tick ⚡"), output);
    }

    @Test
    void reassignsDynamicLocalsAndUpdatesTheNearestScope() {
        List<Value> values = run("""
                fn main() {
                    x = 1
                    if true {
                        x += 2
                        const x = x + 10
                        if true { print(x) }
                    }
                    old = x
                    x = false
                    return old, x
                }
                """);
        assertEquals(List.of(new Num(3), new Bool(false)), values);
    }

    @Test
    void parametersShadowGlobalsAndCallsCannotSeeCallerLocals() {
        assertEquals(List.of(new Num(9), new Num(3)), run("""
                global x = 3
                fn main() {
                    value = param(8)
                    return value, read()
                }
                fn param(x) {
                    x++
                    return x
                }
                fn read() { return x }
                """));
        assertTrue(analyze(source("fn main() { x = 2; other() }; fn other() { print(x) }")).program().isEmpty());
    }

    @Test
    void resolvesProgramsBeforeAnyInitializerOrPrintRuns() throws IOException {
        AnalysisResult result = analyze(fixture("invalid.fsig"));
        assertTrue(result.program().isEmpty());
        assertEquals(List.of(Code.IMMUTABLE_BINDING, Code.UNKNOWN_BINDING, Code.UNKNOWN_BINDING,
                Code.INVALID_CONTROL_FLOW), result.diagnostics().stream().map(issue -> issue.code()).toList());
    }

    @Test
    void evaluatesFiniteArithmeticStringConcatenationAndOrderedComparison() {
        assertEquals(List.of(new Num(6), new Text("ab"), new Bool(true), new Bool(true), new Bool(false)),
                run("""
                        fn main() {
                            value = 5
                            value--
                            value *= 4
                            value /= 2
                            value -= 1
                            value %= 6
                            return +value + 5, "a" + "b", 2 < 3 and 3 <= 3, 4 > 3, 3 >= 4
                        }
                        """));
        assertEquals(List.of(new Bool(true), new Bool(true)),
                run("fn main() { return 1e-300 * 1e-300 is 0, -0 is 0 }"));
    }

    @Test
    void faultsOnBothSignedZerosOverflowAndMixedNumericTypes() {
        for (String expression : List.of("2 / 0", "2 / -0", "2 % 0", "2 % -0")) {
            assertEquals(Code.DIVISION_BY_ZERO, fail("fn main() { return " + expression + " }").diagnostic().code());
        }
        assertEquals(Code.NON_FINITE_NUMBER, fail("fn main() { return 1e308 * 2 }").diagnostic().code());
        assertEquals(Code.TYPE_MISMATCH, fail("fn main() { return \"1\" + 2 }").diagnostic().code());
        assertEquals(Code.TYPE_MISMATCH, fail("fn main() { x = false; x++ }").diagnostic().code());
        assertThrows(IllegalArgumentException.class, () -> new Num(Double.NaN));
    }

    @Test
    void shortCircuitsButRequiresBooleansForEvaluatedOperands() {
        List<String> output = new ArrayList<>();
        Interpreter instance = runtime("""
                fn main() {
                    return false and trap(), true or trap(), not false, not (5 is not 5.0)
                }
                fn trap() { print("unexpected"); return 1 / 0 }
                """, ExecutionLimits.DEFAULT, output::add);
        assertEquals(List.of(new Bool(false), new Bool(true), new Bool(true), new Bool(true)), invoke(instance, "main"));
        assertTrue(output.isEmpty());
        for (String statement : List.of("if 1 {}", "while nil {}", "return true and 3", "return not \"x\"")) {
            assertEquals(Code.TYPE_MISMATCH, fail("fn main() { " + statement + " }").diagnostic().code());
        }
    }

    @Test
    void comparesScalarsWithoutCoercionAndExposesExplicitErrorFields() {
        assertEquals(List.of(new Bool(true), new Bool(true), new Bool(false), new Bool(false)),
                run("fn main() { return nil is nil, true is true, 1 is \"1\", \"a\" is \"b\" }"));
        Interpreter instance = runtime("""
                fn inspect(err, same) { return err.code, err.message, err is same, err is not nil }
                """, ExecutionLimits.DEFAULT, ignored -> {});
        assertEquals(List.of(new Text("invalid"), new Text("Bad value"), new Bool(true), new Bool(true)),
                invoke(instance, "inspect", new ErrorValue("invalid", "Bad value"), new ErrorValue("invalid", "Bad value")));
        assertEquals(Code.UNSUPPORTED_FEATURE, fail("fn main() { return 1.field }").diagnostic().code());
    }

    @Test
    void executesElseIfAndReturnFromInsideNestedControlFlow() {
        assertEquals(List.of(new Num(3)), run("""
                fn main() {
                    x = 0
                    while true {
                        x++
                        if x < 2 { continue }
                        else if x < 3 { x += 1 }
                        else { break }
                        if x is 3 { return x }
                    }
                    return 0
                }
                """));
        assertEquals(List.of(new Num(7)), run("fn main() { if false {} else { return 7 }; return 0 }"));
        assertEquals(Code.UNSUPPORTED_ITERABLE, fail("fn main() { for item in \"a\" { print(item) } }").diagnostic().code());
    }

    @Test
    void callsNamedFunctionsAndRecursesThroughFreshFrames() {
        assertEquals(List.of(new Num(120)), run("""
                fn main() { return (factorial)(identity(5)) }
                fn identity(x) { return x }
                fn factorial(n) {
                    if n <= 1 { return 1 }
                    return n * factorial(n - 1)
                }
                """));
        assertEquals(Code.ARGUMENT_COUNT, fail("fn main() { f() }; fn f(x) {}").diagnostic().code());
        assertEquals(Code.ARGUMENT_COUNT, fail("fn main() { print(1, 2) }").diagnostic().code());
        assertEquals(Code.NOT_CALLABLE, fail("fn main() { f = 1; f() }").diagnostic().code());
        assertEquals(Code.UNKNOWN_ENTRY_POINT, fail("fn other() {}").diagnostic().code());
    }

    @Test
    void distinguishesBareFallthroughAndMultipleReturnsFromScalarCalls() {
        Interpreter instance = runtime("""
                fn none() { return }
                fn implicit() {}
                fn pair() { return 1, "b" }
                fn main() {
                    none()
                    implicit()
                    pair()
                    a, b = pair()
                    a, b = b, a
                    return a, b
                }
                """, ExecutionLimits.DEFAULT, ignored -> {});
        assertEquals(List.of(), invoke(instance, "none"));
        assertEquals(List.of(), invoke(instance, "implicit"));
        assertEquals(List.of(new Num(1), new Text("b")), invoke(instance, "pair"));
        assertEquals(List.of(new Text("b"), new Num(1)), invoke(instance, "main"));
    }

    @Test
    void checksExactArityBeforeWritingAnyAssignmentTargets() {
        Interpreter instance = runtime("""
                global a = 0
                global b = 0
                fn one() { return 1 }
                fn pair() { return 2, 3 }
                fn none() {}
                fn badCall() { a, b = one() }
                fn badList() { a, b = 1, 2, 3 }
                fn scalarMultiple() { return pair() }
                fn scalarZero() { return none() }
                fn groupedPair() { a, b = (pair()) }
                fn destructureScalar() { a, b = (one()) }
                fn inspect() { return a, b }
                """, ExecutionLimits.DEFAULT, ignored -> {});
        for (String name : List.of("badCall", "badList", "scalarMultiple", "scalarZero", "groupedPair")) {
            assertEquals(Code.RETURN_ARITY, issue(instance, name).diagnostic().code(), name);
        }
        assertEquals(Code.UNSUPPORTED_DESTRUCTURING, issue(instance, "destructureScalar").diagnostic().code());
        assertEquals(List.of(new Num(0), new Num(0)), invoke(instance, "inspect"));
    }

    @Test
    void commitsRepeatedTargetsLeftToRightAfterEvaluatingValues() {
        assertEquals(List.of(new Num(2), new Num(2), new Num(7)), run("""
                fn main() {
                    x, x = 1, 2
                    a = 7
                    a, b = x, a
                    return x, a, b
                }
                """));
    }

    @Test
    void reportsUnicodeAndCrLfFaultSpansWithCallStack() {
        RuntimeIssue issue = fail("fn main() {\r\n    print(\"é😀\"); inner()\r\n}\r\nfn inner() { return 42 / -0 }\r\n");
        assertEquals(Code.DIVISION_BY_ZERO, issue.diagnostic().code());
        assertEquals("42 / -0", issue.diagnostic().span().text());
        assertEquals(new SourcePosition(4, 21), issue.diagnostic().span().start());
        assertEquals(List.of("inner", "main"), issue.frames().stream().map(RuntimeIssue.Frame::function).toList());
        assertEquals(new SourcePosition(2, 18), issue.frames().getFirst().callSite().start());
        assertEquals("inner()", issue.frames().getFirst().callSite().text());
    }

    @Test
    void terminatesInfiniteLoopsAndChargesLargeStringOperations() {
        Interpreter loop = runtime("fn main() { while true {} }; fn read() { return true }",
                new ExecutionLimits(16, 80), ignored -> {});
        assertEquals(List.of(new Bool(true)), invoke(loop, "read"));
        RuntimeIssue first = issue(loop, "main");
        RuntimeIssue second = issue(loop, "main");
        assertEquals(Code.EXECUTION_BUDGET, first.diagnostic().code());
        assertEquals(first, second);
        assertEquals(List.of(new Bool(true)), invoke(loop, "read"));

        Interpreter growth = runtime("fn main() { s = \"x\"; while true { s += s } }",
                new ExecutionLimits(16, 300_000, 500_000, 256, 32_768), ignored -> {});
        assertEquals(Code.EXECUTION_BUDGET, issue(growth, "main").diagnostic().code());
    }

    @Test
    void boundsCallDepthAndCombinedEvaluationDepth() {
        String recurse = "fn main() { return recurse() }; fn recurse() { return recurse() }";
        Interpreter small = runtime(recurse, new ExecutionLimits(8, 10_000), ignored -> {});
        RuntimeIssue depth = issue(small, "main");
        assertEquals(Code.CALL_DEPTH_LIMIT, depth.diagnostic().code());
        assertEquals(8, depth.frames().size());

        Interpreter configuredHigh = runtime(recurse, new ExecutionLimits(100_000, 100_000), ignored -> {});
        assertEquals(Code.EXECUTION_DEPTH_LIMIT, issue(configuredHigh, "main").diagnostic().code());
    }

    @Test
    void handlesFailedActivationAndHostFailuresWithoutJavaExceptions() {
        Interpreter invalid = runtime("global broken = 1 / 0; fn main() { print(\"never\") }",
                ExecutionLimits.DEFAULT, message -> org.junit.jupiter.api.Assertions.fail(
                        "Initializer failure must prevent execution: " + message));
        assertEquals(Code.DIVISION_BY_ZERO, issue(invalid, "main").diagnostic().code());
        assertEquals(Code.DIVISION_BY_ZERO, issue(invalid, "main").diagnostic().code());

        Interpreter host = runtime("fn main() { print(\"line\") }", ExecutionLimits.DEFAULT,
                ignored -> { throw new IllegalStateException("callback error"); });
        RuntimeIssue failure = issue(host, "main");
        assertEquals(Code.HOST_FAILURE, failure.diagnostic().code());
        assertEquals("print", failure.frames().getFirst().function());
    }

    @Test
    void rejectsSynchronousHostReentryAndRetainsGlobalsAfterFault() {
        List<RuntimeIssue> attemptedReentry = new ArrayList<>();
        Interpreter[] holder = new Interpreter[1];
        holder[0] = runtime("""
                global value = 0
                fn main() { value++; print("trigger"); return 1 / 0 }
                fn read() { return value }
                """, ExecutionLimits.DEFAULT, ignored -> attemptedReentry.add(issue(holder[0], "read")));
        assertEquals(Code.DIVISION_BY_ZERO, issue(holder[0], "main").diagnostic().code());
        assertEquals(Code.HOST_FAILURE, attemptedReentry.getFirst().diagnostic().code());
        assertEquals(List.of(new Num(1)), invoke(holder[0], "read"));
    }

    private static List<Value> run(String script) {
        return invoke(runtime(script, ExecutionLimits.DEFAULT, ignored -> {}), "main");
    }

    private static RuntimeIssue fail(String script) {
        return issue(runtime(script, ExecutionLimits.DEFAULT, ignored -> {}), "main");
    }

    private static Interpreter runtime(String script, ExecutionLimits limits, Consumer<String> output) {
        return new Interpreter(compile(source(script)), limits, output);
    }

    private static List<Value> invoke(Interpreter runtime, String function, Value... values) {
        ExecutionResult result = runtime.invoke(function, List.of(values));
        assertTrue(result.issue().isEmpty(), result.issue()::toString);
        return result.returns().values();
    }

    private static RuntimeIssue issue(Interpreter runtime, String function) {
        return runtime.invoke(function, List.of()).issue().orElseThrow();
    }

    private static ResolvedProgram compile(SourceText source) {
        AnalysisResult result = analyze(source);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics()::toString);
        return result.program().orElseThrow();
    }

    private static AnalysisResult analyze(SourceText source) {
        return Resolver.resolve(Parser.parse(source));
    }

    private static SourceText source(String script) {
        return new SourceText("test", "test.fsig", script);
    }

    private static SourceText fixture(String filename) throws IOException {
        try (InputStream input = InterpreterTest.class.getResourceAsStream("/runtime/" + filename)) {
            assertNotNull(input);
            return SourceText.fromUtf8("fixture:" + filename, filename, input.readAllBytes());
        }
    }
}
