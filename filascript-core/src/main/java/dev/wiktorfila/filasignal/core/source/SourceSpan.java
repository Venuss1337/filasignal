package dev.wiktorfila.filasignal.core.source;

import java.util.Objects;

/** Half-open range in the source's UTF-16 text. */
public record SourceSpan(SourceText source, int startOffset, int endOffset) {
    public SourceSpan {
        Objects.requireNonNull(source);
        source.requireBoundary(startOffset);
        source.requireBoundary(endOffset);
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("Span end precedes its start");
        }
    }

    public SourcePosition start() {
        return source.positionAt(startOffset);
    }

    public SourcePosition end() {
        return source.positionAt(endOffset);
    }

    public String text() {
        return source.text().substring(startOffset, endOffset);
    }
}
