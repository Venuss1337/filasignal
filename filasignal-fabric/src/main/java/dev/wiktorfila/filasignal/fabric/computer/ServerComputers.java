package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.debug.Debugger;
import dev.wiktorfila.filasignal.core.runtime.debug.ProgramConsole;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.event.Simulation;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateStore;
import dev.wiktorfila.filasignal.core.source.SourceText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every active program on a server, whether it belongs to a computer or to a device with a drive, and
 * delegates execution fairness to the one core scheduler. Server-thread only.
 */
final class ServerComputers {
    private static final Logger LOG = LoggerFactory.getLogger("filasignal/computer");
    // Opt-in lifecycle tracing for investigating load, unload and restart order: -Dfilasignal.traceLifecycle=true.
    private static final boolean TRACE = Boolean.getBoolean("filasignal.traceLifecycle");
    // Computers and device drives share this queue. Activation parses and checks a program, so each tick starts at most
    // 16 programs and at most 64K characters of source; one program always starts so a large one cannot stall.
    private static final int ACTIVATIONS_PER_TICK = 16;
    private static final int ACTIVATION_SOURCE_PER_TICK = 65_536;
    private static final int MAX_STATUSES = 1024;
    private static final int MAX_REQUESTS = 64;
    private static final ExecutionLimits HANDLER_LIMITS = new ExecutionLimits(32, 10_000, 262_144, 8, 1024);

