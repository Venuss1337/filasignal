package dev.wiktorfila.filasignal.core.runtime.debug;

import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionResult;

import java.util.concurrent.SynchronousQueue;
import java.util.function.Supplier;

/**
 * Runs one invocation on a virtual thread used strictly as a coroutine.
 *
 * <p>Exactly one of the owner thread and the worker runs at any time. The owner blocks in {@link #start} or
 * {@link #resume} until the worker suspends or finishes, and the worker blocks while suspended. Host callbacks
 * requested by the worker execute on the owner thread, so world access stays on the thread that owns it. Every
 * transfer goes through a {@link SynchronousQueue}, which also publishes the memory effects of each side.
 */
final class Coroutine {
    sealed interface Signal permits Suspended, Done {
    }

    record Suspended(Debugger.Pause pause) implements Signal {
    }

    record Done(ExecutionResult result) implements Signal {
    }

    private record Crash(Throwable error) {
    }

    private record HostCall(Supplier<?> callback) {
    }

    private record HostReply(Object value, Throwable error) {
    }

    private record Resume(boolean cancel) {
    }

    private final SynchronousQueue<Object> toOwner = new SynchronousQueue<>();
    private final SynchronousQueue<Object> toWorker = new SynchronousQueue<>();
    private final Thread worker;

    Coroutine(Supplier<ExecutionResult> body) {
        worker = Thread.ofVirtual().name("filascript-debugger").unstarted(() -> {
            Object last;
            try {
                last = new Done(body.get());
            } catch (Throwable error) {
                last = new Crash(error);
            }
            transfer(toOwner, last);
        });
    }

    boolean onWorker() {
        return Thread.currentThread() == worker;
    }

    /** Owner thread: starts the worker and waits until it suspends or finishes. */
    Signal start() {
        worker.start();
        return drive();
    }

    /** Owner thread: resumes a suspended worker, optionally asking it to cancel, and waits again. */
    Signal resume(boolean cancel) {
        transfer(toWorker, new Resume(cancel));
        return drive();
    }

    private Signal drive() {
        while (true) {
            Object message = receive(toOwner);
            switch (message) {
                case HostCall call -> {
                    HostReply reply;
                    try {
                        reply = new HostReply(call.callback().get(), null);
                    } catch (RuntimeException | Error error) {
                        reply = new HostReply(null, error);
                    }
                    transfer(toWorker, reply);
                }
                case Crash crash -> {
                    if (crash.error() instanceof Error error) {
                        throw error;
                    }
                    if (crash.error() instanceof RuntimeException exception) {
                        throw exception;
                    }
                    throw new IllegalStateException("Debugged invocation failed", crash.error());
                }
                default -> {
                    return (Signal) message;
                }
            }
        }
    }

    /** Worker thread: publishes a pause and blocks until resumed. Returns true when the owner cancelled. */
    boolean suspend(Debugger.Pause pause) {
        transfer(toOwner, new Suspended(pause));
        return ((Resume) receive(toWorker)).cancel();
    }

    /** Worker thread: runs a callback on the owner thread and rethrows its unchecked failure unchanged. */
    @SuppressWarnings("unchecked")
    <T> T call(Supplier<T> callback) {
        transfer(toOwner, new HostCall(callback));
        HostReply reply = (HostReply) receive(toWorker);
        if (reply.error() instanceof RuntimeException exception) {
            throw exception;
        }
        if (reply.error() instanceof Error error) {
            throw error;
        }
        return (T) reply.value();
    }

    // Interrupts do not abandon a handoff: the peer would otherwise block forever. The flag is restored afterward.
    private static void transfer(SynchronousQueue<Object> queue, Object message) {
        boolean interrupted = false;
        while (true) {
            try {
                queue.put(message);
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object receive(SynchronousQueue<Object> queue) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return queue.take();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
