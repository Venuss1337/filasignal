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

class CollectionsTest {
    @Test
    void listsSupportAliasingNestedMutationAndIterationSnapshots() {
        assertEquals(List.of(new Num(6), new Num(0), new Bool(true), new Num(1)), values("""
                fn main() {
                    a = [1, 3]
                    b = [a]
                    b[0].insert(1, 2)
                    a.append("tail")
                    removed = a.remove(3)
                    sum = 0
                    for value in a { sum += value; a.clear() }
                    a.append(1)
                    return sum, b[0].length - 1, removed is "tail", a[0]
                }
                """));
    }

    @Test
    void listsAndTuplesUnpackExactlyAndTuplesRemainImmutable() {
        assertEquals(List.of(new Num(2), new Num(1), new Bool(true)), values("""
                fn pair() { return (1, 2) }
                fn main() {
                    a, b = (pair())
                    a, b = [b, a]
                    total = 0
                    for item in (a,) { total += item }
                    return total, b, (a,) is (2,)
                }
                """));
        assertEquals(Code.IMMUTABLE_VALUE, fault("fn main() { t = (1,); t[0] = 0 }"));
        assertEquals(Code.INDEX_OUT_OF_BOUNDS, fault("fn main() { return [1][0.5] }"));
        assertEquals(Code.RETURN_ARITY, fault("fn main() { a,b = [1] }"));
    }

    @Test
    void dictionariesUseStringKeysAndInsertionOrder() {
        assertEquals(List.of(new Text("ba"), new Num(1), new Num(2), new Num(3), new Bool(true)), values("""
                fn main() {
                    d = {b: 2, a: 1}
                    a, b = d
                    keys = ""
                    for key in d { keys += key }
                    d.nested = {"with space": 3}
                    nested = d["nested"]["with space"]
                    return keys, a, b, nested, d.remove("b") is 2
                }
                """));
        assertEquals(Code.TYPE_MISMATCH, fault("fn main() { return {}[0] }"));
        assertEquals(Code.MISSING_KEY, fault("fn main() { return {}.missing }"));
    }

    @Test
    void collectionEqualityHandlesRecursiveReferencesAndDictOrder() {
        assertEquals(List.of(new Bool(true), new Bool(true), new Bool(true), new Bool(false)), values("""
                fn main() {
                    a = []
                    b = []
                    a.append(a)
                    b.append(b)
                    before = a is b
                    b.append(nil)
                    return before, a is not b, {a: 1, b: 2} is {b: 2, a: 1}, [1] is (1,)
                }
                """));
    }

    @Test
    void invalidSecondTargetDoesNotWriteFirstTarget() {
        Interpreter runtime = runtime("""
                global a = 1
                global box = [2]
                fn main() { a, box[4] = 3, 4 }
                fn inspect() { return a, box[0] }
                """);
        assertEquals(Code.INDEX_OUT_OF_BOUNDS,
                runtime.invoke("main", List.of()).issue().orElseThrow().diagnostic().code());
        assertEquals(List.of(new Num(1), new Num(2)), runtime.invoke("inspect", List.of()).returns().values());
    }

    @Test
    void enforcesCollectionLimitsWithSourceDiagnostics() {
        Interpreter runtime = runtime("""
                fn main() {
                    a = []
                    while true { a.append(0) }
                }
                """);
        RuntimeIssue issue = runtime.invoke("main", List.of()).issue().orElseThrow();
        assertEquals(Code.COLLECTION_LIMIT, issue.diagnostic().code());
        assertEquals("a.append(0)", issue.diagnostic().span().text());
    }

    @Test
    void jsonRoundTripsNativeValuesAndReportsMalformedOrCyclicData() {
        assertEquals(List.of(new Bool(true), new Bool(true), new Bool(true), new Bool(true), new Bool(true)), values("""
                fn main() {
                    source = {nilKey: nil, arr: [true, false, -1.25, "🚀"]}
                    text, err = json.stringify(source)
                    again, parseErr = json.parse(text)
                    invalid, bad = json.parse("[1,]")
                    list = []
                    list.append(list)
                    missing, cycle = json.stringify(list)
                    tuple, unsupported = json.stringify((1,))
                    return again is source, err is nil and parseErr is nil, bad.code is "json.invalid",
                        cycle.code is "json.invalid", unsupported.code is "json.invalid"
                }
                """));
    }

    @Test
    void jsonChecksUnicodeDuplicateKeysLimitsAndNumericForm() {
        assertEquals(new Text("🚀"), JsonCodec.parse("\"\\ud83d\\ude80\""));
        for (String text : List.of("01", "1e309", "1.", "1e", "{\"a\":1,\"a\":2}", "\"\\ud800\"", "[true,]",
                "[".repeat(70) + "0" + "]".repeat(70), " ".repeat(65_537))) {
            assertThrows(JsonCodec.Invalid.class, () -> JsonCodec.parse(text));
        }
    }

    private static Interpreter runtime(String source) {
        var result = Resolver.resolve(Parser.parse(new SourceText("collections", "collections.fsig", source)));
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics()::toString);
        return new Interpreter(result.program().orElseThrow(), new ExecutionLimits(64, 20_000_000), ignored -> {});
    }

    private static List<Value> values(String source) {
        var result = runtime(source).invoke("main", List.of());
        assertTrue(result.issue().isEmpty(), result.issue()::toString);
        return result.returns().values();
    }

    private static Code fault(String source) {
        return runtime(source).invoke("main", List.of()).issue().orElseThrow().diagnostic().code();
    }
}
