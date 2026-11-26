package dev.wiktorfila.filasignal.core.analysis;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;

import java.util.List;
import java.util.Optional;

public record AnalysisResult(Optional<ResolvedProgram> program, List<Diagnostic> diagnostics) {
    public AnalysisResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
