package dev.wiktorfila.filasignal.core.runtime.event;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.debug.Debugger;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionResult;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.runtime.execution.RuntimeIssue;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateStore;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateException;
import java.io.IOException;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.source.SourceText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Single-owner deterministic automation simulator. Only cancellation crosses threads. */
public final class Simulation {
    // Computers and autonomous devices share one scheduler, so the cap covers both.
    private static final int MAX_CONTROLLERS = 512;
    private static final int MAX_SOURCE_UNITS = 262_144;
    private static final int MAX_QUEUE_UNITS = 262_144;
    /** Each attached debugger can hold one parked virtual thread while its handler is paused. */
    public static final int MAX_DEBUGGERS = 16;
    private static final Set<String> SIDES = Set.of("left", "right", "front", "back", "top", "bottom");
    private static final Slot SIDE = new Slot("side", Set.of(Type.STRING));
    private static final Slot STRENGTH = new Slot("strength", Set.of(Type.NUMBER));
    private static final Slot ERROR = new Slot("error", Set.of(Type.ERROR, Type.NIL));
    private static final Slot OK = new Slot("ok", Set.of(Type.BOOL, Type.NIL));
    private static final Set<Type> PAYLOAD_TYPES = Set.of(Type.NUMBER, Type.BOOL, Type.STRING, Type.NIL, Type.ENUM,
            Type.LIST, Type.TUPLE, Type.DICT);

    private final Map<String, Controller> controllers = new LinkedHashMap<>();
    private final long workPerTick;
    private final int queueCapacity;
    private long tick;
    private int next;
    private boolean ticking;

    public Simulation(long workPerTick, int queueCapacity) {
        if (workPerTick < 1 || queueCapacity < 1 || queueCapacity > 256) {
            throw new IllegalArgumentException("Invalid scheduler limits");
        }
        this.workPerTick = workPerTick;
        this.queueCapacity = queueCapacity;
    }

    private int debuggers() {
        int count = 0;
        for (Controller controller : controllers.values()) {
            if (controller.debugger != null) {
                count++;
            }
        }
        return count;
    }

    /** Maximum number of simultaneously attached controllers. */
    public int capacity() {
        return MAX_CONTROLLERS;
    }

    public record Registration(Controller controller, List<Diagnostic> diagnostics) {
        public Registration { diagnostics = List.copyOf(diagnostics); }
    }

    public Registration add(String identity, SourceText source, ExecutionLimits limits, Set<String> capabilities,
                            Consumer<String> output) {
        try {
            return add(identity, source, limits, capabilities, output, null, null);
        } catch (IOException impossible) {
            throw new IllegalStateException("No state storage was supplied", impossible);
        }
    }

    public Registration add(String identity, SourceText source, ExecutionLimits limits, Set<String> capabilities,
                            Consumer<String> output, StateStore store, StateStore.Migration migration) throws IOException {
        return attach(identity, source, limits, capabilities, output, store, migration, null);
    }

    /**
     * Adds an embedded controller with an explicit host registry, optionally restoring saved state.
     * Call between ticks on the scheduler's owning thread. A rejected program is not attached.
     * The store may be null. A load failure propagates without changing scheduler membership.
     */
    public Registration addEmbedded(String identity, SourceText source, ExecutionLimits limits, Set<String> capabilities,
                                    Consumer<String> output, StateStore store, HostRegistry hosts) throws IOException {
        return attach(identity, source, limits, capabilities, output, store, null, Objects.requireNonNull(hosts));
    }

