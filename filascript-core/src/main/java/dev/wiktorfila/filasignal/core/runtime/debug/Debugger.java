package dev.wiktorfila.filasignal.core.runtime.debug;

import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionResult;
import dev.wiktorfila.filasignal.core.runtime.execution.Instrumentation;
import dev.wiktorfila.filasignal.core.runtime.execution.RuntimeIssue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Line breakpoints, pausing and statement stepping for one program execution instance.
 *
 * <p>Ownership: every public method runs on the owner thread, which also owns the interpreter and its host
 * functions. A debugged invocation runs in a {@link Coroutine} and suspends only at statement boundaries.
 * While it is suspended the interpreter stays busy, so the scheduler must not dispatch further events to it.
 * The invocation keeps its original operation budget across pauses; inspection work is bounded and uncharged.
 *
 * <p>The debugger does not depend on a host, block or user interface. It can serve any interpreter that installs
 * {@link #instrumentation()}.
 */
public final class Debugger {
    public static final int MAX_BREAKPOINTS = 64;
    public static final int MAX_LINE = 1_000_000;
    public static final int MAX_FRAMES = 16;
    public static final int MAX_VARIABLES = 32;

    public enum Reason { BREAKPOINT, STEP, PAUSE }

    /** A source line inside a named function, handler or method. */
    public record Frame(String function, int line) {
    }

    /** Immutable state captured when execution suspends. Lists are bounded. */
    public record Pause(Reason reason, int line, int column, List<Frame> frames,
                        List<Instrumentation.Variable> variables, long operationsUsed) {
        public Pause {
            frames = List.copyOf(frames);
            variables = List.copyOf(variables);
        }
    }

    /** Result of running a slice of a debugged invocation. Operations count only this slice. */
    public sealed interface Outcome {
        long operations();
    }

    public record Finished(ExecutionResult result, long operations) implements Outcome {
    }

    public record Suspended(Pause pause, long operations) implements Outcome {
    }

    private final TreeSet<Integer> breakpoints = new TreeSet<>();
    private final Instrumentation probe = new Probe();
    private Coroutine current;
    private Pause pause;
    private boolean pauseRequested;
    private boolean stepping;
    private boolean continueRequested;
    private int lastLine = -1;
    private long reported;

    /** Hooks to install in the interpreter this debugger serves. */
    public Instrumentation instrumentation() {
        return probe;
    }

    /**
     * Replaces the breakpoint lines. Lines without a statement never trigger.
     *
     * @throws IllegalArgumentException for more than {@value #MAX_BREAKPOINTS} lines or a line outside 1 to
     *                                  {@value #MAX_LINE}
     */
    public void setBreakpoints(Collection<Integer> lines) {
        var next = new TreeSet<Integer>();
        for (Integer line : lines) {
            if (line == null || line < 1 || line > MAX_LINE) {
                throw new IllegalArgumentException("Breakpoint lines must be between 1 and " + MAX_LINE);
            }
            next.add(line);
        }
        if (next.size() > MAX_BREAKPOINTS) {
            throw new IllegalArgumentException("At most " + MAX_BREAKPOINTS + " breakpoints are supported");
        }
        breakpoints.clear();
        breakpoints.addAll(next);
    }

    public List<Integer> breakpoints() {
        return List.copyOf(breakpoints);
    }

    /** Pauses at the next statement executed by this program, in the current or a later invocation. */
    public void requestPause() {
        if (pause == null) {
            pauseRequested = true;
        }
    }

    public boolean pauseRequested() {
        return pauseRequested;
    }

    public boolean suspended() {
        return pause != null;
    }

    public Optional<Pause> pause() {
        return Optional.ofNullable(pause);
    }

    /**
     * Asks the scheduler to continue a suspended invocation. With {@code step}, execution pauses again at the next
     * statement, including one inside a called function or a later handler.
     *
     * When nothing is suspended, a step requests a pause at the next statement and a plain continue withdraws a
     * pending pause request.
     *
     * @return false when nothing is suspended
     */
    public boolean requestContinue(boolean step) {
        if (pause == null) {
            pauseRequested = step;
            return false;
        }
        continueRequested = true;
        stepping = step;
        return true;
    }

    public boolean continueRequested() {
        return continueRequested;
    }

    /** Clears breakpoints and pending pauses. A suspended invocation continues at its next scheduler turn. */
    public void reset() {
        breakpoints.clear();
        pauseRequested = false;
        if (pause != null) {
            requestContinue(false);
        } else {
            stepping = false;
        }
    }

    /** Runs a new invocation until it finishes or suspends. Host callbacks execute on this thread. */
    public Outcome run(Supplier<ExecutionResult> invocation) {
        Objects.requireNonNull(invocation);
        if (current != null) {
            throw new IllegalStateException("A debugged invocation is already in progress");
        }
        current = new Coroutine(invocation);
        reported = 0;
        lastLine = -1;
        return settle(current.start());
    }

    /** Continues the suspended invocation after {@link #requestContinue}. */
    public Outcome proceed() {
        if (pause == null || !continueRequested) {
            throw new IllegalStateException("No continuation was requested");
        }
        continueRequested = false;
        pause = null;
        return settle(current.resume(false));
    }

    /**
     * Cancels a suspended invocation. It finishes with a {@code CANCELLED} fault and keeps writes made before the
     * pause, like any other runtime fault. Returns empty when nothing is suspended.
     */
    public Optional<Finished> abort() {
        if (pause == null) {
            return Optional.empty();
        }
        continueRequested = false;
        stepping = false;
        pause = null;
        Outcome outcome = settle(current.resume(true));
        if (outcome instanceof Finished finished) {
            return Optional.of(finished);
        }
        throw new IllegalStateException("A cancelled invocation suspended again");
    }

    private Outcome settle(Coroutine.Signal signal) {
        return switch (signal) {
            case Coroutine.Suspended suspended -> {
                pause = suspended.pause();
                long slice = pause.operationsUsed() - reported;
                reported = pause.operationsUsed();
                yield new Suspended(pause, slice);
            }
            case Coroutine.Done done -> {
                current = null;
                pause = null;
                continueRequested = false;
                yield new Finished(done.result(), Math.max(0, done.result().operationsUsed() - reported));
            }
        };
    }

    /**
     * Converts interpreter frames into display frames. The innermost frame reports the current line and each
     * outer frame reports the line of the call it is waiting on.
     */
    public static List<Frame> frames(int currentLine, List<RuntimeIssue.Frame> stack) {
        var result = new ArrayList<Frame>();
        int line = currentLine;
        for (RuntimeIssue.Frame frame : stack) {
            if (result.size() == MAX_FRAMES) {
                break;
            }
            result.add(new Frame(frame.function(), line));
            line = frame.callSite().start().line();
        }
        return result;
    }

    private final class Probe implements Instrumentation {
        @Override
        public boolean beforeStatement(Location location) {
            Coroutine running = current;
            if (running == null || !running.onWorker()) {
                return false;
            }
            Reason reason = reason(location);
            if (reason == null) {
                return false;
            }
            var start = location.span().start();
            var variables = new ArrayList<>(location.locals(MAX_VARIABLES));
            variables.addAll(location.globals(MAX_VARIABLES));
            stepping = false;
            pauseRequested = false;
            return running.suspend(new Pause(reason, start.line(), start.column(),
                    frames(start.line(), location.frames()), variables, location.operationsUsed()));
        }

        // A breakpoint pauses once each time execution arrives at its line, so several statements on one line
        // or a one-line loop do not pause repeatedly.
        private Reason reason(Location location) {
            if (!stepping && !pauseRequested && breakpoints.isEmpty()) {
                lastLine = -1;
                return null;
            }
            int line = location.span().start().line();
            Reason reason = stepping ? Reason.STEP : pauseRequested ? Reason.PAUSE
                    : breakpoints.contains(line) && line != lastLine ? Reason.BREAKPOINT : null;
            lastLine = line;
            return reason;
        }

        @Override
        public <T> T call(Supplier<T> callback) {
            Coroutine running = current;
            return running != null && running.onWorker() ? running.call(callback) : callback.get();
        }
    }
}
