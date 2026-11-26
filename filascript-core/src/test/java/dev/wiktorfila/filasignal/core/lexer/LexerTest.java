package dev.wiktorfila.filasignal.core.lexer;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.source.SourcePosition;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static dev.wiktorfila.filasignal.core.lexer.TokenType.*;
import static org.junit.jupiter.api.Assertions.*;

class LexerTest {
    @Test
    void matchesEveryKeywordAndKeepsPrefixedOrDifferentlyCasedNames() {
        String keywords = "global const fn class enum on if else while for in return break continue "
                + "true false nil is and or not self";
        assertTypes(lex(keywords + " let local On on2 _on globalish is_not notepad"),
                GLOBAL, CONST, FN, CLASS, ENUM, ON, IF, ELSE, WHILE, FOR, IN, RETURN, BREAK,
                CONTINUE, TRUE, FALSE, NIL, IS, AND, OR, NOT, SELF,
                IDENTIFIER, IDENTIFIER, IDENTIFIER, IDENTIFIER, IDENTIFIER, IDENTIFIER,
                IDENTIFIER, IDENTIFIER, EOF);
    }

    @Test
    void maximallyMatchesOperatorsAndPunctuation() {
        assertTypes(lex("+-*/% = += -= *= /= %= ++-- < <= > >= {}()[].,:;"),
                PLUS, MINUS, STAR, SLASH, PERCENT, ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN,
                SLASH_ASSIGN, PERCENT_ASSIGN, INCREMENT, DECREMENT, LESS, LESS_EQUAL, GREATER,
                GREATER_EQUAL, LEFT_BRACE, RIGHT_BRACE, LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET,
                RIGHT_BRACKET, DOT, COMMA, COLON, SEMICOLON, EOF);
        assertTypes(lex("a is not b and not c or d"),
                IDENTIFIER, IS, NOT, IDENTIFIER, AND, NOT, IDENTIFIER, OR, IDENTIFIER, EOF);
    }

    @Test
    void scansDecimalNumbersAndLeavesSignsAndMemberDotsSeparate() {
        LexResult result = lex("0 007 0.25 2e7 4E-3 6.2e+8 -2 +3 1.field 1e-9999 5e-324");
        assertTypes(result, NUMBER, NUMBER, NUMBER, NUMBER, NUMBER, NUMBER, MINUS, NUMBER, PLUS,
                NUMBER, NUMBER, DOT, IDENTIFIER, NUMBER, NUMBER, EOF);
        assertEquals(List.of("0", "007", "0.25", "2e7", "4E-3", "6.2e+8", "-", "2", "+", "3",
                "1", ".", "field", "1e-9999", "5e-324", ""), lexemes(result));
        assertFaults(lex("1e 2E- 3e+"), List.of(Code.INVALID_NUMBER, Code.INVALID_NUMBER, Code.INVALID_NUMBER),
                List.of("1e", "2E-", "3e+"));
        assertFaults(lex("1e309"), List.of(Code.NUMBER_OUT_OF_RANGE), List.of("1e309"));
    }

    @Test
    void decodesStringsAndKeepsOriginalTextAndUnicodeSpans() {
        LexResult result = lex("\"é😀\\n\\r\\t\\\"\\\\\\u{1F600}\\u{10ffff}\" tail");
        assertTypes(result, STRING, IDENTIFIER, EOF);
        Token string = result.tokens().getFirst();
        assertEquals("é😀\n\r\t\"\\😀" + Character.toString(0x10ffff), string.stringValue());
        assertTrue(string.lexeme().contains("\\u{1F600}"));
        assertEquals(new SourcePosition(1, string.lexeme().codePointCount(0, string.lexeme().length()) + 1),
                string.span().end());
        assertNull(result.tokens().get(1).stringValue());
        assertEquals("", lex("\"\"").tokens().getFirst().stringValue());
    }

