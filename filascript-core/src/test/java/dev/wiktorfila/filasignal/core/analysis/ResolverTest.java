package dev.wiktorfila.filasignal.core.analysis;

import dev.wiktorfila.filasignal.core.ast.Ast.*;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.parser.ParseResult;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResolverTest {
    @Test
    void recordsLexicalBindingIdentityWithoutChangingSyntax() {
        ParseResult parsed = parse("""
                fn main() {
                    x = 1
                    if true {
                        x = 2
                        const x = x
                        print(x)
                    }
                    return x
                }
                """);
        ResolvedProgram resolved = Resolver.resolve(parsed).program().orElseThrow();
        assertSame(parsed.program(), resolved.syntax());
        Function function = (Function) parsed.program().declarations().getFirst();
        List<Statement> body = function.body().statements();
        Name declaration = (Name) ((Assignment) body.getFirst()).targets().getFirst();
        If conditional = (If) body.get(1);
        List<Statement> branch = conditional.branches().getFirst().body().statements();
        Name update = (Name) ((Assignment) branch.getFirst()).targets().getFirst();
        BindingDeclaration shadow = (BindingDeclaration) branch.get(1);
        Name readShadow = (Name) ((CallStatement) branch.getLast()).call().arguments().getFirst();
        Name returned = (Name) ((Return) body.getLast()).values().getFirst();

        Symbol outer = resolved.bindingOf(declaration);
        Symbol inner = resolved.bindingOf(shadow.binding().name());
        assertSame(declaration, outer.declaration());
        assertSame(outer, resolved.bindingOf(update));
        assertSame(outer, resolved.bindingOf((Name) shadow.initializer()));
        assertSame(inner, resolved.bindingOf(readShadow));
        assertSame(outer, resolved.bindingOf(returned));
        assertNotSame(outer, inner);
        assertFalse(inner.mutable());
    }

    @Test
    void allRightHandExpressionsResolveBeforeImplicitTargetsAreDeclared() {
        AnalysisResult result = resolve("""
                fn main() {
                    a, b = 1, a
                    b, c = b, 2
                    if true { hidden = 1 }
                    print(hidden)
                }
                """);
        assertEquals(List.of(Code.UNKNOWN_BINDING, Code.UNKNOWN_BINDING), codes(result));
        assertEquals(List.of("a", "hidden"), spans(result));
        assertTrue(result.program().isEmpty());
    }

    @Test
    void checksUnknownUpdatesConstantWritesAndLoopPlacement() {
        AnalysisResult result = resolve("""
                const k = 1
                fn main() {
                    k = 2
                    x++
                    y += 3
                    break
                    continue
                    while true { if true { break } }
                }
                """);
        assertEquals(List.of(Code.IMMUTABLE_BINDING, Code.UNKNOWN_BINDING, Code.UNKNOWN_BINDING,
                Code.INVALID_CONTROL_FLOW, Code.INVALID_CONTROL_FLOW), codes(result));
    }

    @Test
    void detectsDuplicateNamesWithinEachScope() {
        AnalysisResult result = resolve("""
                global value = 0
                fn value() {}
                fn parameters(x, x) {}
                fn locals(p) {
                    const p = 3
                    a = 1
                    const a = 2
                }
                """);
        assertEquals(List.of(Code.DUPLICATE_BINDING, Code.DUPLICATE_BINDING, Code.DUPLICATE_BINDING,
                Code.DUPLICATE_BINDING), codes(result));
        assertEquals(List.of("value", "x", "p", "a"), spans(result));
    }

    @Test
    void sealsInitializerOrderAndRejectsCallsAndMutableDependencies() {
        assertTrue(resolve("const K = 2\nglobal value = K * 2\nfn f() { return value }").diagnostics().isEmpty());
        AnalysisResult result = resolve("""
                global x = later
                const later = 1
                global y = x
                global z = f()
                const rejected = print(1)
                fn f() { return x }
                """);
        assertEquals(List.of(Code.INVALID_INITIALIZER, Code.INVALID_INITIALIZER,
                Code.INVALID_INITIALIZER, Code.INVALID_INITIALIZER), codes(result));
        assertEquals(List.of("later", "x", "f()", "print(1)"), spans(result));
    }

    @Test
    void supportsForwardReferencesButRejectsFunctionValues() {
        AnalysisResult result = resolve("""
                fn main() {
                    later()
                    print(g)
                    saved = later
                }
                fn later() { return g }
                global g = 1
                """);
        assertEquals(List.of(Code.FUNCTION_AS_VALUE), codes(result));
        assertEquals(List.of("later"), spans(result));
    }

    @Test
    void syntaxDiagnosticsStopAnalysisAndGuardPartialPrograms() {
        for (String source : List.of("return", "fn main() { global x = 1 }", "fn main() { print(1)")) {
            ParseResult parsed = parse(source);
            assertFalse(parsed.diagnostics().isEmpty());
            AnalysisResult resolved = Resolver.resolve(parsed);
            assertTrue(resolved.program().isEmpty());
            assertEquals(parsed.diagnostics(), resolved.diagnostics());
        }
    }

    @Test
    void diagnosesDeferredConstructsAndBoundsLeftDeepTrees() {
        AnalysisResult result = resolve("""
                enum E { A }
                class C {}
                on startup {}
                fn main(p: number) {
                    list = []
                    tuple = (1,)
                    dict = {}
                    list[0] = 1
                    print(list[0])
                    print(self)
                }
                """);
        assertEquals(1, result.diagnostics().size());
        assertTrue(codes(result).stream().allMatch(code -> code == Code.UNSUPPORTED_FEATURE));
        String deep = "fn main() { return " + "1+".repeat(20_000) + "1 }";
        assertTrue(codes(resolve(deep)).contains(Code.ANALYSIS_DEPTH_LIMIT));
    }

    private static ParseResult parse(String text) {
        return Parser.parse(new SourceText("test", "test.fsig", text));
    }

    private static AnalysisResult resolve(String text) {
        return Resolver.resolve(parse(text));
    }

    private static List<Code> codes(AnalysisResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).toList();
    }

    private static List<String> spans(AnalysisResult result) {
        return result.diagnostics().stream().map(diagnostic -> diagnostic.span().text()).toList();
    }
}
