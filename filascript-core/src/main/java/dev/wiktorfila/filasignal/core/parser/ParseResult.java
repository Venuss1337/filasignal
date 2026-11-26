package dev.wiktorfila.filasignal.core.parser;

import dev.wiktorfila.filasignal.core.ast.Ast.Program;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;

import java.util.List;

/** The program may be partial when diagnostics are present. */
public record ParseResult(Program program, List<Diagnostic> diagnostics) {
    public ParseResult { diagnostics = List.copyOf(diagnostics); }
}