    @Test
    void reportsInvalidEscapesAndStringControlsAndRecoversAtClosingQuote() {
        LexResult result = lex("\"\\q\\😀\\u{D800}\\u{110000}\\u{}\\u{1234567}\\u1234\u0001\" next");
        assertFaults(result, List.of(Code.INVALID_ESCAPE, Code.INVALID_ESCAPE, Code.INVALID_ESCAPE,
                        Code.INVALID_ESCAPE, Code.INVALID_ESCAPE, Code.INVALID_ESCAPE, Code.INVALID_ESCAPE,
                        Code.INVALID_STRING_CHARACTER),
                List.of("\\q", "\\😀", "\\u{D800}", "\\u{110000}", "\\u{}", "\\u{1234567}", "\\u", "\u0001"));
        assertEquals(List.of(ERROR, IDENTIFIER, EOF), types(result));
    }

    @Test
    void recoversFromUnterminatedStringsAndHandlesUnexpectedEof() {
        LexResult result = lex("\"missing\\\r\nnext\n\"eof\\");
        assertFaults(result, List.of(Code.UNTERMINATED_STRING, Code.UNTERMINATED_STRING),
                List.of("\"missing\\", "\"eof\\"));
        assertEquals(List.of(ERROR, NEWLINE, IDENTIFIER, NEWLINE, ERROR, EOF), types(result));
        assertFaults(lex("/*\n*"), List.of(Code.UNTERMINATED_COMMENT), List.of("/*\n*"));
        assertTypes(lex("// comment"), EOF);
        assertTypes(lex(""), EOF);
        assertTypes(lex("/"), SLASH, EOF);
        assertTypes(lex("("), LEFT_PAREN, EOF); // The parser checks delimiter balance.
    }

    @Test
    void keepsAllPhysicalNewlinesForContextualParsing() {
        LexResult result = lex("return//ignore\r\n(a +\n b)/* outer /* inner\r\n */\n"
                + "{x: [\n1,\n2]} ;\nelse");
        assertTypes(result, RETURN, NEWLINE, LEFT_PAREN, IDENTIFIER, PLUS, NEWLINE, IDENTIFIER,
                RIGHT_PAREN, NEWLINE, NEWLINE, LEFT_BRACE, IDENTIFIER, COLON, LEFT_BRACKET,
                NEWLINE, NUMBER, COMMA, NEWLINE, NUMBER, RIGHT_BRACKET, RIGHT_BRACE,
                SEMICOLON, NEWLINE, ELSE, EOF);
        Token crlf = result.tokens().get(1);
        assertEquals("\r\n", crlf.lexeme());
        assertEquals(new SourcePosition(1, 15), crlf.span().start());
        assertEquals(new SourcePosition(2, 1), crlf.span().end());
        assertEquals(new SourcePosition(8, 5), result.tokens().getLast().span().start());
    }

    @Test
    void emitsPreciseErrorsForInvalidCharactersOperatorsAndLoneCr() {
        LexResult result = lex("é😀 == != ! @\rnext /*\r*/");
        assertFaults(result, List.of(Code.INVALID_CHARACTER, Code.INVALID_CHARACTER,
                        Code.UNSUPPORTED_OPERATOR, Code.UNSUPPORTED_OPERATOR, Code.UNSUPPORTED_OPERATOR,
                        Code.INVALID_CHARACTER, Code.INVALID_LINE_ENDING, Code.INVALID_LINE_ENDING),
                List.of("é", "😀", "==", "!=", "!", "@", "\r", "\r"));
        assertEquals(1, result.tokens().get(1).span().startOffset());
        assertEquals(3, result.tokens().get(1).span().endOffset());
        assertEquals(new SourcePosition(1, 3), result.tokens().get(1).span().end());
    }

