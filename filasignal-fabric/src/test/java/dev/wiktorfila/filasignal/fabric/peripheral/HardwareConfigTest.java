package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Location;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HardwareConfigTest {
    @Test
    void displayTextIsBoundedAndNormalized() {
        assertEquals(List.of(), DisplayText.lines(""));
        assertEquals(List.of("a", "b", "c"), DisplayText.lines("a\r\nb\rc"));
        assertEquals(List.of("tab here"), DisplayText.lines("tab\there"));
        assertEquals(List.of("🚀".repeat(DisplayText.MAX_COLUMNS)), DisplayText.lines("🚀".repeat(DisplayText.MAX_COLUMNS)));
        var tooLong = assertThrows(HostFailure.class, () -> DisplayText.lines("x".repeat(DisplayText.MAX_COLUMNS + 1)));
        assertEquals("argument.range", tooLong.code());
        assertThrows(HostFailure.class, () -> DisplayText.lines("\n".repeat(DisplayText.MAX_LINES)));
        assertThrows(HostFailure.class, () -> DisplayText.lines("y".repeat(10_000)));
    }

    @Test
    void alarmSettingsAreValidatedAndSavedValuesFallBack() {
        assertEquals(new AlarmSettings("chime", 10, 60), new AlarmSettings("chime", 10, 60));
        assertEquals("argument.range", assertThrows(HostFailure.class, () -> new AlarmSettings("klaxon", 50, 5)).code());
        assertThrows(HostFailure.class, () -> new AlarmSettings("bell", 101, 5));
        assertThrows(HostFailure.class, () -> new AlarmSettings("bell", 50, 0));
        assertEquals(AlarmSettings.DEFAULT, AlarmSettings.parse("bell", 5, 5));
        assertEquals(new AlarmSettings("bass", 70, 3), AlarmSettings.parse("bass", 70, 3));
    }

    @Test
    void topologyWithDedicatedHardwareRoundTripsAndOlderVersionsStillLoad() {
        var original = new NetworkSave();
        var graph = original.directory();
        Node computer = node(1, Kind.COMPUTER, 0);
        Node repeater = node(2, Kind.REPEATER, 10);
        Node display = node(3, Kind.DISPLAY, 12);
        Node module = node(4, Kind.INVENTORY_MODULE, 14);
        for (Node node : List.of(computer, repeater, display, module)) {
            graph.attach(node);
        }
        graph.access(computer.id(), new NetworkAccess(new UUID(9, 9), java.util.Set.of()));
        graph.link(computer.id(), repeater.id(), false);
        graph.link(repeater.id(), display.id(), false);
        graph.link(repeater.id(), module.id(), false);
        graph.alias(display.id(), "board");
        graph.inventories().add(new InventoryBindings.Binding(new UUID(5, 5), computer.id(), module.id(), Direction.NORTH,
                "ore", new UUID(6, 6), new UUID(9, 9)));
        assertThrows(IllegalArgumentException.class, () -> graph.link(display.id(), module.id(), false));
        var raw = original.write();
        assertEquals(3, ((CompoundTag) raw.convert(NbtOps.INSTANCE).getValue()).getIntOr("version", 0));
        var restored = NetworkSave.read(raw);
        assertTrue(restored.enabled());
        var directory = restored.directory();
        assertEquals(Kind.REPEATER, directory.node(repeater.id()).kind());
        assertEquals("board", directory.node(display.id()).alias());
        assertEquals(module.id(), directory.inventories().byAlias(computer.id(), "ore").interfaceId());
        assertEquals(java.util.Set.of(computer.id(), display.id(), module.id()), directory.neighbors(repeater.id()));

        var older = ((CompoundTag) raw.convert(NbtOps.INSTANCE).getValue()).copy();
        older.putInt("version", 2);
        assertTrue(NetworkSave.read(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, older)).enabled());
        var newer = older.copy();
        newer.putInt("version", 4);
        assertFalse(NetworkSave.read(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE, newer)).enabled());
    }

    @Test
    void formPayloadsRoundTripAtTheirLimits() {
        var form = new DeviceConfigNetwork.Form(BlockPos.ZERO, UUID.randomUUID(), true, "ENTITY_DETECTOR", "Entity Detector",
                "a".repeat(32), "s".repeat(DeviceConfigNetwork.SETTINGS), "ࠀ".repeat(DeviceConfigNetwork.READINGS),
                "t".repeat(256), "1	" + "p".repeat(DeviceConfigNetwork.PROGRAM - 2));
        var edit = new DeviceConfigNetwork.Edit(BlockPos.ZERO, UUID.randomUUID(), DeviceConfigNetwork.DISPLAY_TEXT,
                "🚀".repeat(DeviceConfigNetwork.SETTINGS / 2), "o".repeat(64));
        var buffer = Unpooled.buffer();
        try {
            DeviceConfigNetwork.Form.CODEC.encode(buffer, form);
            assertEquals(form, DeviceConfigNetwork.Form.CODEC.decode(buffer));
            DeviceConfigNetwork.Edit.CODEC.encode(buffer, edit);
            assertEquals(edit, DeviceConfigNetwork.Edit.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static Node node(int id, Kind kind, int x) {
        UUID identity = new UUID(0, id);
        return new Node(identity, kind == Kind.COMPUTER ? identity : null, kind, new Location("minecraft:overworld", x, 0, 0),
                null, List.of());
    }
}
