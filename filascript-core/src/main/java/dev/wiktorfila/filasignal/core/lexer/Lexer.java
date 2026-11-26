package dev.wiktorfila.filasignal.core.lexer;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.source.SourceSpan;
import dev.wiktorfila.filasignal.core.source.SourceText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.wiktorfila.filasignal.core.lexer.TokenType.*;
import static java.util.Map.entry;

public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            entry("global", GLOBAL), entry("const", CONST), entry("fn", FN),
            entry("class", CLASS), entry("enum", ENUM), entry("on", ON),
            entry("if", IF), entry("else", ELSE), entry("while", WHILE),
            entry("for", FOR), entry("in", IN), entry("return", RETURN),
            entry("break", BREAK), entry("continue", CONTINUE), entry("true", TRUE),
            entry("false", FALSE), entry("nil", NIL), entry("is", IS), entry("and", AND),
            entry("or", OR), entry("not", NOT), entry("self", SELF));

    private final SourceText source;
    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<SourceSpan> comments = new ArrayList<>();
    private int offset;

    private Lexer(SourceText source) {
        this.source = source;
        this.text = source.text();
    }

    /** Returns the vocabulary recognized as language keywords. */
    public static java.util.Set<String> keywords() { return KEYWORDS.keySet(); }

    public static LexResult lex(SourceText source) {
        return new Lexer(source).scan();
    }

    private LexResult scan() {
        while (hasInput()) {
            int start = offset;
            int character = text.codePointAt(offset);
            offset += Character.charCount(character);
            switch (character) {
                case ' ', '\t' -> { }
                case '\n' -> emit(NEWLINE, start);
                case '\r' -> carriageReturn(start);
                case '{' -> emit(LEFT_BRACE, start);
                case '}' -> emit(RIGHT_BRACE, start);
                case '(' -> emit(LEFT_PAREN, start);
                case ')' -> emit(RIGHT_PAREN, start);
                case '[' -> emit(LEFT_BRACKET, start);
                case ']' -> emit(RIGHT_BRACKET, start);
                case ',' -> emit(COMMA, start);
                case ':' -> emit(COLON, start);
                case '.' -> emit(DOT, start);
                case ';' -> emit(SEMICOLON, start);
                case '+' -> emit(take('+') ? INCREMENT : take('=') ? PLUS_ASSIGN : PLUS, start);
                case '-' -> emit(take('-') ? DECREMENT : take('=') ? MINUS_ASSIGN : MINUS, start);
                case '*' -> emit(take('=') ? STAR_ASSIGN : STAR, start);
                case '%' -> emit(take('=') ? PERCENT_ASSIGN : PERCENT, start);
                case '<' -> emit(take('=') ? LESS_EQUAL : LESS, start);
                case '>' -> emit(take('=') ? GREATER_EQUAL : GREATER, start);
                case '=' -> {
                    if (take('=')) {
                        error(Code.UNSUPPORTED_OPERATOR, "Use 'is' for equality", start);
                    } else {
                        emit(ASSIGN, start);
                    }
                }
                case '!' -> {
                    boolean inequality = take('=');
                    error(Code.UNSUPPORTED_OPERATOR, inequality ? "Use 'is not' for inequality"
                            : "Use 'not' for Boolean negation", start);
                }
                case '/' -> slash(start);
                case '"' -> string(start);
                default -> {
                    if (identifierStart(character)) {
                        identifier(start);
                    } else if (digit(character)) {
                        number(start);
                    } else {
                        error(Code.INVALID_CHARACTER, "Unexpected character U+%04X".formatted(character), start);
                    }
                }
            }
        }
        emit(EOF, offset);
        return new LexResult(tokens, diagnostics, comments);
    }

    private void identifier(int start) {
        while (identifierStart(peek()) || digit(peek())) {
            offset++;
        }
        emit(KEYWORDS.getOrDefault(text.substring(start, offset), IDENTIFIER), start);
    }

    private void number(int start) {
        consumeDigits();
        if (peek() == '.' && offset + 1 < text.length() && digit(text.charAt(offset + 1))) {
            offset++;
            consumeDigits();
        }
        if (take('e') || take('E')) {
            if (!take('+')) {
                take('-');
            }
            if (!digit(peek())) {
                error(Code.INVALID_NUMBER, "Expected a digit in the exponent", start);
                return;
            }
            consumeDigits();
        }

        if (Double.isFinite(Double.parseDouble(text.substring(start, offset)))) {
            emit(NUMBER, start);
        } else {
            error(Code.NUMBER_OUT_OF_RANGE, "Number exceeds the finite binary64 range", start);
        }
    }

    private void consumeDigits() {
        while (digit(peek())) {
            offset++;
        }
    }

    private void slash(int start) {
        if (take('/')) {
            while (hasInput() && peek() != '\r' && peek() != '\n') {
                offset++;
            }
            comments.add(span(start));
        } else if (take('*')) {
            blockComment(start);
            comments.add(span(start));
        } else {
            emit(take('=') ? SLASH_ASSIGN : SLASH, start);
        }
    }

    private void blockComment(int start) {
        while (hasInput()) {
            int characterStart = offset;
            char character = text.charAt(offset++);
            if (character == '*' && take('/')) {
                return;
            }
            if (character == '\n') {
                emit(NEWLINE, characterStart);
            } else if (character == '\r') {
                carriageReturn(characterStart);
            }
        }
        // Comment newlines already have tokens. Emit only a diagnostic for the full comment.
        report(Code.UNTERMINATED_COMMENT, "Expected '*/' before end of input", start);
    }

    private void carriageReturn(int start) {
        if (take('\n')) {
            emit(NEWLINE, start);
        } else {
            error(Code.INVALID_LINE_ENDING, "Expected LF after CR", start);
        }
    }

    private void string(int start) {
        StringBuilder value = new StringBuilder();
        int initialDiagnostics = diagnostics.size();
        while (hasInput() && peek() != '\n' && peek() != '\r') {
            int characterStart = offset;
            char character = text.charAt(offset++);
            if (character == '"') {
                if (diagnostics.size() == initialDiagnostics) {
                    tokens.add(new Token(STRING, span(start), value.toString()));
                } else {
                    emit(ERROR, start);
                }
                return;
            }
            if (character == '\\') {
                escape(value, characterStart);
            } else if (character < ' ') {
                report(Code.INVALID_STRING_CHARACTER, "Control characters require an escape", characterStart);
            } else {
                value.append(character);
            }
        }
        error(Code.UNTERMINATED_STRING, "Expected a closing double quote before the line ending or EOF", start);
    }

    private void escape(StringBuilder value, int start) {
        if (!hasInput() || peek() == '\n' || peek() == '\r') {
            return; // The string scanner reports the missing closing quote.
        }

        int escaped = text.codePointAt(offset);
        offset += Character.charCount(escaped);
        switch (escaped) {
            case '\\', '"' -> value.appendCodePoint(escaped);
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            case 'u' -> unicodeEscape(value, start);
            default -> report(Code.INVALID_ESCAPE, "Unknown string escape", start);
        }
    }

    private void unicodeEscape(StringBuilder value, int start) {
        if (!take('{')) {
            report(Code.INVALID_ESCAPE, "Expected '{' after Unicode escape prefix", start);
            return;
        }
        int digitsStart = offset;
        int codePoint = 0;
        int hex;
        while ((hex = hexValue(peek())) >= 0) {
            if (offset - digitsStart < 6) {
                codePoint = (codePoint << 4) | hex;
            }
            offset++;
        }
        int count = offset - digitsStart;
        if (!take('}') || count < 1 || count > 6 || !Character.isValidCodePoint(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            report(Code.INVALID_ESCAPE, "Expected 1 to 6 hex digits for a Unicode scalar followed by '}'", start);
            return;
        }
        value.appendCodePoint(codePoint);
    }

    private static int hexValue(int character) {
        if (digit(character)) {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    private static boolean identifierStart(int character) {
        return character == '_' || (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z');
    }

    private static boolean digit(int character) {
        return character >= '0' && character <= '9';
    }

    private boolean hasInput() {
        return offset < text.length();
    }

    private int peek() {
        return hasInput() ? text.charAt(offset) : -1;
    }

    private boolean take(char expected) {
        if (peek() != expected) {
            return false;
        }
        offset++;
        return true;
    }

    private SourceSpan span(int start) {
        return new SourceSpan(source, start, offset);
    }

    private void emit(TokenType type, int start) {
        tokens.add(new Token(type, span(start), null));
    }

    private void error(Code code, String message, int start) {
        report(code, message, start);
        emit(ERROR, start);
    }

    private void report(Code code, String message, int start) {
        diagnostics.add(new Diagnostic(code, message, span(start)));
    }
}
