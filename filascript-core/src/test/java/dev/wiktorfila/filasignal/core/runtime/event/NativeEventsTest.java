package dev.wiktorfila.filasignal.core.runtime.event;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.*;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.execution.ExecutionLimits;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Host enums, device-reference constants, typed event handlers and the (value, err) conveniences. */
class NativeEventsTest {
    private static final UUID SCOPE = UUID.randomUUID();
    private static final Slot NAME = new Slot("name", Set.of(Type.STRING));
    private static final Slot DETECTOR = new Slot("detector", Set.of(Type.HANDLE), "Detector");
    private static final Slot ERROR = new Slot("error", Set.of(Type.ERROR, Type.NIL));

    private static HostRegistry registry(List<String> calls) {
        var named = new Function("detectors.named", List.of(NAME), List.of(DETECTOR), "Refer to a detector by name.",
                Set.of(), 4, (args, ctx) -> List.of(new HostHandle("Detector", SCOPE, new UUID(0, 0),
                ((Text) args.getFirst()).value()))).asReference();
        var count = new Function("Detector.count", List.of(DETECTOR),
                List.of(new Slot("count", Set.of(Type.NUMBER, Type.NIL)), ERROR), "Count entities.", Set.of(), 4,
                (args, ctx) -> {
                    String name = ((HostHandle) args.getFirst()).reference();
                    calls.add("count " + name);
                    return name.equals("broken") ? List.of(Nil.INSTANCE, new ErrorValue("device.unavailable",
                            "The device is disconnected or its area isn't loaded")) : List.of(new Num(3), Nil.INSTANCE);
                });
        var enter = new Event("entityEnter", List.of(), List.of("entity"), "Runs when an entity enters the range.");
        var type = new ObjectType("Detector", Map.of(), Map.of("count", count), Map.of("entityEnter", enter));
        var face = new Event("device.inputChanged", List.of(), List.of("face", "power"), "Runs when an input changes.");
        var custom = new Event("events.custom", List.of(NAME), List.of("value", "sender"), "Runs for a custom event.");
        var weather = new Function("device.weather", List.of(), List.of(new Slot("weather", Set.of(Type.ENUM, Type.NIL)), ERROR),
                "Read the weather.", Set.of(), 2, (args, ctx) -> List.of(new EnumMember("Weather", "RAIN"), Nil.INSTANCE));
        return new HostRegistry(List.of(named, weather), List.of(type),
                Map.of("Direction", List.of("NORTH", "SOUTH"), "Weather", List.of("CLEAR", "RAIN")), List.of(face, custom));
    }

    private static List<Diagnostic> problems(String source) {
        return Resolver.resolve(Parser.parse(new SourceText("events", "events.fsig", source)), registry(new ArrayList<>()))
                .diagnostics();
    }

    @Test
    void routesSourceAndLocalEventsToTypedHandlerParameters() throws Exception {
        var calls = new ArrayList<String>();
        var output = new ArrayList<String>();
        var simulation = new Simulation(10_000, 8);
        var added = simulation.addEmbedded("program", new SourceText("program", "program.fsig", """
                const gate = detectors.named("gate")
                global seen = []

                on gate.entityEnter(entity) {
                    seen.append(entity.name)
                    print(gate.count())
                }

                on device.inputChanged(face, power) {
                    if face is Direction.SOUTH and power > 0 { print("south on") }
                }

                on events.custom("door.open", value) {
                    print(value.by)
                }
                """), new ExecutionLimits(32, 2000), Set.of(), output::add, null, registry(calls));
        assertTrue(added.diagnostics().isEmpty(), added.diagnostics()::toString);
        var controller = added.controller();
        var subscriptions = controller.subscriptions();
        assertEquals("entityEnter", subscriptions.getFirst().name());
        assertEquals("gate", subscriptions.getFirst().source().reference());
        assertEquals(List.of("entity"), subscriptions.getFirst().parameters());
        assertNull(subscriptions.get(1).source());
        simulation.tick();

        var entity = new DictValue(Map.of("name", new Text("Zombie"), "hostile", new Bool(true)));
        assertTrue(controller.deliver(0, List.of(entity)));
        assertTrue(controller.deliver(0, List.of(entity)));
        assertTrue(controller.emit("device.inputChanged", List.of(), List.of(new EnumMember("Direction", "SOUTH"), new Num(15))));
        assertTrue(controller.emit("events.custom", List.of(new Text("door.open")), List.of(new DictValue(Map.of("by", new Text("Ana"))))));
        assertTrue(controller.emit("events.custom", List.of(new Text("other")), List.of(new Num(1))),
                "An event without a matching handler is not a failure");
        assertFalse(controller.deliver(0, List.of(new HostHandle("Detector", SCOPE, SCOPE))), "A handle travelled in an event");
        assertFalse(controller.deliver(9, List.of()), "An unknown subscription was accepted");
        // Each controller runs one handler per tick.
        for (int i = 0; i < 5; i++) {
            simulation.tick();
        }
        assertEquals(List.of("3", "3", "south on", "Ana"), output);
        assertEquals(List.of("count gate", "count gate"), calls);
    }

    @Test
    void handlerParametersReceiveIndependentCopies() throws Exception {
        var output = new ArrayList<String>();
        var simulation = new Simulation(10_000, 8);
        var added = simulation.addEmbedded("program", new SourceText("program", "program.fsig", """
                global first = nil
                on events.custom("list", value) {
                    value.append(1)
                    print(value.length)
                }
                """), new ExecutionLimits(32, 2000), Set.of(), output::add, null, registry(new ArrayList<>()));
        var controller = added.controller();
        simulation.tick();
        var shared = new ListValue(List.of(new Num(0)));
        controller.emit("events.custom", List.of(new Text("list")), List.of(shared));
        controller.emit("events.custom", List.of(new Text("list")), List.of(shared));
        simulation.tick();
        simulation.tick();
        assertEquals(List.of("2", "2"), output);
        assertEquals(1, shared.size(), "The sender's list was changed by a receiver");
    }

