package dev.wiktorfila.filasignal.core.runtime.persistence;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NamedStorageTest {
    @Test
    void storesDetachedCopiesOfSupportedValues() throws StateException {
        var storage = new NamedStorage();
        var list = new ListValue(List.of(new Num(1), new Text("two")));
        var dict = new DictValue();
        dict.put("items", list);
        dict.put("pair", new TupleValue(List.of(new Bool(true), Value.Nil.INSTANCE)));
        storage.write("last", dict);
        list.insert(2, new Num(3));
        var read = (DictValue) storage.read("last");
        assertEquals(2, ((ListValue) read.get("items")).size(), "A later mutation leaked into storage");
        ((ListValue) read.get("items")).insert(0, new Num(0));
        assertEquals(2, ((ListValue) ((DictValue) storage.read("last")).get("items")).size(), "A read returned an alias");
        assertNull(storage.read("missing"));
        assertTrue(storage.has("last") && storage.keys().equals(List.of("last")));

        var restored = NamedStorage.load(storage.save());
        assertEquals(storage.used(), restored.used());
        assertEquals(new TupleValue(List.of(new Bool(true), Value.Nil.INSTANCE)), ((DictValue) restored.read("last")).get("pair"));
        assertTrue(restored.delete("last") && !restored.delete("last") && restored.used() == 0);
    }

    @Test
    void appendsToListsAndRejectsOtherValues() {
        var storage = new NamedStorage();
        storage.append("history", new Text("a"));
        storage.append("history", new Num(2));
        assertEquals(List.of(new Text("a"), new Num(2)), ((ListValue) storage.read("history")).snapshot());
        storage.write("count", new Num(1));
        var failure = assertThrows(NamedStorage.Failure.class, () -> storage.append("count", new Num(2)));
        assertEquals("storage.type", failure.code());
        assertEquals(new Num(1), storage.read("count"));
    }

    @Test
    void rejectsUnsupportedCyclicDeepAndOversizedValuesWithoutChanges() {
        var storage = new NamedStorage();
        storage.write("keep", new Text("safe"));
        int used = storage.used();
        var handle = new HostHandle("Inventory", UUID.randomUUID(), UUID.randomUUID());
        assertEquals("storage.value", assertThrows(NamedStorage.Failure.class, () -> storage.write("x", handle)).code());
        assertEquals("storage.value", assertThrows(NamedStorage.Failure.class,
                () -> storage.write("x", new EnumMember("Color", "RED"))).code());
        var cyclic = new ListValue(List.of());
        cyclic.insert(0, cyclic);
        assertEquals("storage.value", assertThrows(NamedStorage.Failure.class, () -> storage.write("x", cyclic)).code());
        Value deep = new Num(1);
        for (int i = 0; i < NamedStorage.MAX_DEPTH + 2; i++) {
            deep = new ListValue(List.of(deep));
        }
        Value tooDeep = deep;
        assertEquals("storage.value", assertThrows(NamedStorage.Failure.class, () -> storage.write("x", tooDeep)).code());
        assertEquals("storage.key", assertThrows(NamedStorage.Failure.class, () -> storage.write("bad key", new Num(1))).code());
        assertEquals("storage.full", assertThrows(NamedStorage.Failure.class,
                () -> storage.write("big", new Text("x".repeat(NamedStorage.CAPACITY)))).code());
        assertEquals(used, storage.used());
        assertEquals(List.of("keep"), storage.keys());
    }

    @Test
    void enforcesCapacityAcrossKeysAndEntryCount() {
        var storage = new NamedStorage();
        String chunk = "y".repeat(10_000);
        int written = 0;
        try {
            for (int i = 0; i < 10; i++) {
                storage.write("k" + i, new Text(chunk));
                written++;
            }
            fail("Capacity was not enforced");
        } catch (NamedStorage.Failure full) {
            assertEquals("storage.full", full.code());
        }
        assertTrue(written == 6 && storage.used() <= NamedStorage.CAPACITY, "Wrote " + written);
        // Replacing an existing value is allowed when the result fits.
        storage.write("k0", new Text("small"));
        storage.clear();
        for (int i = 0; i < NamedStorage.MAX_ENTRIES; i++) {
            storage.write("n" + i, new Num(i));
        }
        assertEquals("storage.full", assertThrows(NamedStorage.Failure.class, () -> storage.write("extra", new Num(0))).code());
        storage.write("n0", new Num(-1));
    }

    @Test
    void rejectsDamagedAndUnknownSaves() {
        assertEquals(StateException.Reason.CORRUPT, assertThrows(StateException.class,
                () -> NamedStorage.load(new byte[]{0, 0, 0, 1, 0, 0, 0, 1})).reason());
        assertEquals(StateException.Reason.INCOMPATIBLE, assertThrows(StateException.class,
                () -> NamedStorage.load(new byte[]{0, 0, 0, 9, 0, 0, 0, 0})).reason());
        var storage = new NamedStorage();
        storage.write("a", new DictValue(Map.of("b", new Num(1))));
        byte[] saved = storage.save();
        saved[saved.length - 1] ^= 0x7f;
        assertThrows(StateException.class, () -> NamedStorage.load(java.util.Arrays.copyOf(saved, saved.length + 1)));
    }
}
