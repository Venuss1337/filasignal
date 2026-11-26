package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.runtime.debug.Debugger;
import dev.wiktorfila.filasignal.core.runtime.debug.ProgramConsole;
import dev.wiktorfila.filasignal.core.runtime.execution.RuntimeIssue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Server-thread diagnostics for one loaded computer: bounded console history, the latest runtime fault, the latest
 * activation rejection and a server-log throttle. It outlives individual activations so Stop and Restart keep
 * history, and is discarded when the block entity unloads.
 */
final class ComputerStatus {
    private static final int LOG_LINES_PER_WINDOW = 20;
    private static final int LOG_WINDOW_TICKS = 1200;
    private static final Set<Diagnostic.Code> RESOURCE_LIMITS = EnumSet.of(Diagnostic.Code.EXECUTION_BUDGET,
            Diagnostic.Code.CALL_DEPTH_LIMIT, Diagnostic.Code.EXECUTION_DEPTH_LIMIT, Diagnostic.Code.DATA_LIMIT,
            Diagnostic.Code.OUTPUT_LIMIT, Diagnostic.Code.COLLECTION_LIMIT);

    /** A located problem with a human-readable message and a bounded call stack, innermost first. */
    record Problem(String message, int line, int column, List<Debugger.Frame> frames) {
        Problem {
            frames = List.copyOf(frames);
        }

        String located() {
            return line + ":" + column + " " + message;
        }
    }

    final ProgramConsole console = new ProgramConsole();
    private Problem fault;
    private Problem rejection;
    private long logWindow = -1;
    private int logLines;

    Problem fault() { return fault; }
    Problem rejection() { return rejection; }

    void fault(RuntimeIssue issue) {
        var diagnostic = issue.diagnostic();
        var start = diagnostic.span().start();
        fault = new Problem(describe(diagnostic), start.line(), start.column(),
                Debugger.frames(start.line(), issue.frames()));
        console.add(ProgramConsole.Kind.ERROR, "Runtime error " + fault.located());
    }

    void rejected(Diagnostic diagnostic, int count) {
        var start = diagnostic.span().start();
        rejection = new Problem(diagnostic.message() + (count > 1 ? " (+" + (count - 1) + " more)" : ""),
                start.line(), start.column(), List.of());
        console.add(ProgramConsole.Kind.ERROR, "Program rejected at " + rejection.located());
    }

    void rejected(String reason) {
        rejection = new Problem(reason, 0, 0, List.of());
        console.add(ProgramConsole.Kind.ERROR, "Program could not start: " + reason);
    }

    void activated() {
        rejection = null;
        console.add(ProgramConsole.Kind.LIFECYCLE, "Program started");
    }

    void lifecycle(String message) {
        console.add(ProgramConsole.Kind.LIFECYCLE, message);
    }

    /** Locations in an older program no longer apply once a new program is installed. */
    void programChanged() {
        fault = null;
        rejection = null;
    }

    /**
     * Rate-limits mirroring program output to the server log so busy timers cannot flood it. Returns the line to
     * log, a one-time notice when the window's allowance runs out, or null.
     */
    String serverLogLine(long tick, String line) {
        long window = tick / LOG_WINDOW_TICKS;
        if (window != logWindow) {
            logWindow = window;
            logLines = 0;
        }
        if (logLines > LOG_LINES_PER_WINDOW) {
            return null;
        }
        logLines++;
        return logLines <= LOG_LINES_PER_WINDOW ? line
                : "Further output is omitted from the server log for up to a minute. Open the computer to read it.";
    }

    private static String describe(Diagnostic diagnostic) {
        String message = diagnostic.message();
        return RESOURCE_LIMITS.contains(diagnostic.code()) ? "Handler stopped by a resource limit: " + message : message;
    }
}
