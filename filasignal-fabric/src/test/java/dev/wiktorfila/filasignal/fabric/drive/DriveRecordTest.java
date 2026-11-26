package dev.wiktorfila.filasignal.fabric.drive;

import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DriveRecordTest {
    @Test
    void roundTripsProgramGlobalsAndStorage() throws IOException {
        int[] changes = {0};
        var record = new DriveRecord(UUID.randomUUID(), () -> changes[0]++);
        UUID before = record.revision();
        record.program("on startup { }", false);
        record.globals(new byte[]{1, 2, 3});
        record.storage().write("history", new ListValue(List.of(new Num(4))));
        record.touch(42);
        assertNotEquals(before, record.revision());
        assertTrue(changes[0] >= 3, "Changes did not mark the shard dirty");

        var restored = DriveRecord.read(record.write(), () -> { });
        assertNull(restored.damage());
        assertEquals(record.id(), restored.id());
        assertEquals("on startup { }", restored.source());
        assertEquals(record.revision(), restored.revision());
        assertArrayEquals(new byte[]{1, 2, 3}, restored.globals());
        assertEquals(42, restored.lastSeen());
        assertEquals(List.of(new Num(4)), ((ListValue) restored.storage().read("history")).snapshot());

        record.program("", true);
        assertNull(record.globals(), "Fresh globals were not discarded");
        assertThrows(IOException.class, () -> record.globals(new byte[DriveRecord.MAX_GLOBALS + 1]));
        assertThrows(IllegalArgumentException.class, () -> record.program("x".repeat(DriveRecord.MAX_SOURCE + 1), false));
    }

    @Test
    void preservesDamagedRecordsUnchangedAndRefusesToUseThem() {
        var tag = new CompoundTag();
        UUID id = UUID.randomUUID();
        tag.putInt("version", DriveRecord.VERSION);
        tag.putString("id", id.toString());
        tag.putString("source", "on startup { }");
        tag.putByteArray("storage", new byte[]{0, 0, 0, 1, 0, 0, 0, 5});
        var damaged = DriveRecord.read(tag, () -> { });
        assertNotNull(damaged.damage());
        assertEquals(tag, damaged.write(), "A damaged record was rewritten");
        assertThrows(IllegalStateException.class, damaged::storage);
        assertThrows(IllegalStateException.class, () -> damaged.program("", false));

        var future = tag.copy();
        future.putInt("version", 9);
        assertNotNull(DriveRecord.read(future, () -> { }).damage());
        assertNull(DriveRecord.read(new CompoundTag(), () -> { }), "A record without an ID was accepted");
    }

    @Test
    void driveTagIsSmallAndBounded() {
        var tag = new DriveTag(UUID.randomUUID(), "l".repeat(40), "entity_detector");
        assertEquals(DriveTag.MAX_LABEL, tag.label().length());
        var buffer = Unpooled.buffer();
        try {
            DriveTag.STREAM_CODEC.encode(buffer, tag);
            assertTrue(buffer.readableBytes() < 128, "The drive component should stay small");
            assertEquals(tag, DriveTag.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new DriveTag(UUID.randomUUID(), "", "x".repeat(33)));
    }
}
