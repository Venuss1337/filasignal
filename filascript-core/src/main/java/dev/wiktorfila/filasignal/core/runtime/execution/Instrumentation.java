package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.source.SourceSpan;

import java.util.List;
import java.util.function.Supplier;

/**
 * Optional hooks used by a debugger. Install with {@link Interpreter#instrument} while the runtime is idle.
 * Both methods are called on the thread that runs the invocation.
 */
public interface Instrumentation {
    /**
     * Called before each statement runs. An implementation may block until its owner resumes execution.
     *
     * @return true to cancel the invocation with a {@code CANCELLED} runtime fault
     */
    boolean beforeStatement(Location location);

    /**
     * Runs a host function or output callback. An implementation may transfer the callback to the owning thread.
     * Exceptions thrown by the callback must propagate unchanged.
     */
    <T> T call(Supplier<T> callback);

    /** A read-only view of the statement about to run. Valid only during {@link #beforeStatement}. */
    interface Location {
        SourceSpan span();

        /** Active frames, innermost first. */
        List<RuntimeIssue.Frame> frames();

        /** Operations charged to the current invocation so far. */
        long operationsUsed();

        /** Bounded summaries of the visible local bindings, innermost scope first. */
        List<Variable> locals(int limit);

        /** Bounded summaries of top-level {@code global} and {@code const} bindings in declaration order. */
        List<Variable> globals(int limit);
    }

    /** A bounded, display-only summary. The scope is {@code local}, {@code global} (persistent) or {@code const}. */
    record Variable(String scope, String name, String value) {
    }
}
