package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Location;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Mode;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NetworkSaveTest {
    @Test
    void preservesRepeaterLinksNamesAndPortsWithAllEndpointsOfflineUntilLoaded() {
        var original = new NetworkSave();
        var graph = original.directory();
        Node computer = node(1, Kind.COMPUTER, 0);
        Node relay = node(2, Kind.INTERFACE, 20);
        Node endpoint = node(3, Kind.INTERFACE, 40);
        Node sensor = node(4, Kind.SENSOR, 60);
        for (Node node : List.of(computer, relay, endpoint, sensor)) {
            graph.attach(node);
        }
        graph.link(computer.id(), relay.id(), false);
        graph.link(relay.id(), endpoint.id(), false);
        graph.link(endpoint.id(), sensor.id(), false);
        graph.alias(sensor.id(), "entrance");
        graph.port(endpoint.id(), Direction.NORTH, Mode.OUTPUT, "door");
        assertThrows(IllegalArgumentException.class, () -> graph.port(relay.id(), Direction.UP, Mode.INPUT, "door"));
        assertTrue(graph.reachable(graph.node(sensor.id())));
        var restored = NetworkSave.read(original.write()).directory();
        assertEquals(3, restored.links().size());
        assertEquals("entrance", restored.node(sensor.id()).alias());
        assertEquals(Direction.NORTH, restored.port(computer.id(), "door").port().face());
        assertFalse(restored.reachable(restored.node(sensor.id())));
        for (Node node : List.of(computer, relay, endpoint, sensor)) {
            restored.attach(node);
        }
        assertTrue(restored.reachable(restored.node(sensor.id())));
        restored.detach(relay.id());
        assertFalse(restored.reachable(restored.node(sensor.id())));
        restored.remove(computer.id());
        assertNull(restored.node(sensor.id()).network());
        assertTrue(restored.links().isEmpty());
    }

    @Test
    void rejectsBadAdoptionWithoutChangingNetworkOwnership() {
        var graph = new NetworkDirectory(8);
        Node root = node(1, Kind.COMPUTER, 0);
        Node distant = node(2, Kind.INTERFACE, 12);
        graph.attach(root);
        graph.attach(distant);
        assertThrows(IllegalArgumentException.class, () -> graph.link(root.id(), distant.id(), false));
        assertNull(graph.node(distant.id()).network());
        assertTrue(graph.links().isEmpty());
    }

    @Test
    void rejectsDuplicateLinksAndAliasesWithoutReassigningAnAddress() {
        var graph = new NetworkDirectory(32);
        Node root = node(1, Kind.COMPUTER, 0);
        Node first = node(2, Kind.INTERFACE, 1);
        Node second = node(3, Kind.INTERFACE, 2);
        for (Node node : List.of(root, first, second)) {
            graph.attach(node);
        }
        graph.link(root.id(), first.id(), false);
        graph.link(first.id(), second.id(), false);
        graph.alias(first.id(), "relay");
        assertThrows(IllegalArgumentException.class, () -> graph.alias(second.id(), "relay"));
        assertEquals(first.id(), graph.discover(root.id(), "relay").device().orElseThrow().id());
        assertThrows(IllegalArgumentException.class, () -> graph.link(first.id(), root.id(), false));
        assertThrows(IllegalArgumentException.class, () -> graph.link(root.id(), second.id(), true));
        graph.alias(first.id(), "entry");
        assertTrue(graph.discover(root.id(), "relay").device().isEmpty());
        assertTrue(graph.discover(root.id(), "entry").device().isPresent());
    }

    @Test
    void preservesAnIncompatibleRecordAndDisablesTopology() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("version", 99);
        var raw = new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, tag);
        var store = NetworkSave.read(raw);
        assertFalse(store.enabled());
        assertThrows(IllegalArgumentException.class, store::directory);
        assertEquals(raw, store.write());
    }

    @Test
    void migratesOwnerlessDevicesAndPersistsAccessAndInventoryGrants() {
        var store = new NetworkSave();
        var graph = store.directory();
        var root = node(1, Kind.COMPUTER, 0);
        var router = node(2, Kind.INTERFACE, 1);
        graph.attach(root);
        graph.attach(router);
        graph.link(root.id(), router.id(), false);
        var player = new UUID(0, 10);
        var trusted = new UUID(0, 11);
        var owner = new NetworkPermission.Actor(player, false);
        var guest = new NetworkPermission.Actor(trusted, false);
        var admin = new NetworkPermission.Actor(null, true);
        assertFalse(NetworkPermission.allows(graph, router, owner, false));
        assertTrue(NetworkPermission.allows(graph, router, admin, true));

        graph.access(root.id(), new NetworkAccess(player, java.util.Set.of(trusted)));
        graph.inventories().add(new InventoryBindings.Binding(new UUID(0, 20), root.id(), router.id(),
                Direction.EAST, "storage", new UUID(0, 21), player));
        assertThrows(IllegalArgumentException.class, () -> graph.inventories().add(new InventoryBindings.Binding(
                new UUID(0, 22), root.id(), router.id(), Direction.WEST, "storage", new UUID(0, 23), player)));
        var restored = NetworkSave.read(store.write()).directory();
        assertTrue(NetworkPermission.allows(restored, restored.node(router.id()), owner, true));
        assertTrue(NetworkPermission.allows(restored, restored.node(router.id()), guest, false));
        assertFalse(NetworkPermission.allows(restored, restored.node(router.id()), guest, true));
        assertEquals(player, restored.inventories().byAlias(root.id(), "storage").owner());
        restored.remove(router.id());
        assertNull(restored.inventories().byAlias(root.id(), "storage"));
    }

    @Test
    void redstoneIdentitySurvivesRenameButNotRemovalOrModeChanges() {
        var graph = new NetworkDirectory(32);
        Node root = node(1, Kind.COMPUTER, 0);
        Node router = node(2, Kind.INTERFACE, 1);
        graph.attach(root);
        graph.attach(router);
        graph.link(root.id(), router.id(), false);
        graph.port(router.id(), Direction.NORTH, Mode.INPUT, "input");
        UUID identity = graph.port(root.id(), "input").port().id();
        graph.port(router.id(), Direction.NORTH, Mode.INPUT, "renamed");
        assertNull(graph.port(root.id(), "input"));
        assertEquals(identity, graph.port(root.id(), "renamed").port().id());
        graph.port(router.id(), Direction.NORTH, Mode.OUTPUT, "renamed");
        assertNull(graph.port(identity));
        assertNotEquals(identity, graph.port(root.id(), "renamed").port().id());
        graph.port(router.id(), Direction.NORTH, Mode.OUTPUT, null);
        assertNull(graph.port(root.id(), "renamed"));
    }

    private static Node node(int id, Kind kind, int x) {
        UUID key = new UUID(0, id);
        return new Node(key, kind == Kind.COMPUTER ? key : null, kind,
                new Location("minecraft:overworld", x, 64, 0), null, List.of());
    }
}
