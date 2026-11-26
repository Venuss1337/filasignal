package dev.wiktorfila.filasignal.core.runtime.debug;

import dev.wiktorfila.filasignal.core.ast.Ast.*;

import java.util.ArrayDeque;
import java.util.BitSet;

/**
 * Lines on which an executable statement starts. These are the lines where a breakpoint can pause.
 * Top-level initializers run during activation and are not statement boundaries.
 */
public final class StatementLines {
    private StatementLines() {
    }

    /** Returns the 1-based statement start lines of a parsed program. Iterative, so deep nesting is safe. */
    public static BitSet of(Program program) {
        var lines = new BitSet();
        var blocks = new ArrayDeque<Block>();
        for (Declaration declaration : program.declarations()) {
            switch (declaration) {
                case Function function -> blocks.push(function.body());
                case EventDeclaration event -> blocks.push(event.body());
                case ClassDeclaration clazz -> {
                    for (ClassMember member : clazz.members()) {
                        if (member instanceof Function method) {
                            blocks.push(method.body());
                        } else if (member instanceof Constructor constructor) {
                            blocks.push(constructor.body());
                        }
                    }
                }
                default -> { }
            }
        }
        while (!blocks.isEmpty()) {
            for (Statement statement : blocks.pop().statements()) {
                lines.set(statement.span().start().line());
                switch (statement) {
                    case If conditional -> {
                        conditional.branches().forEach(branch -> blocks.push(branch.body()));
                        if (conditional.fallback() != null) {
                            blocks.push(conditional.fallback());
                        }
                    }
                    case While loop -> blocks.push(loop.body());
                    case For loop -> blocks.push(loop.body());
                    default -> { }
                }
            }
        }
        return lines;
    }
}
