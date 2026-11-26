package dev.wiktorfila.filasignal.core.runtime.event;

import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    private static final ExecutionLimits LIMITS = new ExecutionLimits(32, 1_000);

    @Test
    void runsLifecycleTimersFilteredEventsAndIndependentGlobals() {
        Simulation sim = new Simulation(3_000, 8);
        String program = """
                global count = 0
                on startup { count++ }
                on shutdown { count += 100 }
                on timer.every(3) { count += 10 }
                on redstone.rising("left") {
                    input, err = redstone.read("left")
                    ok, err = redstone.set("right", input)
                    count += event.payload
                }
                fn inspect() { return count }
                """;
        var first = add(sim, "first", program, Set.of("redstone.read", "redstone.write"), new ArrayList<>());
        var second = add(sim, "second", program, Set.of(), new ArrayList<>());
        assertEquals(2, sim.tick().dispatched());
        assertTrue(first.setInput("left", 7));
        assertTrue(first.setInput("front", 9));
        assertTrue(sim.tick().failures().isEmpty());
        assertEquals(7, first.output("right"));
        assertEquals(0, second.output("right"));
        assertEquals(List.of(new Num(8)), first.interpreter().invoke("inspect", List.of()).returns().values());
        sim.tick();
        assertEquals(List.of(new Num(11)), second.interpreter().invoke("inspect", List.of()).returns().values());
        first.shutdown();
        sim.tick();
        assertEquals(List.of(new Num(118)), first.interpreter().invoke("inspect", List.of()).returns().values());
    }

    @Test
    void isolatesFaultsAndEnforcesHostCapabilitiesAndContracts() {
        Simulation sim = new Simulation(3_000, 8);
        var lines = new ArrayList<String>();
        add(sim, "denied", """
                on startup {
                    value, err = redstone.set("right", 1)
                    log.info(err.code)
                }
                on task.bad { redstone.set("right", true) }
                """, Set.of("log"), lines);
        var other = add(sim, "allowed", """
                on startup {
                    ok, err = redstone.set("right", 16)
                    print(err.code)
                }
                """, Set.of("redstone.write"), lines);
        assertTrue(sim.tick().failures().isEmpty());
        assertEquals(List.of("host.denied", "redstone.value"), lines);
        assertEquals(0, other.output("right"));
    }

    @Test
    void queuesFeedbackLaterAndDropsNewestWhenFull() {
        Simulation sim = new Simulation(1_000, 2);
        var controller = add(sim, "loop", """
                global count = 0
                on custom.loop {
                    count++
                    events.emit("custom.loop", nil)
                    events.emit("custom.loop", nil)
                    events.emit("custom.loop", nil)
                }
                fn inspect() { return count }
                """, Set.of("events.emit"), new ArrayList<>());
        assertTrue(controller.emit("custom.loop", List.of(), Nil.INSTANCE));
        for (int tick = 1; tick <= 5; tick++) {
            var result = sim.tick();
            assertEquals(1, result.dispatched());
            assertTrue(result.failures().isEmpty());
            assertEquals(List.of(new Num(tick)), controller.interpreter().invoke("inspect", List.of()).returns().values());
        }
        assertEquals(2, controller.pending());
        assertTrue(controller.dropped() > 0);
        controller.cancelPending();
        assertEquals(0, sim.tick().dispatched());
    }

    @Test
    void rotatesControllersUnderAGlobalWorkCapAndReportsFaults() {
        Simulation sim = new Simulation(1_000, 4);
        String loop = "on startup { while true {} }";
        add(sim, "one", loop, Set.of(), new ArrayList<>());
        add(sim, "two", loop, Set.of(), new ArrayList<>());
        var first = sim.tick();
        assertEquals(1_000, first.operations());
        assertEquals("one", first.failures().getFirst().controller());
        assertEquals(Code.EXECUTION_BUDGET, first.failures().getFirst().issue().diagnostic().code());
        assertEquals("two", sim.tick().failures().getFirst().controller());
        assertEquals(0, sim.tick().dispatched());
    }

    @Test
    void chargesRetainedControllerStateAcrossRepeatedEvents() {
        Simulation sim = new Simulation(1_000, 4);
        var registration = sim.add("grow", new SourceText("grow", "grow.fsig", """
                global retained = []
                on timer.every(1) { retained.append([1, 2, 3]) }
                fn inspect() { return retained.length }
                """), new ExecutionLimits(32, 1000, 200, 8, 100), Set.of(), ignored -> {});
        assertTrue(registration.diagnostics().isEmpty(), registration.diagnostics()::toString);
        int failures = 0;
        for (int i = 0; i < 50; i++) {
            var result = sim.tick();
            if (!result.failures().isEmpty()) {
                assertEquals(Code.DATA_LIMIT, result.failures().getFirst().issue().diagnostic().code());
                failures++;
            }
        }
        assertTrue(failures > 0 && failures < 50);
    }

    @Test
    void customArgumentsAndPayloadAreVisibleAndBadTimerFailsRegistration() {
        Simulation sim = new Simulation(1_000, 4);
        var failure = sim.add("bad", new SourceText("bad", "bad", "on timer.every(0) {}"), LIMITS, Set.of(), ignored -> {});
        assertNull(failure.controller());
        assertEquals(Code.TYPE_MISMATCH, failure.diagnostics().getFirst().code());
        var logs = new ArrayList<String>();
        var controller = add(sim, "custom", """
                on custom.message("for me") { print(event.arguments[0] + event.payload) }
                """, Set.of(), logs);
        assertTrue(controller.emit("custom.message", List.of(new Text("for you")), new Text("!")));
        assertTrue(controller.emit("custom.message", List.of(new Text("for me")), new Text("!")));
        sim.tick();
        assertEquals(List.of("for me!"), logs);
        assertFalse(controller.emit("startup", List.of(), Nil.INSTANCE));
    }

    @Test
    void embeddedAttachmentUsesItsRegistryAndCanDetachWithoutDispatch() throws java.io.IOException {
        Simulation sim = new Simulation(1_000, 4);
        var hosts = dev.wiktorfila.filasignal.core.host.HostRegistry.EMPTY;
        var unknown = new SourceText("unknown", "unknown.fsig", "on startup { redstone.read(\"left\") }");
        var rejected = sim.addEmbedded("test", unknown, LIMITS, Set.of(), ignored -> {}, null, hosts);
        assertNull(rejected.controller());
        var source = new SourceText("test", "test.fsig", "on startup { print(\"ready\") }");
        var messages = new ArrayList<String>();
        var first = sim.addEmbedded("test", source, LIMITS, Set.of(), messages::add, null, hosts);
        assertTrue(first.diagnostics().isEmpty());
        assertTrue(sim.detach("test"));
        assertEquals(0, sim.tick().dispatched());
        assertEquals(0, first.controller().pending());
        assertFalse(first.controller().emit("custom.event", List.of(), Nil.INSTANCE));
        sim.addEmbedded("test", source, LIMITS, Set.of(), messages::add, null, hosts);
        assertEquals(List.of("test"), sim.tick().dispatchedControllers());
        assertEquals(List.of("ready"), messages);
    }

    private static Simulation.Controller add(Simulation sim, String name, String source, Set<String> capabilities,
                                             List<String> output) {
        var registration = sim.add(name, new SourceText(name, name + ".fsig", source), LIMITS, capabilities, output::add);
        assertTrue(registration.diagnostics().isEmpty(), registration.diagnostics()::toString);
        return registration.controller();
    }
}
