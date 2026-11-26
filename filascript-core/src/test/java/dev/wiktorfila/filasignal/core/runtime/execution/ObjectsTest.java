package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjectsTest {
    @Test
    void constructorsInitializeFieldsInOrderAndInstancesHaveIndependentCollections() {
        assertEquals(List.of(new Num(4), new Num(2), new Num(6), new Bool(true), new Bool(true)), run("""
                global BASE = 2
                class Box {
                    count: number = BASE
                    values: list = [self.count]
                    Box(initial: number) { self.count = initial }
                    fn add(amount: number) {
                        self.values.append(amount)
                        self.count += amount
                        return self.count
                    }
                }
                fn main() {
                    first: Box = Box(4)
                    second = Box(4)
                    nested = [first, second]
                    next = nested[0].add(2)
                    return nested[1].count, first.values.length, next, first is nested[0], first is not second
                }
                """));
    }

    @Test
    void instancesCanReferToThemselvesAndEnumsHaveStableIdentity() {
        assertEquals(List.of(new Bool(true), new Bool(true), new Bool(false)), run("""
                global state: Mode = Mode.ACTIVE
                enum Mode { ACTIVE, OFF }
                enum Other { ACTIVE }
                class Object {
                    parent = nil
                    Object() { self.parent = self }
                }
                fn main() {
                    x = Object()
                    return x.parent is x, [state] is [Mode.ACTIVE], state is Other.ACTIVE
                }
                """));
    }

    @Test
    void rejectsAnnotationViolationsBeforeParallelAssignmentAndAtCallBoundaries() {
        var runtime = runtime("""
                global first = 1
                global second: number = 2
                fn main() { first, second = 3, "wrong" }
                fn inspect() { return first, second }
                """);
        assertEquals(Code.TYPE_MISMATCH, runtime.invoke("main", List.of()).issue().orElseThrow().diagnostic().code());
        assertEquals(List.of(new Num(1), new Num(2)), runtime.invoke("inspect", List.of()).returns().values());
        assertEquals(Code.TYPE_MISMATCH, fault("fn f(x: bool) {}; fn main() { f(1) }"));
        assertEquals(Code.TYPE_MISMATCH, fault("class C { x: number = false }; fn main() { C() }"));
        assertEquals(Code.TYPE_MISMATCH, fault("class C { x: number = 1 }; fn main() { c = C(); c.x = nil }"));
        assertEquals(Code.TYPE_MISMATCH, fault("fn main() { x: number = 0; x = nil }"));
    }

    @Test
    void reportsConstructorAndMemberErrors() {
        assertEquals(Code.ARGUMENT_COUNT, fault("class C { C(x) {} }; fn main() { C() }"));
        assertEquals(Code.MISSING_KEY, fault("class C { a = self.b; b = 2 }; fn main() { C() }"));
        assertEquals(Code.MISSING_KEY, fault("class C {}; fn main() { c = C(); c.bad() }"));
        assertEquals(Code.IMMUTABLE_VALUE, fault("enum E { A }; fn main() { E.A = 2 }"));
        for (String invalid : List.of("class C { C() {}; C(x) {} }", "enum E { A, A }",
                "class C { x = 1; fn x() {} }", "class C { C() { return 1 } }")) {
            assertFalse(analysis(invalid).diagnostics().isEmpty());
        }
    }

    private static List<Value> run(String source) {
        var result = runtime(source).invoke("main", List.of());
        assertTrue(result.issue().isEmpty(), result.issue()::toString);
        return result.returns().values();
    }

    private static Code fault(String source) {
        return runtime(source).invoke("main", List.of()).issue().orElseThrow().diagnostic().code();
    }

    private static Interpreter runtime(String source) {
        var compiled = analysis(source);
        assertTrue(compiled.diagnostics().isEmpty(), compiled.diagnostics()::toString);
        return new Interpreter(compiled.program().orElseThrow(), ExecutionLimits.DEFAULT, ignored -> {});
    }

    private static dev.wiktorfila.filasignal.core.analysis.AnalysisResult analysis(String source) {
        return Resolver.resolve(Parser.parse(new SourceText("objects", "objects.fsig", source)));
    }
}