    private final MinecraftServer server;
    private static final long TICK_BUDGET = 40_000;
    private final Simulation scheduler = new Simulation(TICK_BUDGET, 32);
    private final Set<ProgramHost> pending = new LinkedHashSet<>();
    private final Map<ProgramHost, ActiveProgram> bindings = new LinkedHashMap<>();
    private final Map<String, ActiveProgram> identities = new LinkedHashMap<>();
    // Diagnostics outlive activations so Stop and Restart keep history. Entries go when the block entity unloads.
    private final Map<ProgramHost, ComputerStatus> statuses = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ProgramHost, ComputerStatus> eldest) {
            return size() > MAX_STATUSES;
        }
    };
    // Lifecycle changes requested by running programs run before the next dispatch, never inside one.
    private final ArrayDeque<Runnable> requests = new ArrayDeque<>();
    // Visual states to show on blocks, applied at the end of the tick so chunk loading never changes block states.
    private final Map<ProgramHost, ProgramHost.Indicator> indicators = new LinkedHashMap<>();
    private final DebugSessions debugging = new DebugSessions(this);
    private boolean stopping;
    private long latestWork;
    private long latestNanos;

    ServerComputers(MinecraftServer server) {
        this.server = server;
    }

    void enqueue(ProgramHost host) {
        requireServerThread();
        if (!stopping && !bindings.containsKey(host) && host.runnable()) {
            if (pending.size() < scheduler.capacity()) {
                pending.add(host);
            } else {
                LOG.warn("Program activation queue is full at {}", host.block().getBlockPos());
            }
        }
    }

    /** Releases everything held for a block entity that unloaded or was removed. */
    void unload(ProgramHost host) {
        requireServerThread();
        pending.remove(host);
        detachRuntime(host, "Program unloaded");
        indicators.remove(host);
        debugging.forget(server, host);
        statuses.remove(host);
    }

    /**
     * Saves globals and detaches the current execution instance. A handler paused by the debugger is cancelled first,
     * keeping its earlier writes like any other runtime fault.
     */
    private void detachRuntime(ProgramHost host, String reason) {
        ActiveProgram active = bindings.get(host);
        trace("detach", host, reason + (active == null ? " (not running)" : ""));
        if (active != null && active.runtime.suspended()) {
            active.runtime.abortSuspended();
            status(host).lifecycle("Paused handler cancelled: " + reason.toLowerCase(java.util.Locale.ROOT));
        }
        checkpoint(host);
        active = bindings.remove(host);
        if (active != null) {
            identities.remove(active.runtime.identity());
            scheduler.detach(active.runtime.identity());
            ComputerLifecycle.observer().detached(server, host);
        }
        show(host, ProgramHost.Indicator.OFF);
    }

    private void trace(String event, ProgramHost host, String detail) {
        if (TRACE) {
            LOG.info("[lifecycle] tick {} {} {} at {} removed={}: {}", server.getTickCount(), event, host.executionId(),
                    host.block().getBlockPos(), host.block().isRemoved(), detail);
        }
    }

    private void show(ProgramHost host, ProgramHost.Indicator indicator) {
        indicators.put(host, indicator);
    }

    /** Applies the latest visual state of each changed program to its block. */
    private void showIndicators() {
        if (indicators.isEmpty()) {
            return;
        }
        // Applied from a copy: a block update must not change the map being read.
        var pending = new java.util.ArrayList<>(indicators.entrySet());
        indicators.clear();
        for (var entry : pending) {
            if (!entry.getKey().block().isRemoved()) {
                entry.getKey().indicate(entry.getValue());
            }
        }
    }

    /** Queues a lifecycle change requested from inside a running program. Returns false when the queue is full. */
    boolean request(Runnable change) {
        requireServerThread();
        if (requests.size() >= MAX_REQUESTS) {
            return false;
        }
        requests.add(change);
        return true;
    }

    void tick() {
        requireServerThread();
        if (stopping) {
            return;
        }
        long started = System.nanoTime();
        try {
            tickNow();
        } finally {
            latestNanos = System.nanoTime() - started;
        }
    }

    /**
     * Scheduler figures for diagnostics and benchmarks. Event and fault totals are summed over running programs, so
     * the call costs one step per program.
     */
    ComputerLifecycle.Metrics metrics() {
        long handlers = 0;
        long faults = 0;
        long dropped = 0;
        int queued = 0;
        for (ActiveProgram active : bindings.values()) {
            var stats = active.runtime.statistics();
            handlers += stats.handlers();
            faults += stats.faults();
            dropped += stats.dropped();
            queued += stats.pending();
        }
        return new ComputerLifecycle.Metrics(bindings.size(), pending.size(), queued, latestWork, TICK_BUDGET, latestNanos,
                handlers, faults, dropped);
    }

    private void tickNow() {
        while (!requests.isEmpty()) {
            try {
                requests.removeFirst().run();
            } catch (RuntimeException failure) {
                LOG.warn("Program lifecycle request failed: {}", failure.getMessage());
            }
        }
        // An operator NBT edit or a drive change can replace a program while the block stays loaded.
        for (ProgramHost host : List.copyOf(bindings.keySet())) {
            if (host.block().isRemoved()) {
                unload(host);
            } else if (bindings.get(host).revision != host.revision()) {
                detachRuntime(host, "Program replaced");
                enqueue(host);
            }
        }
        Iterator<ProgramHost> queued = pending.iterator();
        int activatedSource = 0;
        for (int count = 0; count < ACTIVATIONS_PER_TICK && queued.hasNext()
                && activatedSource < ACTIVATION_SOURCE_PER_TICK; count++) {
            if (bindings.size() == scheduler.capacity()) {
                break;
            }
            ProgramHost host = queued.next();
            queued.remove();
            if (!host.block().isRemoved()) {
                activatedSource += activate(host);
            }
        }
        dispatch();
        debugging.tick(server);
        ComputerLifecycle.observer().ticked(server);
        showIndicators();
    }

    /** Activates a queued program and returns how many source characters it parsed. */
    private int activate(ProgramHost host) {
        ComputerStatus status = status(host);
        try {
            ProgramHost.Program program = host.program();
            if (program.unavailable() != null || !program.enabled()) {
                show(host, ProgramHost.Indicator.OFF);
                return 0;
            }
            String identity = host.executionId();
            if (identities.containsKey(identity)) {
                LOG.warn("Duplicate program ID {} at {}", identity, host.block().getBlockPos());
                status.rejected("Another loaded program has the same ID");
                show(host, ProgramHost.Indicator.ERROR);
                return 0;
            }
            StateStore store = new StateStore() {
                @Override
                public Optional<byte[]> load(String controller) throws IOException {
                    return Optional.ofNullable(host.program().snapshot());
                }

                @Override
                public void save(String controller, byte[] snapshot) throws IOException {
                    host.publishSnapshot(snapshot);
                }
            };
            var hosts = host.hosts((ServerLevel) host.block().getLevel());
            var attachment = scheduler.addEmbedded(identity, new SourceText(identity, identity + ".fsig", program.source()),
                    HANDLER_LIMITS, host.capabilities(), line -> output(status, identity, line), store, hosts);
            if (attachment.controller() == null) {
                var first = attachment.diagnostics().getFirst();
                LOG.warn("Program {} rejected at {}: {} ({})", identity, first.span().start(), first.message(),
                        attachment.diagnostics().size());
                status.rejected(first, attachment.diagnostics().size());
                show(host, ProgramHost.Indicator.ERROR);
                return program.source().length();
            }
            trace("activate", host, "revision " + host.revision() + ", restored globals "
                    + (program.snapshot() == null ? 0 : program.snapshot().length) + " bytes");
            ActiveProgram active = new ActiveProgram(host, attachment.controller());
            bindings.put(host, active);
            identities.put(identity, active);
            status.activated();
            show(host, ProgramHost.Indicator.RUNNING);
            debugging.activated(host, attachment.controller());
            host.activated(server);
            ComputerLifecycle.observer().attached(server, host, attachment.controller());
            return program.source().length();
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            LOG.warn("Program at {} could not activate: {}", host.block().getBlockPos(), error.getMessage());
            status.rejected(error.getMessage() == null ? "Activation failed" : error.getMessage());
            show(host, ProgramHost.Indicator.ERROR);
            return 0;
        }
    }

    private void output(ComputerStatus status, String identity, String line) {
        status.console.add(ProgramConsole.Kind.OUTPUT, line);
        String logged = status.serverLogLine(server.getTickCount(), line);
        if (logged != null) {
            LOG.info("[{}] {}", identity, logged);
        }
    }

    /** The running program with this execution identity, or null. */
    ProgramHost host(String id) {
        ActiveProgram active = identities.get(id);
        return active == null ? null : active.host;
    }

    /** Running programs, for operator diagnostics. */
    List<ProgramHost> hosts() {
        return List.copyOf(bindings.keySet());
    }

    boolean emit(String id, String name, java.util.List<Value> arguments, Value payload) {
        requireServerThread();
        ActiveProgram active = identities.get(id);
        return active != null && !active.host.block().isRemoved() && active.revision == active.host.revision()
                && active.runtime.emit(name, arguments, payload);
    }

    /**
     * Validates a program against a registry and optional saved globals without running it.
     *
     * @throws IllegalArgumentException with the first located problem, or {@link ProgramEditing#INCOMPATIBLE_GLOBALS}
     */
    static void validate(String name, String source, HostRegistry hosts, Set<String> capabilities, byte[] snapshot)
            throws IOException {
        var analysis = Resolver.resolve(Parser.parse(new SourceText(name, name + ".fsig", source)), hosts);
        if (!analysis.diagnostics().isEmpty()) {
            var first = analysis.diagnostics().getFirst();
            var at = first.span().start();
            throw new IllegalArgumentException(at.line() + ":" + at.column() + " " + first.message());
        }
        var validation = new dev.wiktorfila.filasignal.core.runtime.execution.Interpreter(
                analysis.program().orElseThrow(), HANDLER_LIMITS, ignored -> {}, capabilities);
        if (snapshot != null) {
            validation.restoreGlobals(snapshot, null);
        }
        var prepared = validation.prepareEvents();
        if (prepared.issue().isPresent()) {
            var diagnostic = prepared.issue().orElseThrow().diagnostic();
            if (diagnostic.code() == dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code.PERSISTENCE_FAILURE) {
                throw new IllegalArgumentException(ProgramEditing.INCOMPATIBLE_GLOBALS + ". " + diagnostic.message());
            }
            throw new IllegalArgumentException(diagnostic.message());
        }
    }

    void program(ProgramHost host, String source, boolean reset, boolean apply) throws IOException {
        requireServerThread();
        ProgramHost.Program current = host.program();
        if (current.unavailable() != null) {
            throw new IllegalArgumentException(current.unavailable());
        }
        if (source.length() > ProgramEditing.MAX_SOURCE || source.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) {
            throw new IllegalArgumentException("Program exceeds the source limit or contains malformed Unicode");
        }
        ActiveProgram active = bindings.get(host);
        // Validation while paused checks against the last saved globals instead of cancelling the paused handler.
        if (apply || active == null || !active.runtime.suspended()) {
            if (apply && active != null && active.runtime.suspended()) {
                active.runtime.abortSuspended();
                status(host).lifecycle("Paused handler cancelled: new program installed");
            }
            checkpoint(host);
            if (!reset && active != null && active.dirty) {
                throw new IOException("Could not checkpoint existing globals");
            }
        }
        validate(host.executionId(), source, host.hosts((ServerLevel) host.block().getLevel()), host.capabilities(),
                reset ? null : host.program().snapshot());
        if (!apply) {
            return;
        }
        host.install(source, reset);
        detachRuntime(host, "New program installed");
        ComputerStatus status = status(host);
        status.programChanged();
        status.lifecycle(reset ? "New program installed with fresh globals" : "New program installed");
        if (host.program().enabled()) {
            enqueue(host);
        }
    }

    void control(ProgramHost host, boolean enabled, String authorizedBy) throws IOException {
        control(host, enabled, authorizedBy, null, null);
    }

    void control(ProgramHost host, boolean enabled, String authorizedBy, UUID authority, String reason) throws IOException {
        requireServerThread();
        ProgramHost.Program current = host.program();
        if (enabled && current.unavailable() != null) {
            throw new IllegalArgumentException(current.unavailable());
        }
        var active = bindings.get(host);
        if (active != null && active.runtime.suspended()) {
            active.runtime.abortSuspended();
            status(host).lifecycle("Paused handler cancelled: " + (enabled ? "restart" : "stop"));
        }
        checkpoint(host);
        if (active != null && active.dirty) {
            throw new IOException("Could not checkpoint existing globals");
        }
        detachRuntime(host, enabled ? "Restart" : "Stop");
        host.enable(enabled, authorizedBy, authority);
        host.stopped(server);
        status(host).lifecycle(reason != null ? reason : enabled ? "Restart requested" : "Program stopped");
        if (enabled) {
            enqueue(host);
        }
    }

    /** Detaches a program without changing whether it should run, for example before its drive is removed. */
    void release(ProgramHost host, String reason) {
        requireServerThread();
        pending.remove(host);
        if (bindings.containsKey(host)) {
            detachRuntime(host, reason);
            host.stopped(server);
            status(host).lifecycle(reason);
        }
        debugging.forget(server, host);
    }

    String budgetSummary() {
        return "Scheduler  " + latestWork + " / " + TICK_BUDGET + " ops last tick";
    }

    String lastLog(ProgramHost host) {
        ComputerStatus status = statuses.get(host);
        return status == null ? "" : status.console.latest(ProgramConsole.Kind.OUTPUT);
    }

    /** The activation rejection when the program could not start, otherwise the latest runtime fault. */
    String lastFault(ProgramHost host) {
        ComputerStatus status = statuses.get(host);
        if (status == null) {
            return "";
        }
        if (status.rejection() != null && !bindings.containsKey(host)) {
            return "Rejected: " + status.rejection().located();
        }
        return status.fault() == null ? "" : status.fault().located();
    }

    ComputerStatus status(ProgramHost host) {
        return statuses.computeIfAbsent(host, ignored -> new ComputerStatus());
    }

    /** The host's current execution instance, or null when it is not running. */
    Simulation.Controller runtime(ProgramHost host) {
        ActiveProgram active = bindings.get(host);
        return active == null ? null : active.runtime;
    }

    /** The busiest running programs by the work of their last handler, for operator telemetry. */
    List<String> busiest(int limit) {
        var rows = new ArrayList<ActiveProgram>(bindings.values());
        rows.sort(Comparator.comparingLong((ActiveProgram active) -> active.runtime.statistics().peakOperations()).reversed());
        var lines = new ArrayList<String>();
        for (ActiveProgram active : rows.subList(0, Math.min(limit, rows.size()))) {
            var stats = active.runtime.statistics();
            var pos = active.host.block().getBlockPos();
            lines.add(active.host.displayName() + " " + pos.toShortString() + ": last " + stats.lastOperations() + ", peak "
                    + stats.peakOperations() + " ops, " + stats.handlers() + " handlers, " + stats.faults() + " faults, "
                    + stats.dropped() + " dropped");
        }
        return lines;
    }

    int running() {
        return bindings.size();
    }

    private void dispatch() {
        Simulation.Tick tick = scheduler.tick();
        latestWork = tick.operations();
        for (String identity : tick.dispatchedControllers()) {
            ActiveProgram active = identities.get(identity);
            active.dirty = true;
            active.host.block().setChanged();
        }
        for (Simulation.Failure failure : tick.failures()) {
            ActiveProgram active = identities.get(failure.controller());
            status(active.host).fault(failure.issue());
            show(active.host, ProgramHost.Indicator.ERROR);
            ComputerLifecycle.observer().failed(server, active.host, failure.issue().diagnostic().message());
            // Preserve the first runtime fault per attachment. A broken timer can otherwise flood server logs.
            if (!active.reportedFault) {
                active.reportedFault = true;
                var diagnostic = failure.issue().diagnostic();
                LOG.warn("Program {} at {}: {} {}", failure.controller(), diagnostic.span().start(),
                        diagnostic.code(), diagnostic.message());
            }
        }
    }

    /** Saves dirty globals. A paused handler keeps the program dirty until it finishes or is cancelled. */
    void checkpoint(ProgramHost host) {
        requireServerThread();
        ActiveProgram active = bindings.get(host);
        if (active == null || !active.dirty || active.revision != host.revision() || active.runtime.suspended()) {
            return;
        }
        try {
            active.runtime.save();
            active.dirty = false;
            active.reportedSaveFailure = false;
        } catch (IOException error) {
            if (!active.reportedSaveFailure) {
                active.reportedSaveFailure = true;
                status(host).lifecycle("Globals could not be saved: " + error.getMessage());
                LOG.warn("Program {} snapshot failed; keeping the prior record: {}",
                        active.runtime.identity(), error.getMessage());
            }
        }
    }

    void stop() {
        requireServerThread();
        stopping = true;
        pending.clear();
        requests.clear();
        debugging.closeAll();
        for (ActiveProgram active : List.copyOf(bindings.values())) {
            if (active.revision == active.host.revision() && !active.host.block().isRemoved()) {
                active.runtime.shutdown();
            } else {
                unload(active.host);
            }
        }
        // The final dispatch shares the normal server budget. Undispatched shutdown work is dropped.
        dispatch();
        for (ProgramHost host : List.copyOf(bindings.keySet())) {
            unload(host);
        }
    }

    void unloadLevel(ServerLevel level) {
        requireServerThread();
        pending.removeIf(host -> host.block().getLevel() == level);
        for (ProgramHost host : List.copyOf(bindings.keySet())) {
            if (host.block().getLevel() == level) {
                unload(host);
            }
        }
    }

    boolean active(ProgramHost host) {
        requireServerThread();
        return bindings.containsKey(host);
    }

    // Debugger operations. The caller has authorized the player for this program.

    String debug(ServerPlayer player, ProgramHost host, net.minecraft.core.BlockPos requestPos, String target, int action,
                 String argument) {
        requireServerThread();
        if (action == EditorNetwork.Request.DEBUG_ATTACH) {
            debugging.attach(host, player, requestPos, target, server.getTickCount());
            return "Debugger attached";
        }
        Debugger debugger = debugging.monitored(host, player.getUUID()).debugger;
        return switch (action) {
            case EditorNetwork.Request.DEBUG_PAUSE -> {
                debugger.requestPause();
                yield debugger.suspended() ? "Already paused" : "Pausing at the next statement";
            }
            case EditorNetwork.Request.DEBUG_RESUME -> debugger.requestContinue(false) ? "Resuming" : "Pause request withdrawn";
            case EditorNetwork.Request.DEBUG_STEP -> debugger.requestContinue(true) ? "Stepping to the next statement"
                    : "Pausing at the next statement";
            case EditorNetwork.Request.DEBUG_BREAKPOINTS -> {
                debugger.setBreakpoints(ProgramDebugging.breakpoints(argument));
                int count = debugger.breakpoints().size();
                yield count == 0 ? "Breakpoints cleared" : count + (count == 1 ? " breakpoint set" : " breakpoints set");
            }
            default -> throw new IllegalArgumentException("Unknown debugger action");
        };
    }

    void detachDebugger(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        requireServerThread();
        debugging.detach(player, pos);
    }

    record State(int code, String headline) {
    }

    State state(ProgramHost host) {
        ActiveProgram active = bindings.get(host);
        var session = debugging.session(host);
        Debugger debugger = session == null ? null : session.debugger;
        ComputerStatus status = statuses.get(host);
        if (debugger != null && debugger.suspended()) {
            var pause = debugger.pause().orElseThrow();
            return new State(EditorNetwork.DebugState.PAUSED, "Paused at line " + pause.line() + " ("
                    + pause.reason().name().toLowerCase(java.util.Locale.ROOT) + ")"
                    + (debugger.continueRequested() ? " · resuming" : ""));
        }
        if (active != null) {
            if (debugger != null && debugger.pauseRequested()) {
                return new State(EditorNetwork.DebugState.PAUSING, "Running · pauses at the next statement");
            }
            int queued = active.runtime.pending();
            String note = ComputerLifecycle.observer().note(server, host);
            return new State(EditorNetwork.DebugState.RUNNING, (queued == 0 ? "Running · waiting for events"
                    : "Running · " + queued + " queued " + (queued == 1 ? "event" : "events"))
                    + (note.isEmpty() ? "" : " · " + note));
        }
        ProgramHost.Program program;
        try {
            program = host.program();
        } catch (IOException corrupt) {
            return new State(EditorNetwork.DebugState.REJECTED, "Unreadable program record");
        }
        if (program.unavailable() != null) {
            return new State(EditorNetwork.DebugState.STOPPED, program.unavailable());
        }
        if (!program.enabled()) {
            return new State(EditorNetwork.DebugState.STOPPED, host instanceof ComputerBlockEntity ? "Stopped" : "Ready · stopped");
        }
        if (pending.contains(host)) {
            return new State(EditorNetwork.DebugState.STARTING, "Starting");
        }
        if (status != null && status.rejection() != null) {
            return new State(EditorNetwork.DebugState.REJECTED, "Not running: " + status.rejection().located());
        }
        return new State(EditorNetwork.DebugState.STOPPED, "Not running");
    }

    EditorNetwork.DebugState debugView(ProgramHost host, net.minecraft.core.BlockPos pos, UUID requestId, boolean success,
                                       String message) {
        State state = state(host);
        ComputerStatus status = statuses.get(host);
        ComputerStatus.Problem problem = status == null ? null
                : state.code() == EditorNetwork.DebugState.REJECTED && status.rejection() != null ? status.rejection()
                : status.fault();
        var session = debugging.session(host);
        Debugger debugger = session == null ? null : session.debugger;
        var pause = debugger == null ? Optional.<Debugger.Pause>empty() : debugger.pause();
        var console = status == null ? List.<EditorNetwork.ConsoleLine>of() : status.console.entries().stream()
                .map(entry -> new EditorNetwork.ConsoleLine(entry.kind().ordinal(), entry.text())).toList();
        var view = new EditorNetwork.Status(state.code(), EditorNetwork.shorten(state.headline(), EditorNetwork.STATE),
                problem == null ? "" : EditorNetwork.shorten(problem.message(), 500),
                problem == null ? 0 : problem.line(), problem == null ? 0 : problem.column(),
                problem == null ? List.of() : frames(problem.frames()), statistics(host));
        return new EditorNetwork.DebugState(pos, requestId, success,
                EditorNetwork.shorten(message, 500), view, pause.map(ServerComputers::pause).orElse(EditorNetwork.Pause.NONE),
                debugger == null ? List.of() : debugger.breakpoints(), console);
    }

    String statistics(ProgramHost host) {
        ActiveProgram active = bindings.get(host);
        String scheduler = "scheduler " + latestWork + " / " + TICK_BUDGET + " ops last tick";
        if (active == null) {
            return "Not running · " + scheduler;
        }
        var stats = active.runtime.statistics();
        return "Last handler " + stats.lastOperations() + " / " + stats.budget() + " ops · peak " + stats.peakOperations()
                + " · " + stats.handlers() + " handlers · " + stats.faults() + " faults · " + stats.dropped()
                + " dropped events · " + scheduler;
    }

    private static EditorNetwork.Pause pause(Debugger.Pause pause) {
        var variables = pause.variables().stream().limit(EditorNetwork.MAX_VARIABLES).map(variable ->
                new EditorNetwork.Variable(variable.scope(), EditorNetwork.shorten(variable.name(), EditorNetwork.Variable.NAME),
                        EditorNetwork.shorten(variable.value(), EditorNetwork.Variable.VALUE))).toList();
        return new EditorNetwork.Pause(pause.line(), pause.column(), pause.reason().name().toLowerCase(java.util.Locale.ROOT),
                frames(pause.frames()), variables);
    }

    private static List<EditorNetwork.Frame> frames(List<Debugger.Frame> frames) {
        return frames.stream().limit(EditorNetwork.MAX_FRAMES)
                .map(frame -> new EditorNetwork.Frame(EditorNetwork.shorten(frame.function(), EditorNetwork.Frame.NAME),
                        frame.line())).toList();
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Computer lifecycle requires the Minecraft server thread");
        }
    }

    private static final class ActiveProgram {
        private final ProgramHost host;
        private final Simulation.Controller runtime;
        private final long revision;
        private boolean dirty;
        private boolean reportedFault;
        private boolean reportedSaveFailure;

        private ActiveProgram(ProgramHost host, Simulation.Controller runtime) {
            this.host = host;
            this.runtime = runtime;
            this.revision = host.revision();
        }
    }
}
