package dev.wiktorfila.filasignal.core.analysis;

import dev.wiktorfila.filasignal.core.ast.Ast.Name;

/** A declaration's identity is independent of its spelling. */
public record Symbol(int id, String name, Kind kind, Name declaration, String type) {
    public enum Kind {
        LOCAL, PARAMETER, LOOP_VARIABLE, GLOBAL, CONSTANT, FUNCTION, CLASS, ENUM, BUILTIN
    }

    public boolean mutable() {
        return switch (kind) {
            case LOCAL, PARAMETER, LOOP_VARIABLE, GLOBAL -> true;
            case CONSTANT, FUNCTION, CLASS, ENUM, BUILTIN -> false;
        };
    }
}
