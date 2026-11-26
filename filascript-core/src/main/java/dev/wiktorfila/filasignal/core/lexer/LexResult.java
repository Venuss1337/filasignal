package dev.wiktorfila.filasignal.core.lexer;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;

import java.util.List;

public record LexResult(List<Token> tokens, List<Diagnostic> diagnostics, List<dev.wiktorfila.filasignal.core.source.SourceSpan> comments) {
    public LexResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
        comments = List.copyOf(comments);
    }
}
