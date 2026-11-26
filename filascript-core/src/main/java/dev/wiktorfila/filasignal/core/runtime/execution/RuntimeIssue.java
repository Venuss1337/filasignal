package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.source.SourceSpan;

import java.util.List;

/** Stack frames are ordered from the innermost call to the entry point. */
public record RuntimeIssue(Diagnostic diagnostic, List<Frame> frames) {
    public RuntimeIssue { frames = List.copyOf(frames); }

    public record Frame(String function, SourceSpan callSite) {
    }
}