    private Registration attach(String identity, SourceText source, ExecutionLimits limits, Set<String> capabilities,
                                Consumer<String> output, StateStore store, StateStore.Migration migration,
                                HostRegistry hosts) throws IOException {
        if (ticking || controllers.size() == MAX_CONTROLLERS || controllers.containsKey(identity)
                || limits.operations() > workPerTick) {
            throw new IllegalArgumentException("Controller cannot be added with these scheduler limits or identity");
        }
        if (source.text().length() > MAX_SOURCE_UNITS) {
            return new Registration(null, List.of(new Diagnostic(Diagnostic.Code.DATA_LIMIT,
                    "Controller source exceeds " + MAX_SOURCE_UNITS + " UTF-16 units",
                    new dev.wiktorfila.filasignal.core.source.SourceSpan(source, 0, 0))));
        }
        Controller controller = new Controller(identity, source, limits.operations());
        var analyzed = Resolver.resolve(Parser.parse(source), hosts == null ? controller.hosts() : hosts);
        if (!analyzed.diagnostics().isEmpty()) {
            return new Registration(null, analyzed.diagnostics());
        }
        controller.interpreter = new Interpreter(analyzed.program().orElseThrow(), limits, output, capabilities);
        controller.store = store;
        if (store != null) {
            var saved = store.load(identity);
            if (saved.isPresent()) {
                try {
                    controller.interpreter.restoreGlobals(saved.orElseThrow(), migration);
                } catch (StateException state) {
                    return new Registration(null, List.of(new Diagnostic(
                            Diagnostic.Code.PERSISTENCE_FAILURE, state.getMessage(), analyzed.program().orElseThrow().syntax().span())));
                }
            }
        }
        Interpreter.Preparation preparation = controller.interpreter.prepareEvents();
        if (preparation.issue().isPresent()) {
            return new Registration(null, List.of(preparation.issue().orElseThrow().diagnostic()));
        }
        controller.subscriptions = preparation.subscriptions();
        controllers.put(identity, controller);
        controller.enqueue("startup", List.of(), Nil.INSTANCE, tick + 1);
        return new Registration(controller, List.of());
    }

    public record Failure(String controller, RuntimeIssue issue) {
    }

    /** Completed dispatches include failed handlers because their earlier writes may still be visible. */
    public record Tick(long number, long operations, int dispatched, List<Failure> failures,
                       List<String> dispatchedControllers) {
        public Tick {
            failures = List.copyOf(failures);
            dispatchedControllers = List.copyOf(dispatchedControllers);
        }

        public Tick(long number, long operations, int dispatched, List<Failure> failures) {
            this(number, operations, dispatched, failures, List.of());
        }
    }

