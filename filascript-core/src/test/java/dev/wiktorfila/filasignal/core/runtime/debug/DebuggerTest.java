package dev.wiktorfila.filasignal.core.runtime.debug;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Slot;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Type;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.event.Simulation;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.Instrumentation.Variable;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.runtime.value.ValueSummary;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerTest {
    private static final ExecutionLimits LIMITS = new ExecutionLimits(32, 1_000);

    private static final String COUNTER = """
            global count = 0
            const label = "counter"
            on startup {
                step = 1
                count += step
                count += bump(10)
            }
            fn bump(amount) {
                doubled = amount * 2
                return doubled
            }
            fn inspect() { return count }
            """;

    @Test
    void breakpointPausesOnlyItsControllerAndResumesWithOriginalBudget() {
        var sim = new Simulation(5_000, 8);
        var output = new ArrayList<String>();
        var debugged = add(sim, "debugged", COUNTER, output);
        var other = add(sim, "other", COUNTER, output);
        var debugger = new Debugger();
        debugged.debug(debugger);
        debugger.setBreakpoints(List.of(6));

        var first = sim.tick();
        assertEquals(List.of("debugged", "other"), first.dispatchedControllers());
        assertTrue(debugged.suspended());
        var pause = debugger.pause().orElseThrow();
        assertEquals(Debugger.Reason.BREAKPOINT, pause.reason());
        assertEquals(6, pause.line());
        assertEquals(List.of(new Debugger.Frame("startup", 6)), pause.frames());
        assertTrue(pause.variables().contains(new Variable("local", "step", "1")));
        assertTrue(pause.variables().contains(new Variable("global", "count", "1")));
        assertTrue(pause.variables().contains(new Variable("const", "label", "\"counter\"")));
        assertEquals(List.of(new Num(21)), other.interpreter().invoke("inspect", List.of()).returns().values());

        // A paused controller is skipped until a continuation is requested.
        assertTrue(sim.tick().dispatchedControllers().isEmpty());
        assertTrue(debugged.suspended());

        assertTrue(debugger.requestContinue(false));
        var resumed = sim.tick();
        assertEquals(List.of("debugged"), resumed.dispatchedControllers());
        assertTrue(resumed.failures().isEmpty());
        assertFalse(debugged.suspended());
        assertEquals(1, debugged.statistics().handlers());
        assertTrue(debugged.statistics().lastOperations() > 0);
        assertEquals(List.of(new Num(21)), debugged.interpreter().invoke("inspect", List.of()).returns().values());
    }

    @Test
    void stepEntersCalledFunctionsAndReportsTheCallStack() {
        var sim = new Simulation(5_000, 8);
        var debugged = add(sim, "debugged", COUNTER, new ArrayList<>());
        var debugger = new Debugger();
        debugged.debug(debugger);
        debugger.setBreakpoints(List.of(6));
        sim.tick();
        assertTrue(debugger.requestContinue(true));
        sim.tick();
        var inside = debugger.pause().orElseThrow();
        assertEquals(Debugger.Reason.STEP, inside.reason());
        assertEquals(9, inside.line());
        assertEquals(List.of(new Debugger.Frame("bump", 9), new Debugger.Frame("startup", 6)), inside.frames());
        assertTrue(inside.variables().contains(new Variable("local", "amount", "10")));
        debugger.requestContinue(true);
        sim.tick();
        assertEquals(10, debugger.pause().orElseThrow().line());
        assertTrue(debugger.pause().orElseThrow().variables().contains(new Variable("local", "doubled", "20")));
        debugger.requestContinue(false);
        assertTrue(sim.tick().failures().isEmpty());
        assertFalse(debugged.suspended());
    }

    @Test
    void pauseRequestWaitsForTheNextHandlerAndTimersDoNotBurst() {
        var sim = new Simulation(5_000, 8);
        String source = """
                global ticks = 0
                on timer.every(1) {
                    ticks++
                }
                fn inspect() { return ticks }
                """;
        var controller = add(sim, "timer", source, new ArrayList<>());
        var debugger = new Debugger();
        controller.debug(debugger);
        debugger.requestPause();
        sim.tick();
        assertEquals(Debugger.Reason.PAUSE, debugger.pause().orElseThrow().reason());
        assertEquals(3, debugger.pause().orElseThrow().line());
        for (int i = 0; i < 10; i++) {
            sim.tick();
        }
        assertEquals(0, controller.pending(), "Timers queued work while paused");
        assertTrue(debugger.requestContinue(false));
        sim.tick();
        assertFalse(controller.suspended());
        assertEquals(List.of(new Num(1)), controller.interpreter().invoke("inspect", List.of()).returns().values());
    }

    @Test
    void budgetsStillApplyAcrossPausesAndDetachCancelsKeepingEarlierWrites() {
        var sim = new Simulation(5_000, 8);
        String source = """
                global marks = 0
                on startup {
                    marks = 1
                    while true {
                        marks += 1
                    }
                }
                on custom.go {
                    marks = 100
                    marks = 200
                }
                fn inspect() { return marks }
                """;
        var controller = add(sim, "loop", source, new ArrayList<>());
        var debugger = new Debugger();
        controller.debug(debugger);
        debugger.setBreakpoints(List.of(5));
        sim.tick();
        assertTrue(controller.suspended());
        debugger.setBreakpoints(List.of());
        debugger.requestContinue(false);
        var exhausted = sim.tick();
        assertEquals(Code.EXECUTION_BUDGET, exhausted.failures().getFirst().issue().diagnostic().code());
        assertTrue(exhausted.operations() <= LIMITS.operations());

        debugger.setBreakpoints(List.of(10));
        assertTrue(controller.emit("custom.go", List.of(), Nil.INSTANCE));
        sim.tick();
        assertTrue(controller.suspended());
        var cancelled = controller.debug(null);
        assertEquals(Code.CANCELLED, cancelled.orElseThrow().issue().orElseThrow().diagnostic().code());
        assertFalse(controller.suspended());
        assertEquals(List.of(new Num(100)), controller.interpreter().invoke("inspect", List.of()).returns().values());
    }

    @Test
    void hostCallsAndOutputRunOnTheOwnerThreadWhileDebugging() {
        var owner = Thread.currentThread();
        var threads = new ArrayList<Thread>();
        var hosts = new HostRegistry(List.of(new HostRegistry.Function("probe.mark",
                List.of(new Slot("value", Set.of(Type.NUMBER))), List.of(new Slot("value", Set.of(Type.NUMBER))),
                "Records the calling thread.", Set.of(), 1, (arguments, context) -> {
                    threads.add(Thread.currentThread());
                    context.charge(2);
                    return List.of(arguments.getFirst());
                })));
        var sim = new Simulation(5_000, 8);
        var output = new ArrayList<String>();
        var registration = assertDoesNotThrow(() -> sim.addEmbedded("host", new SourceText("host", "host.fsig", """
                on startup {
                    value = probe.mark(4)
                    print(value)
                }
                """), LIMITS, Set.of(), line -> {
            threads.add(Thread.currentThread());
            output.add(line);
        }, null, hosts));
        var controller = registration.controller();
        var debugger = new Debugger();
        controller.debug(debugger);
        debugger.setBreakpoints(List.of(3));
        sim.tick();
        assertTrue(controller.suspended());
        debugger.requestContinue(false);
        assertTrue(sim.tick().failures().isEmpty());
        assertEquals(List.of("4"), output);
        assertEquals(List.of(owner, owner), threads);
    }

    @Test
    void shutdownCancelsAPausedHandlerAndAllowsSaving() throws IOException {
        var saved = new ArrayList<byte[]>();
        var store = new dev.wiktorfila.filasignal.core.runtime.persistence.StateStore() {
            @Override public Optional<byte[]> load(String controller) { return Optional.empty(); }
            @Override public void save(String controller, byte[] snapshot) { saved.add(snapshot); }
        };
        var sim = new Simulation(5_000, 8);
        var controller = sim.add("saved", new SourceText("saved", "saved.fsig", COUNTER), LIMITS, Set.of(),
                ignored -> {}, store, null).controller();
        var debugger = new Debugger();
        controller.debug(debugger);
        debugger.setBreakpoints(List.of(5));
        sim.tick();
        assertTrue(controller.suspended());
        assertThrows(IOException.class, controller::save);
        controller.shutdown();
        assertFalse(controller.suspended());
        controller.save();
        assertEquals(1, saved.size());
        assertEquals(1, controller.statistics().faults());
    }

    @Test
    void breakpointsAreValidatedAndStatementLinesExcludeInitializers() {
        var debugger = new Debugger();
        assertThrows(IllegalArgumentException.class, () -> debugger.setBreakpoints(List.of(0)));
        var many = new ArrayList<Integer>();
        for (int line = 1; line <= Debugger.MAX_BREAKPOINTS + 1; line++) {
            many.add(line);
        }
        assertThrows(IllegalArgumentException.class, () -> debugger.setBreakpoints(many));
        debugger.setBreakpoints(List.of(4, 2, 4));
        assertEquals(List.of(2, 4), debugger.breakpoints());
        var lines = StatementLines.of(Parser.parse(new SourceText("lines", "lines.fsig", COUNTER)).program());
        assertEquals("{4, 5, 6, 9, 10, 12}", lines.toString());
    }

    @Test
    void valueSummariesAreBoundedAndCycleSafe() {
        var list = new ListValue(List.of());
        for (int i = 0; i < 4000; i++) {
            list.insert(i, new Num(i));
        }
        String large = ValueSummary.describe(list, 120);
        assertTrue(large.length() <= 120 && large.startsWith("[0, 1"), large);
        assertTrue(large.contains("3992 more"), large);

        var cyclic = new ListValue(List.of(new Text("a\nb")));
        var dict = new DictValue();
        dict.put("self", cyclic);
        cyclic.insert(1, dict);
        assertEquals("[\"a\\nb\", {self: <cycle>}]", ValueSummary.describe(cyclic, 120));

        var handle = new HostHandle("Inventory", java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        assertEquals("(Inventory handle, nil)", ValueSummary.describe(new TupleValue(List.of(handle, Value.Nil.INSTANCE)), 64));
        String deep = ValueSummary.describe(new ListValue(List.of(new ListValue(List.of(new ListValue(List.of(
                new ListValue(List.of(new Num(1))))))))), 64);
        assertEquals("[[[[… 1 item]]]]", deep);
        String emoji = ValueSummary.describe(new Text("🚀".repeat(40)), 16);
        assertFalse(Character.isHighSurrogate(emoji.charAt(emoji.length() - 2)), emoji);
    }

    @Test
    void consoleKeepsABoundedHistory() {
        var console = new ProgramConsole();
        for (int i = 0; i < ProgramConsole.CAPACITY + 5; i++) {
            console.add(ProgramConsole.Kind.OUTPUT, "line " + i);
        }
        console.add(ProgramConsole.Kind.ERROR, "x".repeat(500) + "\nnext");
        assertEquals(ProgramConsole.CAPACITY, console.entries().size());
        assertEquals("line 6", console.entries().getFirst().text());
        assertEquals(ProgramConsole.MAX_LINE, console.latest(ProgramConsole.Kind.ERROR).length());
        assertEquals(ProgramConsole.CAPACITY + 6, console.sequence());
    }

    private static Simulation.Controller add(Simulation sim, String name, String source, List<String> output) {
        var registration = sim.add(name, new SourceText(name, name + ".fsig", source), LIMITS, Set.of(), output::add);
        assertTrue(registration.diagnostics().isEmpty(), registration.diagnostics()::toString);
        return registration.controller();
    }
}
