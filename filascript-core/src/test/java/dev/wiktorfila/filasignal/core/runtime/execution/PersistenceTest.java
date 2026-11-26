package dev.wiktorfila.filasignal.core.runtime.execution;

import dev.wiktorfila.filasignal.core.analysis.Resolver;
import dev.wiktorfila.filasignal.core.diagnostic.Diagnostic.Code;
import dev.wiktorfila.filasignal.core.parser.Parser;
import dev.wiktorfila.filasignal.core.runtime.event.Simulation;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateStore;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateException;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.core.source.SourceText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    private static final String GRAPH = """
            enum Mode { OFF, ACTIVE }
            global created = 0
            class Box {
                count: number = 0
                next = nil
                mode: Mode = Mode.ACTIVE
                Box() { created++ }
                fn touch() { self.count++ }
            }
            global left = nil
            global right = nil
            global dict = {}
            global tuple = nil
            global flag = nil
            fn create() {
                local = 7
                left = Box()
                right = left
                left.next = left
                dict["left"] = left
                dict["self"] = dict
                items = []
                tuple = (items,)
                items.append(tuple)
                flag = true
            }
            fn verify() {
                left.touch()
                return right.count, dict.left is left, left.next is left, dict["self"] is dict,
                    tuple[0][0] is tuple, left.mode is Mode.ACTIVE, flag, created
            }
            """;

    @Test
    void restoresClassEnumTupleAndSharedCyclicGraphsWithoutRerunningConstructors() throws IOException {
        Interpreter first = interpreter(GRAPH);
        assertTrue(first.invoke("create", List.of()).issue().isEmpty());
        byte[] snapshot = first.snapshotGlobals();
        assertArrayEquals(snapshot, first.snapshotGlobals());
        Interpreter second = interpreter(GRAPH);
        second.restoreGlobals(snapshot, null);
        assertEquals(List.of(new Num(1), new Bool(true), new Bool(true), new Bool(true),
                        new Bool(true), new Bool(true), new Bool(true), new Num(1)),
                values(second, "verify"));
        assertEquals(new Num(2), values(second, "verify").getFirst());
        assertEquals(new Num(1), values(first, "verify").getFirst());
    }

    @Test
    void preservesExistingValuesAndDefaultsOnlyForNewGlobals() throws IOException {
        Interpreter previous = interpreter("global x: number = 7; fn main() { return x }");
        assertEquals(List.of(new Num(7)), values(previous, "main"));
        Interpreter changed = interpreter("""
                global x: number = 1 / 0
                global newValue = 2
                fn main() { local = 0; local++; return x, newValue, local }
                """);
        changed.restoreGlobals(previous.snapshotGlobals(), null);
        for (int i = 0; i < 2; i++) {
            assertEquals(List.of(new Num(7), new Num(2), new Num(1)), values(changed, "main"));
        }
    }

    @Test
    void rejectsTruncationCorruptionAndClassOrEnumSchemaChanges() throws IOException {
        Interpreter previous = interpreter(GRAPH);
        assertTrue(previous.invoke("create", List.of()).issue().isEmpty());
        byte[] snapshot = previous.snapshotGlobals();
        byte[] corrupt = snapshot.clone();
        corrupt[12] ^= 1;
        for (byte[] bytes : List.of(corrupt, Arrays.copyOf(snapshot, snapshot.length - 1))) {
            Interpreter restored = interpreter(GRAPH);
            restored.restoreGlobals(bytes, null);
            var diagnostic = restored.invoke("verify", List.of()).issue().orElseThrow().diagnostic();
            assertEquals(Code.PERSISTENCE_FAILURE, diagnostic.code());
            assertTrue(diagnostic.message().contains("CORRUPT"));
        }
        Interpreter changed = interpreter(GRAPH.replace("count: number = 0", "count: number = 0\nadded = 1"));
        changed.restoreGlobals(snapshot, null);
        assertTrue(changed.invoke("verify", List.of()).issue().orElseThrow().diagnostic().message().contains("class schema"));
        Interpreter oldEnum = interpreter("enum E { X }; global value = E.X; fn main() {}");
        values(oldEnum, "main");
        Interpreter newEnum = interpreter("enum E { Y }; global value = E.Y; fn main() {}");
        newEnum.restoreGlobals(oldEnum.snapshotGlobals(), null);
        assertTrue(newEnum.invoke("main", List.of()).issue().orElseThrow().diagnostic().message().contains("enum member"));
    }

    @Test
    void requiresExplicitMigrationForRenamesAndValidatesReplacement() throws IOException {
        Interpreter original = interpreter("global old = 9; fn main() {}");
        values(original, "main");
        byte[] old = original.snapshotGlobals();
        Interpreter denied = interpreter("global renamed = 1; fn main() { return renamed }");
        denied.restoreGlobals(old, null);
        assertTrue(denied.invoke("main", List.of()).issue().orElseThrow().diagnostic().message().contains("INCOMPATIBLE"));
        Interpreter migratedState = interpreter("global renamed = 9; fn main() {}");
        values(migratedState, "main");
        denied.restoreGlobals(old, (snapshot, reason) -> {
            assertArrayEquals(old, snapshot);
            assertEquals(StateException.Reason.INCOMPATIBLE, reason.reason());
            return migratedState.snapshotGlobals();
        });
        assertEquals(List.of(new Num(9)), values(denied, "main"));
    }

    @Test
    void checksSnapshotSizeAndUnsupportedValuesBeforeStorage() throws IOException {
        assertEquals(StateException.Reason.UNSUPPORTED, assertThrows(StateException.class,
                () -> GlobalSnapshot.encode(Map.of("invalid", new GlobalSnapshot.Global("", Namespace.JSON)))).reason());
        Interpreter oversized = interpreter("global value = \"" + "🚀".repeat(70_000) + "\"; fn main() {}");
        values(oversized, "main");
        assertEquals(StateException.Reason.LIMIT, assertThrows(StateException.class, oversized::snapshotGlobals).reason());
    }

    @Test
    void persistsEventUpdatesUnderControllerIdentityAcrossSimulatedRestarts() throws IOException {
        var store = new MemoryStore();
        String script = """
                global counter = 0
                on startup { counter++ }
                on redstone.rising("left") { counter += event.payload }
                fn inspect() { return counter }
                """;
        var original = new Simulation(20_000, 8);
        var first = attach(original, "first", script, store);
        var other = attach(original, "other", script, store);
        original.tick();
        first.setInput("left", 5);
        original.tick();
        first.save();
        other.save();

        var restart = new Simulation(20_000, 8);
        var restored = attach(restart, "first", script, store);
        var restoredOther = attach(restart, "other", script, store);
        assertEquals(List.of(new Num(6)), values(restored.interpreter(), "inspect"));
        assertEquals(List.of(new Num(1)), values(restoredOther.interpreter(), "inspect"));
        restart.tick();
        assertEquals(List.of(new Num(7)), values(restored.interpreter(), "inspect"));
        byte[] lastGood = store.load("first").orElseThrow();
        store.fail = true;
        assertThrows(IOException.class, restored::save);
        assertArrayEquals(lastGood, store.load("first").orElseThrow());
    }

    private static Simulation.Controller attach(Simulation simulation, String name, String script, StateStore store)
            throws IOException {
        var result = simulation.add(name, new SourceText(name, name + ".fsig", script),
                new ExecutionLimits(32, 10_000), Set.of(), ignored -> {}, store, null);
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics()::toString);
        return result.controller();
    }

    private static List<Value> values(Interpreter interpreter, String entry) {
        var result = interpreter.invoke(entry, List.of());
        assertTrue(result.issue().isEmpty(), result.issue()::toString);
        return result.returns().values();
    }

    private static Interpreter interpreter(String script) {
        var compiled = Resolver.resolve(Parser.parse(new SourceText("persist", "persist.fsig", script)));
        assertTrue(compiled.diagnostics().isEmpty(), compiled.diagnostics()::toString);
        return new Interpreter(compiled.program().orElseThrow(), new ExecutionLimits(64, 400_000), ignored -> {});
    }

    private static final class MemoryStore implements StateStore {
        private final Map<String, byte[]> data = new HashMap<>();
        private boolean fail;

        @Override
        public Optional<byte[]> load(String controller) {
            return Optional.ofNullable(data.get(controller)).map(byte[]::clone);
        }

        @Override
        public void save(String controller, byte[] snapshot) throws IOException {
            if (fail) {
                throw new IOException("simulated write failure");
            }
            data.put(controller, snapshot.clone());
        }
    }
}
