package dev.wiktorfila.filasignal.core.parser;

import dev.wiktorfila.filasignal.core.ast.Ast.*;
import dev.wiktorfila.filasignal.core.ast.AstPrinter;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.source.SourcePosition;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    @Test
    void respectsOperatorPrecedenceAndLeftAssociativity() {
        Expression expression = value("not a + b * -c - d is not e and f or g or h");
        Binary last = binary(expression, BinaryOperator.OR);
        assertEquals("h", assertInstanceOf(Name.class, last.right()).text());
        Binary firstOr = binary(last.left(), BinaryOperator.OR);
        Binary and = binary(firstOr.left(), BinaryOperator.AND);
        Unary not = assertInstanceOf(Unary.class, and.left());
        assertEquals(UnaryOperator.NOT, not.operator());
        Binary comparison = binary(not.operand(), BinaryOperator.IS_NOT);
        Binary difference = binary(comparison.left(), BinaryOperator.SUBTRACT);
        Binary addition = binary(difference.left(), BinaryOperator.ADD);
        Binary multiply = binary(addition.right(), BinaryOperator.MULTIPLY);
        assertEquals(UnaryOperator.MINUS, assertInstanceOf(Unary.class, multiply.right()).operator());
        Binary remainder = binary(value("+a / b % c"), BinaryOperator.REMAINDER);
        assertEquals(UnaryOperator.PLUS, assertInstanceOf(Unary.class,
                binary(remainder.left(), BinaryOperator.DIVIDE).left()).operator());
        binary(value("a < b"), BinaryOperator.LESS);
        binary(value("a <= b"), BinaryOperator.LESS_EQUAL);
        binary(value("a > b"), BinaryOperator.GREATER);
        binary(value("a >= b"), BinaryOperator.GREATER_EQUAL);
        binary(value("a is b"), BinaryOperator.IS);
        Unary doubleNot = assertInstanceOf(Unary.class, value("not not a"));
        assertEquals(UnaryOperator.NOT, doubleNot.operator());
        assertEquals(UnaryOperator.NOT, assertInstanceOf(Unary.class, doubleNot.operand()).operator());
        assertEquals(List.of(Code.CHAINED_COMPARISON), codes(parse("global x = a < b is c")));
        assertInstanceOf(Group.class, binary(value("(a < b) is c"), BinaryOperator.IS).left());
    }

    @Test
    void parsesCallsConstructionMemberChainsAndIndexingWithoutResolvingNames() {
        Call outer = assertInstanceOf(Call.class, value("Motor(3).start()(input[0]).action()"));
        Member action = assertInstanceOf(Member.class, outer.callee());
        assertEquals("action", action.name().text());
        Call invoke = assertInstanceOf(Call.class, action.receiver());
        assertInstanceOf(Index.class, invoke.arguments().getFirst());
        Call start = assertInstanceOf(Call.class, invoke.callee());
        Member member = assertInstanceOf(Member.class, start.callee());
        Call construct = assertInstanceOf(Call.class, member.receiver());
        assertEquals("Motor", assertInstanceOf(Name.class, construct.callee()).text());
        assertEquals("3", assertInstanceOf(NumberLiteral.class, construct.arguments().getFirst()).spelling());
        assertInstanceOf(CallStatement.class, body("unknown()[0].start()").getFirst());
    }

    @Test
    void preservesAssignmentShapesAndEveryPostfixOrCompoundOperator() {
        List<Statement> statements = body("""
                a, self.items[0] = get()
                a, b = (get())
                a, b = a, b
                a += 1; a -= 1; a *= 1; a /= 1; a %= 1
                get().member++; a--
                x: number = 2
                const done = false
                """);
        Assignment direct = assertInstanceOf(Assignment.class, statements.getFirst());
        assertEquals(2, direct.targets().size());
        assertInstanceOf(Index.class, direct.targets().getLast());
        assertInstanceOf(Call.class, direct.values().getFirst());
        assertInstanceOf(Group.class, ((Assignment) statements.get(1)).values().getFirst());
        assertEquals(2, ((Assignment) statements.get(2)).values().size());
        assertEquals(List.of(AssignmentOperator.ADD, AssignmentOperator.SUBTRACT, AssignmentOperator.MULTIPLY,
                AssignmentOperator.DIVIDE, AssignmentOperator.REMAINDER),
                statements.subList(3, 8).stream().map(st -> ((Assignment) st).operator()).toList());
        Update update = assertInstanceOf(Update.class, statements.get(8));
        assertInstanceOf(Member.class, update.target());
        assertEquals(UpdateOperator.INCREMENT, update.operator());
        assertEquals(UpdateOperator.DECREMENT, ((Update) statements.get(9)).operator());
        BindingDeclaration annotated = assertInstanceOf(BindingDeclaration.class, statements.get(10));
        assertEquals("number", annotated.binding().type().text());
        assertEquals(BindingKind.ANNOTATED, annotated.kind());
        assertEquals(BindingKind.CONSTANT, ((BindingDeclaration) statements.get(11)).kind());
    }

    @Test
    void rejectsInvalidTargetsAndExpressionOnlyStatementsWithRecovery() {
        ParseResult result = parse("""
                fn f() {
                    (a) = 1
                    f() = 1
                    a + b = 1
                    self = 1
                    1++
                    a, b--
                    --a
                    a = b++
                    a + b
                    accept()
                }
                """);
        assertEquals(List.of(Code.INVALID_ASSIGNMENT_TARGET, Code.INVALID_ASSIGNMENT_TARGET,
                Code.INVALID_ASSIGNMENT_TARGET, Code.INVALID_ASSIGNMENT_TARGET, Code.INVALID_ASSIGNMENT_TARGET,
                Code.INVALID_ASSIGNMENT_ARITY, Code.UNSUPPORTED_UPDATE_POSITION, Code.UNSUPPORTED_UPDATE_POSITION,
                Code.EXPECTED_CALL_STATEMENT), codes(result));
        assertEquals(List.of("(a)", "f()", "a + b", "self", "1", "--", "--", "++", "a + b"),
                result.diagnostics().stream().map(issue -> issue.span().text()).toList());
        Function function = (Function) result.program().declarations().getFirst();
        assertEquals("accept()", function.body().statements().getFirst().span().text());
    }

    @Test
    void distinguishesGroupingTuplesListsAndDictionaryKeys() {
        assertInstanceOf(Group.class, value("(false)"));
        assertEquals(0, assertInstanceOf(Tuple.class, value("()")).elements().size());
        Tuple singleton = assertInstanceOf(Tuple.class, value("(nil,)"));
        assertInstanceOf(Nil.class, singleton.elements().getFirst());
        assertEquals(2, assertInstanceOf(Tuple.class, value("(1, 2,)")).elements().size());
        assertTrue(assertInstanceOf(ListLiteral.class, value("[]")).elements().isEmpty());
        assertTrue(assertInstanceOf(Dictionary.class, value("{}")).entries().isEmpty());
        Dictionary dictionary = assertInstanceOf(Dictionary.class, value("""
                {
                  key:
                    [
                       1,
                       (false,),
                    ],
                  "some:key": true,
                }
                """));
        DictionaryEntry entry = dictionary.entries().getLast();
        assertEquals("some:key", entry.key());
        assertEquals("\"some:key\"", entry.keySpan().text());
        assertEquals("\"some:key\": true", entry.span().text());
        assertTrue(assertInstanceOf(BooleanLiteral.class, entry.value()).value());
        assertEquals("key", dictionary.entries().getFirst().key());
        assertEquals(2, assertInstanceOf(ListLiteral.class, dictionary.entries().getFirst().value()).elements().size());
    }

    @Test
    void parsesBlockAndDictionaryBracesAndAttachesElseToInnermostIf() {
        If outer = assertInstanceOf(If.class, body("""
                if {active: true}.active {
                    if flag { call() }
                    /* newline
                    */ else if other { call() }
                    else { call() }
                }
                else { call() }
                """).getFirst());
        Member member = assertInstanceOf(Member.class, outer.branches().getFirst().condition());
        assertInstanceOf(Dictionary.class, member.receiver());
        If inner = assertInstanceOf(If.class, outer.branches().getFirst().body().statements().getFirst());
        assertEquals(2, inner.branches().size());
        assertNotNull(inner.fallback());
        assertNotNull(outer.fallback());
        assertFalse(parse("fn f() { if x {}; else {} }").diagnostics().isEmpty());
        assertFalse(parse("fn f() { if x {}\nelse\nif y {} }").diagnostics().isEmpty());
        assertFalse(parse("fn f() { if x\n{} }").diagnostics().isEmpty());
    }

    @Test
    void handlesReturnArityAndDocumentedContinuations() {
        List<Statement> statements = body("""
                return /* split
                */
                f()
                return 1
                return (2, 3), // extends expression list
                    source(
                        4 +
                          5
                    )
                x =
                    a is
                    not
                    b
                y = [
                  receiver.
                    method(
                      1
                    )
                ]
                """);
        Return empty = (Return) statements.get(0);
        assertTrue(empty.values().isEmpty());
        assertEquals("return", empty.span().text());
        assertInstanceOf(CallStatement.class, statements.get(1));
        assertEquals(1, ((Return) statements.get(2)).values().size());
        Return multiple = (Return) statements.get(3);
        assertEquals(2, multiple.values().size());
        assertInstanceOf(Tuple.class, multiple.values().getFirst());
        binary(((Assignment) statements.get(4)).values().getFirst(), BinaryOperator.IS_NOT);
        assertEquals(6, statements.size());
        assertEquals(List.of(Code.EXPECTED_CALL_STATEMENT), codes(parse("fn f() { x = 1\n+2 }")));
        assertEquals(List.of(Code.EXPECTED_TOKEN), codes(parse("fn f() { x = [1;\n2] }")));
        assertEquals(List.of(Code.EXPECTED_TOKEN), codes(parse("fn f()\n{}")));
    }

    @Test
    void parsesCompleteFixtureWithFunctionsClassesEnumsAndEvents() throws IOException {
        Program program = assertSuccess(fixture("program.fsig"));
        assertEquals(7, program.declarations().size());
        assertEquals(BindingKind.GLOBAL, ((BindingDeclaration) program.declarations().get(0)).kind());
        assertEquals(List.of("OFF", "ON"), ((EnumDeclaration) program.declarations().get(2))
                .members().stream().map(Name::text).toList());
        ClassDeclaration motor = (ClassDeclaration) program.declarations().get(3);
        assertEquals(BindingKind.FIELD, ((BindingDeclaration) motor.members().getFirst()).kind());
        Constructor constructor = assertInstanceOf(Constructor.class, motor.members().get(1));
        assertEquals("Motor", constructor.name().text());
        assertEquals("number", constructor.parameters().getFirst().type().text());
        assertInstanceOf(Function.class, motor.members().getLast());
        Function demo = (Function) program.declarations().get(4);
        If conditional = assertInstanceOf(If.class, demo.body().statements().get(3));
        While loop = assertInstanceOf(While.class, conditional.branches().getLast().body().statements().getFirst());
        assertInstanceOf(Break.class, loop.body().statements().getLast());
        For forLoop = assertInstanceOf(For.class, conditional.fallback().statements().getFirst());
        assertInstanceOf(Continue.class, ((If) forLoop.body().statements().getFirst())
                .branches().getFirst().body().statements().getFirst());
        EventDeclaration event = (EventDeclaration) program.declarations().get(5);
        assertEquals(List.of("redstone", "rising"), event.name().stream().map(Name::text).toList());
        assertEquals(2, event.arguments().size());
        assertTrue(((EventDeclaration) program.declarations().getLast()).arguments().isEmpty());
    }

    @Test
    void reportsMultipleDeterministicErrorsAndKeepsLaterDeclarations() throws IOException {
        ParseResult result = fixture("errors.fsig");
        assertEquals(List.of(Code.EXPECTED_TOKEN, Code.INVALID_ASSIGNMENT_TARGET, Code.UNSUPPORTED_UPDATE_POSITION,
                Code.CHAINED_COMPARISON, Code.INVALID_ASSIGNMENT_ARITY, Code.INVALID_ASSIGNMENT_ARITY,
                Code.EXPECTED_EXPRESSION, Code.EXPECTED_EXPRESSION, Code.INVALID_CONSTRUCTOR_NAME), codes(result));
        assertEquals("KEEP", ((BindingDeclaration) result.program().declarations().getLast()).binding().name().text());
        Function broken = (Function) result.program().declarations().getFirst();
        assertEquals("valid()", broken.body().statements().getLast().span().text());
        ParseResult again = fixture("errors.fsig");
        assertEquals(result.diagnostics().stream().map(ParserTest::describe).toList(),
                again.diagnostics().stream().map(ParserTest::describe).toList());
        assertEquals(AstPrinter.print(result.program()), AstPrinter.print(again.program()));
    }

    @Test
    void reportsPreciseCrLfAndUnicodePositions() throws IOException {
        String source = "fn f() {\r\n  call(\"é😀\", missing)\r\n}\r\n";
        SourceText text = SourceText.fromUtf8("test", "test.fsig", source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Program program = assertSuccess(Parser.parse(text));
        Function function = (Function) program.declarations().getFirst();
        assertEquals("fn f() {\r\n  call(\"é😀\", missing)\r\n}", function.span().text());
        assertEquals(new SourcePosition(4, 1), program.span().end());
        Call call = ((CallStatement) function.body().statements().getFirst()).call();
        assertEquals(new SourcePosition(2, 3), call.span().start());
        assertEquals(new SourcePosition(2, 14), call.arguments().getLast().span().start());
        assertEquals(source.indexOf("missing"), call.arguments().getLast().span().startOffset());
        Diagnostic error = parse("fn f() {\r\n  call(\"é😀\", )\r\n}").diagnostics().getFirst();
        assertEquals(new SourcePosition(2, 14), error.span().start());
        assertEquals(")", error.span().text());
    }

    @Test
    void recoversAtScopeBoundariesAndCombinesLexicalAndSyntaxDiagnostics() {
        ParseResult result = parse("fn broken() {\n @\n 1 + )\nfn next() { unknown() }\nconst keep = 1");
        assertEquals(List.of(Code.INVALID_CHARACTER, Code.EXPECTED_EXPRESSION, Code.EXPECTED_TOKEN), codes(result));
        assertEquals("next", ((Function) result.program().declarations().getFirst()).name().text());
        assertEquals(2, result.program().declarations().size());
        assertEquals(List.of(Code.EXPECTED_DECLARATION), codes(parse("x = 2\nconst good = 0")));
        assertEquals(List.of(Code.EXPECTED_SEPARATOR), codes(parse("global x = 1 const y = 2")));
        assertEquals(List.of(Code.EXPECTED_TOKEN), codes(parse("enum Empty {}")));
        assertEquals(List.of(Code.EXPECTED_TOKEN), codes(parse("fn f(p,) {}")));
    }

    @Test
    void recoversAfterBalancedAndUnclosedDelimitersWithoutLosingTheNextStatement() {
        ParseResult result = parse("fn f() {\n bad(1;\n 2)\n kept()\n bad(1\n saved()\n}");
        assertEquals(List.of(Code.EXPECTED_TOKEN, Code.EXPECTED_TOKEN), codes(result));
        Function function = (Function) result.program().declarations().getFirst();
        assertEquals(List.of("kept()", "saved()"),
                function.body().statements().stream().map(statement -> statement.span().text()).toList());
    }

    @Test
    void pointsUnexpectedEofAtAnEmptySpan() {
        for (String source : List.of("fn", "global x =", "fn f() {", "enum E { A,", "on foo(", "const x = [1",
                "const x = (2,", "class C { C() { return", "const x = {x:")) {
            ParseResult result = parse(source);
            Diagnostic diagnostic = result.diagnostics().getFirst();
            assertEquals(source.length(), diagnostic.span().startOffset(), source);
            assertEquals("", diagnostic.span().text(), source);
        }
        assertSuccess(parse(";\n;\n"));
    }

    @Test
    void limitsDeepRecursiveSyntaxButAcceptsLongFlatStreamsAndPrintsLongChains() {
        for (String text : List.of(
                "global x = " + "(".repeat(10_000) + "1" + ")".repeat(10_000),
                "global x = " + "not ".repeat(10_000) + "true",
                "fn f() {" + "if true {".repeat(10_000) + "}".repeat(10_001))) {
            ParseResult result = parse(text + "\nconst after = 1");
            assertTrue(codes(result).contains(Code.NESTING_LIMIT));
            assertEquals("after", ((BindingDeclaration) result.program().declarations().getLast()).binding().name().text());
        }
        Function flat = (Function) assertSuccess(parse("fn f() {\n" + "f();\n".repeat(50_000) + "}"))
                .declarations().getFirst();
        assertEquals(50_000, flat.body().statements().size());
        Expression chain = value("1" + " + 1".repeat(8_000) + ".x".repeat(8_000));
        String printed = AstPrinter.print(chain);
        assertEquals(8_000, printed.lines().filter(line -> line.stripLeading().equals("(Binary ADD")).count());
        assertEquals(8_000, printed.lines().filter(line -> line.stripLeading().equals("(Member")).count());
    }

    @Test
    void terminatesOnSeededMalformedInputs() {
        String[] fragments = {"fn", "on", "if", "else", "return", "const", "x", "=", "(", ")", "[", "]",
                "{", "}", ",", ".", "++", "+", "1", "@", "\r\n", ";", "\"é😀\""};
        Random random = new Random(2026);
        for (int sample = 0; sample < 500; sample++) {
            StringBuilder source = new StringBuilder("fn f() {\n");
            for (int part = 0; part < 96; part++) {
                source.append(fragments[random.nextInt(fragments.length)]).append(' ');
            }
            ParseResult result = parse(source.toString());
            assertFalse(result.diagnostics().isEmpty());
        }
    }

    @Test
    void printsADeterministicReadableTree() {
        Program program = assertSuccess(parse("fn f(x) { return x, \"a\\n\" }"));
        assertEquals("""
                (Program
                  (Function
                    (Name f)
                    (Binding
                      (Name x)
                    )
                    (Block
                      (Return
                        (Name x)
                        (String "a\\n")
                      )
                    )
                  )
                )
                """, AstPrinter.print(program));
    }

    private static ParseResult parse(String text) {
        return Parser.parse(new SourceText("test", "test.fsig", text));
    }

    private static Program assertSuccess(ParseResult result) {
        assertEquals(List.of(), result.diagnostics());
        return result.program();
    }

    private static List<Statement> body(String text) {
        return ((Function) assertSuccess(parse("fn test() {\n" + text + "\n}"))
                .declarations().getFirst()).body().statements();
    }

    private static Expression value(String expression) {
        return ((BindingDeclaration) assertSuccess(parse("global test = " + expression))
                .declarations().getFirst()).initializer();
    }

    private static Binary binary(Expression expression, BinaryOperator operator) {
        Binary binary = assertInstanceOf(Binary.class, expression);
        assertEquals(operator, binary.operator());
        return binary;
    }

    private static List<Code> codes(ParseResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).toList();
    }

    private static String describe(Diagnostic diagnostic) {
        return "%s %s %s %s".formatted(diagnostic.code(), diagnostic.message(),
                diagnostic.span().startOffset(), diagnostic.span().endOffset());
    }

    private static ParseResult fixture(String filename) throws IOException {
        try (InputStream input = ParserTest.class.getResourceAsStream("/parser/" + filename)) {
            assertNotNull(input);
            return Parser.parse(SourceText.fromUtf8("fixture:" + filename, filename, input.readAllBytes()));
        }
    }
}
