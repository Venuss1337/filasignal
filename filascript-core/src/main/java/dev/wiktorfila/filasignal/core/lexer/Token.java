package dev.wiktorfila.filasignal.core.lexer;

import dev.wiktorfila.filasignal.core.source.SourceSpan;

/** The decoded string value is present only for STRING tokens. */
public record Token(TokenType type, SourceSpan span, String stringValue) {
    public String lexeme() {
        return span.text();
    }
}