    @Test
    void unwrapsValueOrErrorResultsAndReportsIgnoredFailures() {
        var calls = new ArrayList<String>();
        var output = new ArrayList<String>();
        var program = Resolver.resolve(Parser.parse(new SourceText("calls", "calls.fsig", """
                const gate = detectors.named("gate")
                const broken = detectors.named("broken")
                fn ok() { return gate.count() + 1 }
                fn weather() { return device.weather() is Weather.RAIN }
                fn ignored() {
                    for attempt in [1, 2, 3] {
                        broken.count()
                    }
                    return str(4) + " " + str([1, 2.5]) + " " + str(gate)
                }
                fn stops() { return broken.count() }
                fn explicit() {
                    count, err = broken.count()
                    return count, err.code
                }
                """)), registry(calls)).program().orElseThrow();
        var runtime = new Interpreter(program, new ExecutionLimits(16, 1000), output::add, Set.of());
        assertEquals(List.of(new Num(4)), runtime.invoke("ok", List.of()).returns().values());
        assertEquals(List.of(new Bool(true)), runtime.invoke("weather", List.of()).returns().values());
        assertEquals(List.of(new Text("4 [1, 2.5] Detector(\"gate\")")), runtime.invoke("ignored", List.of()).returns().values());
        assertEquals(1, output.size(), "An ignored failure should be reported once per call site");
        assertTrue(output.getFirst().startsWith("Warning: Detector.count failed: The device is disconnected"), output::toString);
        var stopped = runtime.invoke("stops", List.of()).issue().orElseThrow().diagnostic();
        assertEquals(Code.HOST_FAILURE, stopped.code());
        assertTrue(stopped.message().contains("(device.unavailable)"), stopped.message());
        assertEquals(List.of(Nil.INSTANCE, new Text("device.unavailable")), runtime.invoke("explicit", List.of()).returns().values());
    }

    @Test
    void validatesEventsHandlersAndReferencesStatically() {
        assertTrue(problems("""
                on startup { }
                on timer.every(20) { }
                on device.inputChanged(face) { }
                on events.custom("a.b", value, sender) { }
                const later = detectors.named("x")
                on later.entityEnter(entity) { print(Direction.NORTH) }
                """).isEmpty());
        assertTrue(problems("on device.wobble { }").getFirst().message().startsWith("Unknown event device.wobble"));
        assertTrue(problems("const gate = detectors.named(\"g\")\non gate.wobble(x) { }").getFirst().message()
                .contains("gate has no event named wobble"));
        assertTrue(problems("const gate = 4\non gate.entityEnter(x) { }").getFirst().message().contains("is not a device"));
        assertTrue(problems("on device.inputChanged(face, power, extra) { }").getFirst().message().contains("at most 2 values"));
        assertTrue(problems("on events.custom() { }").getFirst().message().contains("needs 1 filter"));
        assertTrue(problems("on device.inputChanged(\"north\") { }").getFirst().message().contains("plain names"));
        assertTrue(problems("on device.inputChanged(event) { }").stream().anyMatch(p -> p.code() == Code.DUPLICATE_BINDING));
        assertTrue(problems("const x = detectors.count()").stream().anyMatch(p -> p.code() == Code.INVALID_INITIALIZER));
        // A program may declare its own enum with a host enum's name.
        assertTrue(problems("enum Direction { UP }\nfn f() { return Direction.UP }").isEmpty());
        // Permissive registries keep undeclared events with every argument as a filter.
        assertTrue(Resolver.resolve(Parser.parse(new SourceText("p", "p.fsig", "on custom.thing(1, 2) { }"))).diagnostics().isEmpty());
    }

    @Test
    void hostEnumsPersistInGlobalsAndCompareByValue() throws Exception {
        var program = Resolver.resolve(Parser.parse(new SourceText("p", "p.fsig", """
                global facing = Direction.NORTH
                fn turn() { facing = Direction.SOUTH }
                fn check() { return facing is Direction.SOUTH, facing }
                """)), registry(new ArrayList<>())).program().orElseThrow();
        var first = new Interpreter(program, new ExecutionLimits(16, 1000), ignored -> {}, Set.of());
        first.invoke("turn", List.of());
        byte[] saved = first.snapshotGlobals();
        var second = new Interpreter(program, new ExecutionLimits(16, 1000), ignored -> {}, Set.of());
        second.restoreGlobals(saved, null);
        assertEquals(List.of(new Bool(true), new EnumMember("Direction", "SOUTH")), second.invoke("check", List.of()).returns().values());
    }

    @Test
    void boundsStructuredEventValues() {
        var deep = (Value) new Num(1);
        for (int i = 0; i < 12; i++) {
            deep = new ListValue(List.of(deep));
        }
        assertFalse(Interpreter.eventValue(deep));
        assertFalse(Interpreter.eventValue(new ListValue(java.util.Collections.nCopies(600, new Num(1)))));
        assertFalse(Interpreter.eventValue(new Text("x".repeat(20_000))));
        assertTrue(Interpreter.eventValue(new DictValue(Map.of("items", new ListValue(List.of(new Text("iron")))))));
        assertFalse(Interpreter.scalarEventValue(new ListValue(List.of())));
        assertTrue(EnumSet.allOf(Type.class).contains(Type.ENUM));
    }
}
