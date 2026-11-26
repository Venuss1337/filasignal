package dev.wiktorfila.filasignal.fabric.device;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNetworkTest {
    @Test
    void cachesRoutesAndInvalidatesOnUnloadRemovalAndUnlink() {
        var graph = new DeviceNetwork();
        UUID root = id(1), relay = id(2), sensor = id(3);
        assertEquals(Change.APPLIED, graph.register(device(root, root, Kind.COMPUTER, 0, null)));
        assertEquals(Change.APPLIED, graph.register(device(relay, root, Kind.INTERFACE, 25, "relay")));
        assertEquals(Change.APPLIED, graph.register(device(sensor, root, Kind.SENSOR, 50, "sensor")));
        assertEquals(Status.UNAVAILABLE, graph.discover(root, "sensor").status());
        assertEquals(Change.APPLIED, graph.link(root, relay));
        assertEquals(Change.APPLIED, graph.link(relay, sensor));
        assertEquals(Status.AVAILABLE, graph.discover(root, "sensor").status());
        graph.loaded(relay, false);
        assertEquals(Status.UNAVAILABLE, graph.discover(root, "sensor").status());
        assertEquals(sensor, graph.discover(root, "sensor").device().orElseThrow().id());
        graph.loaded(relay, true);
        assertEquals(Status.AVAILABLE, graph.discover(root, "sensor").status());
        graph.unlink(root, relay);
        assertEquals(Status.UNAVAILABLE, graph.discover(root, "sensor").status());
        graph.remove(relay);
        assertEquals(Status.UNKNOWN, graph.discover(root, "relay").status());
        graph.remove(root);
        assertEquals(Status.UNKNOWN, graph.discover(root, "sensor").status());
    }

    @Test
    void validatesOwnershipDimensionRangeRolesAndAliasClaims() {
        var graph = new DeviceNetwork();
        UUID root = id(1), other = id(2), far = id(3), sensor = id(4), relay = id(5);
        graph.register(device(root, root, Kind.COMPUTER, 0, null));
        graph.register(device(other, other, Kind.COMPUTER, 0, null));
        graph.register(device(far, root, Kind.INTERFACE, 33, "gate"));
        graph.register(device(sensor, root, Kind.SENSOR, 34, "sensor"));
        graph.register(device(relay, root, Kind.INTERFACE, 32, "local"));
        assertEquals(Change.WRONG_DIMENSION, graph.register(new Device(id(6), root, Kind.INTERFACE,
                new Location("nether", 0, 0, 0), null)));
        assertEquals(Change.WRONG_NETWORK, graph.link(far, other));
        assertEquals(Change.OUT_OF_RANGE, graph.link(root, far));
        assertEquals(Change.INVALID_LINK, graph.link(root, sensor));
        assertEquals(Change.APPLIED, graph.link(root, relay));
        assertEquals(Change.APPLIED, graph.link(relay, sensor));
        assertEquals(Change.LIMIT, graph.link(far, sensor));
        graph.loaded(far, false);
        assertEquals(Change.DUPLICATE_ALIAS, graph.alias(sensor, "gate"));
        assertEquals(Change.INVALID_ALIAS, graph.alias(sensor, "not valid"));
        assertEquals(Change.APPLIED, graph.alias(sensor, "front_sensor"));
        assertEquals(Status.UNKNOWN, graph.discover(root, "sensor").status());
        assertEquals(Status.AVAILABLE, graph.discover(root, "front_sensor").status());
    }

    @Test
    void repeatersRelayAndHardwareLeavesTakeOneUplink() {
        var graph = new DeviceNetwork();
        UUID root = id(1), repeater = id(2), detector = id(3), alarm = id(4), second = id(5);
        graph.register(device(root, root, Kind.COMPUTER, 0, null));
        graph.register(device(repeater, root, Kind.REPEATER, 10, "router"));
        graph.register(device(detector, root, Kind.ENTITY_DETECTOR, 12, "gate"));
        graph.register(device(alarm, root, Kind.ALARM, 14, "siren"));
        graph.register(device(second, root, Kind.REPEATER, 16, "router2"));
        // Hardware never links directly to a computer or another leaf; the existing relay rule is preserved.
        assertEquals(Change.INVALID_LINK, graph.link(root, detector));
        assertEquals(Change.INVALID_LINK, graph.link(detector, alarm));
        assertEquals(Change.APPLIED, graph.link(root, repeater));
        assertEquals(Change.APPLIED, graph.link(repeater, detector));
        assertEquals(Change.APPLIED, graph.link(repeater, alarm));
        assertEquals(Change.APPLIED, graph.link(repeater, second));
        assertEquals(Change.LIMIT, graph.link(second, detector));
        assertEquals(Status.AVAILABLE, graph.discover(root, "gate").status());
        assertEquals(java.util.Set.of(root, detector, alarm, second), graph.neighbors(repeater));
        assertTrue(Kind.REPEATER.has(Capability.RELAY) && !Kind.REPEATER.has(Capability.HARDWARE));
        assertTrue(Kind.COMPUTER.has(Capability.EXECUTION) && !Kind.REPEATER.has(Capability.EXECUTION));
        for (Kind kind : Kind.values()) {
            assertTrue(kind.has(Capability.NETWORK), kind.id());
            boolean programmable = kind == Kind.COMPUTER || (kind.has(Capability.HARDWARE) && kind != Kind.INTERFACE
                    && kind != Kind.SENSOR && kind != Kind.STORAGE_SERVER);
            assertEquals(programmable, kind.has(Capability.EXECUTION), kind.id());
        }
        assertEquals("entity_detector", Kind.ENTITY_DETECTOR.id());
    }

    @Test
    void bridgesPairOnceAcrossLongDistancesAndPeersStayConnectedWithoutTheComputer() {
        var graph = new DeviceNetwork();
        UUID root = id(1), near = id(2), far = id(3), detector = id(4), alarm = id(5), third = id(6);
        graph.register(device(root, root, Kind.COMPUTER, 0, null));
        graph.register(device(near, root, Kind.WIRELESS_BRIDGE, 10, "near"));
        graph.register(device(far, root, Kind.WIRELESS_BRIDGE, 200, "far"));
        graph.register(device(detector, root, Kind.ENTITY_DETECTOR, 205, "gate"));
        graph.register(device(alarm, root, Kind.ALARM, 210, "siren"));
        graph.register(device(third, root, Kind.WIRELESS_BRIDGE, 300, "third"));
        assertEquals(Change.APPLIED, graph.link(root, near));
        // 190 blocks is beyond normal links but within bridge range.
        assertEquals(Change.APPLIED, graph.link(near, far));
        assertEquals(far, graph.bridgePeer(near));
        assertEquals(Change.LIMIT, graph.link(far, third), "A bridge pairs with only one other bridge");
        assertEquals(Change.APPLIED, graph.link(far, detector));
        assertEquals(Change.APPLIED, graph.link(far, alarm));
        assertEquals(Status.AVAILABLE, graph.discover(root, "gate").status());
        assertTrue(graph.connected(detector, alarm));
        // With the computer unloaded the far devices still reach each other, but not through it.
        graph.loaded(root, false);
        assertEquals(Status.UNAVAILABLE, graph.discover(root, "gate").status());
        assertTrue(graph.connected(detector, alarm));
        assertTrue(graph.connected(detector, near));
        graph.loaded(far, false);
        assertFalse(graph.connected(detector, alarm), "An unloaded relay splits the network");
        var out = new DeviceNetwork();
        out.register(device(root, root, Kind.COMPUTER, 0, null));
        out.register(device(near, root, Kind.WIRELESS_BRIDGE, 10, null));
        out.register(device(far, root, Kind.WIRELESS_BRIDGE, 300, null));
        assertEquals(Change.OUT_OF_RANGE, out.link(near, far));
    }

    private static Device device(UUID id, UUID root, Kind kind, int x, String alias) {
        return new Device(id, root, kind, new Location("overworld", x, 0, 0), alias);
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }
}