    /**
     * Detaches a controller without executing any queued handlers, on the owning thread between ticks.
     * The caller owns saving state before detaching. Returns false when the identity is absent.
     */
    public boolean detach(String identity) {
        if (ticking) {
            throw new IllegalStateException("Cannot detach during dispatch");
        }
        int index = 0;
        var entries = controllers.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getKey().equals(identity)) {
                Controller removed = entry.getValue();
                removed.debug(null);
                entries.remove();
                removed.stopping = true;
                removed.cancelPending();
                if (index < next) {
                    next--;
                }
                next = controllers.isEmpty() ? 0 : next % controllers.size();
                return true;
            }
            index++;
        }
        return false;
    }

    public Tick tick() {
        if (ticking || tick == Long.MAX_VALUE - 1) {
            throw new IllegalStateException("Cannot tick recursively or beyond the tick range");
        }
        ticking = true;
        try {
            tick++;
            for (Controller controller : controllers.values()) {
                controller.timers();
            }
            long remaining = workPerTick;
            int dispatched = 0;
            var failures = new ArrayList<Failure>();
            var completed = new ArrayList<String>();
            List<Controller> ordered = List.copyOf(controllers.values());
            int start = next;
            for (int step = 0; step < ordered.size(); step++) {
                int index = (start + step) % ordered.size();
                Controller controller = ordered.get(index);
                Debugger debugger = controller.debugger;
                boolean resuming = debugger != null && debugger.suspended();
                // A paused program keeps its in-flight handler and queue. Other controllers continue normally.
                if (resuming && !debugger.continueRequested()) {
                    continue;
                }
                Pending head = resuming ? null : controller.queue.peekFirst();
                if (!resuming && (head == null || head.readyTick() > tick)) {
                    continue;
                }
                if (remaining < controller.handlerBudget) {
                    next = index;
                    break;
                }
                Debugger.Outcome outcome;
                if (resuming) {
                    outcome = debugger.proceed();
                } else {
                    controller.queue.removeFirst();
                    controller.queuedUnits -= head.units();
                    controller.cancelled.set(false);
                    java.util.function.Supplier<ExecutionResult> invocation = () -> controller.interpreter.invokeEvent(
                            head.subscription(), head.arguments(), head.values(), controller.handlerBudget,
                            controller.cancelled::get);
                    outcome = debugger == null ? finished(invocation.get()) : debugger.run(invocation);
                }
                remaining -= Math.max(1, outcome.operations());
                dispatched++;
                completed.add(controller.identity);
                if (outcome instanceof Debugger.Finished finished) {
                    controller.record(finished.result());
                    finished.result().issue().ifPresent(issue -> failures.add(new Failure(controller.identity, issue)));
                }
                next = (index + 1) % ordered.size();
            }
            return new Tick(tick, workPerTick - remaining, dispatched, failures, completed);
        } finally {
            ticking = false;
        }
    }

    private static Debugger.Finished finished(ExecutionResult result) {
        return new Debugger.Finished(result, result.operationsUsed());
    }

    /**
     * Recent per-controller execution figures. Operation counts cover whole handler invocations, including work
     * performed before and after any debugger pause.
     */
    public record Statistics(long handlers, long faults, long lastOperations, long peakOperations, long budget,
                             int pending, long dropped) {
    }

    private record Pending(int subscription, List<Value> arguments, List<Value> values, long readyTick, int units) {
        private Pending(int subscription, List<Value> arguments, List<Value> values, long readyTick) {
            this(subscription, arguments, values, readyTick, 1 + values.stream().mapToInt(Simulation::eventUnits).sum()
                    + arguments.stream().mapToInt(Simulation::eventUnits).sum());
        }
    }

    public final class Controller {
        private final String identity;
        private final SourceText source;
        private final long handlerBudget;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final ArrayDeque<Pending> queue = new ArrayDeque<>();
        private final Map<String, Integer> inputs = new LinkedHashMap<>();
        private final Map<String, Integer> outputs = new LinkedHashMap<>();
        private List<Interpreter.Subscription> subscriptions;
        private Interpreter interpreter;
        private StateStore store;
        private Debugger debugger;
        private long dropped;
        private int queuedUnits;
        private boolean stopping;
        private long handlers;
        private long faults;
        private long lastOperations;
        private long peakOperations;

        private Controller(String identity, SourceText source, long handlerBudget) {
            this.identity = Objects.requireNonNull(identity);
            this.source = source;
            this.handlerBudget = handlerBudget;
        }

        public String identity() { return identity; }
        public SourceText source() { return source; }
        public Interpreter interpreter() { return interpreter; }
        public List<Interpreter.Subscription> subscriptions() { return subscriptions; }
        /**
         * Saves globals through the configured store.
         *
         * @throws IOException when the store fails or a debugger holds a paused handler. The prior record stays valid.
         */
        public void save() throws IOException {
            if (store == null) {
                throw new IllegalStateException("No state store was configured");
            }
            if (suspended()) {
                throw new IOException("Globals cannot be saved while a handler is paused");
            }
            store.save(identity, interpreter.snapshotGlobals());
        }

        public int pending() { return queue.size(); }
        public long dropped() { return dropped; }
        public void cancelCurrent() { cancelled.set(true); }

        public Statistics statistics() {
            return new Statistics(handlers, faults, lastOperations, peakOperations, handlerBudget, queue.size(), dropped);
        }

        private void record(ExecutionResult result) {
            handlers++;
            if (result.issue().isPresent()) {
                faults++;
            }
            lastOperations = result.operationsUsed();
            peakOperations = Math.max(peakOperations, lastOperations);
        }

        public Debugger debugger() { return debugger; }

        /** True while a debugger holds a paused handler for this controller. */
        public boolean suspended() {
            return debugger != null && debugger.suspended();
        }

        /**
         * Attaches a debugger, or detaches with null, between ticks on the owning thread. Detaching or replacing a
         * debugger cancels its paused handler. Without a debugger handlers run directly with no hook overhead.
         *
         * @return the cancelled handler result, if a paused handler had to be cancelled
         */
        public Optional<ExecutionResult> debug(Debugger next) {
            if (ticking) {
                throw new IllegalStateException("Cannot change debuggers during dispatch");
            }
            if (next == debugger) {
                return Optional.empty();
            }
            if (next != null && debuggers() >= MAX_DEBUGGERS) {
                throw new IllegalStateException("Too many controllers are being debugged");
            }
            Optional<ExecutionResult> cancelled = abortSuspended();
            debugger = next;
            interpreter.instrument(next == null ? null : next.instrumentation());
            return cancelled;
        }

        /** Cancels a paused handler, keeping its earlier writes, so the controller can stop or detach. */
        public Optional<ExecutionResult> abortSuspended() {
            if (debugger == null) {
                return Optional.empty();
            }
            var aborted = debugger.abort().map(Debugger.Finished::result);
            aborted.ifPresent(this::record);
            return aborted;
        }

        public void cancelPending() {
            queue.clear();
            queuedUnits = 0;
        }

        public void shutdown() {
            if (!stopping) {
                stopping = true;
                abortSuspended();
                cancelPending();
                enqueue("shutdown", List.of(), Nil.INSTANCE, tick + 1);
            }
        }

        public int output(String side) {
            return outputs.getOrDefault(side, 0);
        }

        public boolean setInput(String side, int strength) {
            if (stopping || !SIDES.contains(side) || strength < 0 || strength > 15) {
                return false;
            }
            int previous = inputs.getOrDefault(side, 0);
            inputs.put(side, strength);
            return previous != 0 || strength == 0
                    || enqueue("redstone.rising", List.of(new Text(side)), new Num(strength), tick + 1);
        }

        public boolean emit(String name, List<Value> arguments, Value payload) {
            return emit(name, arguments, List.of(payload));
        }

        /**
         * Queues a named event for every matching handler without a source, for the next tick. Arguments are scalars
         * compared with handler filters. Values bind to handler parameters.
         *
         * @return false when the controller is stopping, the event is invalid or a queue was full
         */
        public boolean emit(String name, List<Value> arguments, List<Value> values) {
            if (stopping || !validCustomName(name) || arguments.size() > 16 || values.size() > 16
                    || values.stream().anyMatch(value -> !Interpreter.eventValue(value))
                    || arguments.stream().anyMatch(argument -> !Interpreter.scalarEventValue(argument))) {
                return false;
            }
            return enqueue(name, List.copyOf(arguments), List.copyOf(values), tick + 1);
        }

        /**
         * Queues an event for one handler chosen by the host, such as a device event the host routed to
         * {@code on gate.entityEnter(entity)}. Runs on a later tick, never synchronously.
         *
         * @return false when the controller is stopping, the subscription or values are invalid, or the queue is full
         */
        public boolean deliver(int subscription, List<Value> values) {
            if (stopping || subscription < 0 || subscription >= subscriptions.size() || values.size() > 16
                    || values.stream().anyMatch(value -> !Interpreter.eventValue(value))) {
                return false;
            }
            Interpreter.Subscription target = subscriptions.get(subscription);
            return offer(new Pending(target.index(), target.filters(), List.copyOf(values), tick + 1));
        }

        private boolean enqueue(String name, List<Value> arguments, Value payload, long ready) {
            return enqueue(name, arguments, List.of(payload), ready);
        }

        private boolean enqueue(String name, List<Value> arguments, List<Value> values, long ready) {
            boolean accepted = true;
            for (Interpreter.Subscription subscription : subscriptions) {
                if (subscription.source() == null && subscription.name().equals(name)
                        && matches(subscription.filters(), arguments)) {
                    accepted &= offer(new Pending(subscription.index(), arguments, values, ready));
                }
            }
            return accepted;
        }

        private boolean offer(Pending event) {
            if (queue.size() == queueCapacity || event.units() > MAX_QUEUE_UNITS - queuedUnits) {
                if (dropped != Long.MAX_VALUE) {
                    dropped++;
                }
                return false;
            }
            queue.addLast(event);
            queuedUnits += event.units();
            return true;
        }

        // Timer periods that elapse while a handler is paused are skipped rather than queued as a burst.
        private void timers() {
            if (stopping || suspended()) {
                return;
            }
            for (Interpreter.Subscription subscription : subscriptions) {
                if (subscription.name().equals("timer.every")) {
                    int period = (int) ((Num) subscription.filters().getFirst()).value();
                    if (tick % period == 0) {
                        offer(new Pending(subscription.index(), subscription.filters(), List.of(new Num(tick)), tick));
                    }
                }
            }
        }

        private HostRegistry hosts() {
            return new HostRegistry(List.of(
                    new Function("print", List.of(new Slot("value", java.util.EnumSet.allOf(Type.class))), List.of(),
                            "Print one display line.", Set.of(), 1, (args, context) -> {
                        context.print(args.getFirst());
                        return List.of();
                    }),
                    new Function("log.info", List.of(new Slot("message", Set.of(Type.STRING))), List.of(),
                            "Write a controller log line.", Set.of("log"), 1, (args, context) -> {
                        context.log(((Text) args.getFirst()).value());
                        return List.of();
                    }),
                    new Function("redstone.read", List.of(SIDE), List.of(new Slot("value", Set.of(Type.NUMBER, Type.NIL)), ERROR),
                            "Read a simulated input strength.", Set.of("redstone.read"), 2, (args, context) -> {
                        String side = ((Text) args.getFirst()).value();
                        if (!SIDES.contains(side)) {
                            return error("redstone.side", "Unknown controller side");
                        }
                        return List.of(new Num(inputs.getOrDefault(side, 0)), Nil.INSTANCE);
                    }),
                    new Function("redstone.set", List.of(SIDE, STRENGTH), List.of(OK, ERROR),
                            "Set a simulated output strength.", Set.of("redstone.write"), 3, (args, context) -> {
                        String side = ((Text) args.getFirst()).value();
                        double value = ((Num) args.get(1)).value();
                        if (!SIDES.contains(side) || value < 0 || value > 15 || value != Math.rint(value)) {
                            return error("redstone.value", "Expected a known side and integer strength from 0 to 15");
                        }
                        int strength = (int) value;
                        int previous = outputs.getOrDefault(side, 0);
                        if (strength != previous) {
                            context.charge(subscriptions.size());
                            outputs.put(side, strength);
                            if (!stopping && !enqueue("redstone.changed", List.of(new Text(side)), new Num(strength), tick + 1)) {
                                return error("event.overflow", "Output changed but some events were dropped");
                            }
                        }
                        return List.of(new Bool(true), Nil.INSTANCE);
                    }),
                    new Function("events.emit", List.of(new Slot("name", Set.of(Type.STRING)),
                            new Slot("payload", PAYLOAD_TYPES)), List.of(OK, ERROR),
                            "Queue a custom event for a later tick.", Set.of("events.emit"), 3, (args, context) -> {
                        context.charge(subscriptions.size());
                        return emit(((Text) args.getFirst()).value(), List.of(), args.get(1))
                                ? List.of(new Bool(true), Nil.INSTANCE)
                                : error("event.rejected", "Event was invalid, stopped or dropped");
                    })));
        }
    }

    private static int eventUnits(Value value) {
        return switch (value) {
            case Text text -> 1 + text.value().length();
            case EnumMember member -> 1 + member.name().length() + member.enumeration().length();
            case ListValue list -> 1 + list.snapshot().stream().mapToInt(Simulation::eventUnits).sum();
            case TupleValue tuple -> 1 + tuple.elements().stream().mapToInt(Simulation::eventUnits).sum();
            case DictValue dict -> 1 + dict.keys().stream().mapToInt(key -> key.length() + eventUnits(dict.get(key))).sum();
            case ErrorValue error -> 1 + error.code().length() + error.message().length();
            default -> 1;
        };
    }

    private static boolean validCustomName(String name) {
        return name != null && name.length() <= 128 && !Set.of("startup", "shutdown", "timer.every").contains(name)
                && name.matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*");
    }


    private static boolean matches(List<Value> filters, List<Value> arguments) {
        if (filters.isEmpty()) {
            return true;
        }
        if (filters.size() != arguments.size()) {
            return false;
        }
        for (int i = 0; i < filters.size(); i++) {
            Value expected = filters.get(i);
            Value actual = arguments.get(i);
            if (expected instanceof Num a && actual instanceof Num b) {
                if (a.value() != b.value()) {
                    return false;
                }
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static List<Value> error(String code, String message) {
        return List.of(Nil.INSTANCE, new ErrorValue(code, message));
    }
}
