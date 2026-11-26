package dev.wiktorfila.filasignal.fabric.computer;

import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ControllerDataTest {
    @Test
    void preservesStableIdentitySourceAndAnOwnedSnapshot() throws IOException {
        ControllerData clean = ControllerData.fresh();
        byte[] input = {1, 2, 3};
        ControllerData changed = clean.withSnapshot(input);
        input[0] = 9;
        byte[] exposed = changed.snapshot();
        exposed[1] = 9;
        ControllerData restored = ControllerData.read(changed.encode());
        assertEquals(clean.identity(), restored.identity());
        assertEquals(clean.source(), restored.source());
        assertArrayEquals(new byte[] {1, 2, 3}, restored.snapshot());
    }

    @Test
    void rejectsUnknownVersionMalformedRecordsAndOversizeSnapshots() {
        CompoundTag bad = new CompoundTag();
        bad.putInt("version", 99);
        assertThrows(IOException.class, () -> ControllerData.read(new Dynamic<>(NbtOps.INSTANCE, bad)));
        bad.putInt("version", 1);
        bad.putString("identity", "bad");
        assertThrows(IOException.class, () -> ControllerData.read(new Dynamic<>(NbtOps.INSTANCE, bad)));
        assertThrows(IOException.class, () -> ControllerData.fresh().withSnapshot(new byte[262_145]));
    }
}