    @Test
    void lexesRepresentativeSourceFixtures() throws IOException {
        LexResult valid = fixture("controller.fsig");
        assertTrue(valid.diagnostics().isEmpty(), valid.diagnostics()::toString);
        assertTrue(types(valid).containsAll(List.of(GLOBAL, FN, ON, INCREMENT, ELSE, EOF)));
        assertTrue(valid.tokens().stream().anyMatch(token -> "Siła ⚡".equals(token.stringValue())));

        LexResult invalid = fixture("invalid.fsig");
        assertFaults(invalid, List.of(Code.INVALID_NUMBER, Code.INVALID_ESCAPE, Code.UNSUPPORTED_OPERATOR,
                        Code.UNTERMINATED_STRING, Code.UNTERMINATED_COMMENT),
                List.of("1e+", "\\u{D800}", "!=", "\"unfinished", "/* open\n"));
        assertEquals(EOF, invalid.tokens().getLast().type());
    }

    @Test
    void scansLargeAndPathologicalInputsIterativelyWithOrderedTokenSpans() {
        int count = 50_000;
        String text = "x+=1;\r\n".repeat(count) + "(".repeat(count) + "+++".repeat(count)
                + "/*" + "*".repeat(count) + "/" + " ".repeat(count);
        LexResult result = lex(text);
        assertEquals(List.of(), result.diagnostics());
        assertEquals(6 * count + (3 * count) / 2 + 1, result.tokens().size());
        int priorEnd = 0;
        for (Token token : result.tokens()) {
            assertTrue(token.span().startOffset() >= priorEnd);
            priorEnd = token.span().endOffset();
        }
        Token eof = result.tokens().getLast();
        assertEquals(text.length(), eof.span().startOffset());
        assertEquals(text.length(), eof.span().endOffset());

        String hugeNumber = "9".repeat(count);
        assertFaults(lex(hugeNumber), List.of(Code.NUMBER_OUT_OF_RANGE), List.of(hugeNumber));

        String invalidEscape = "\"\\u{" + "A".repeat(count) + "}\"";
        assertFaults(lex(invalidEscape), List.of(Code.INVALID_ESCAPE),
                List.of(invalidEscape.substring(1, invalidEscape.length() - 1)));
    }

    @Test
    void maintainsSpanInvariantsAcrossMixedValidAndMalformedInput() {
        List<String> alphabet = List.of("x", "2", "e", "\"", "\\", "{", "}", "/", "*", "+", "\t",
                "\r", "\n", "é", "😀", "\uFEFF");
        Random random = new Random(42);
        for (int sample = 0; sample < 200; sample++) {
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < 256; i++) {
                input.append(alphabet.get(random.nextInt(alphabet.size())));
            }
            LexResult result = lex(input.toString());
            int previousEnd = 0;
            for (Token token : result.tokens()) {
                assertTrue(token.span().startOffset() >= previousEnd);
                assertEquals(token.type() == EOF, token.lexeme().isEmpty());
                previousEnd = token.span().endOffset();
            }
            Token eof = result.tokens().getLast();
            assertEquals(EOF, eof.type());
            assertEquals(eof.span().source().text().length(), eof.span().endOffset());
        }
    }

    private static LexResult fixture(String filename) throws IOException {
        try (InputStream input = LexerTest.class.getResourceAsStream("/lexer/" + filename)) {
            assertNotNull(input);
            return Lexer.lex(SourceText.fromUtf8("fixture:" + filename, filename, input.readAllBytes()));
        }
    }

    private static LexResult lex(String text) {
        return Lexer.lex(new SourceText("test", "test.fsig", text));
    }

    private static List<TokenType> types(LexResult result) {
        return result.tokens().stream().map(Token::type).toList();
    }

    private static List<String> lexemes(LexResult result) {
        return result.tokens().stream().map(Token::lexeme).toList();
    }

    private static void assertTypes(LexResult result, TokenType... expected) {
        assertEquals(List.of(), result.diagnostics());
        assertEquals(Arrays.asList(expected), types(result));
    }

    private static void assertFaults(LexResult result, List<Code> codes, List<String> spans) {
        assertEquals(codes, result.diagnostics().stream().map(Diagnostic::code).toList());
        assertEquals(spans, result.diagnostics().stream().map(fault -> fault.span().text()).toList());
    }
}
