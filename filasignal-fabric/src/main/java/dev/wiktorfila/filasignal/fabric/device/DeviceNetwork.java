package dev.wiktorfila.filasignal.fabric.device;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Standalone graph for server-owned device discovery. The caller owns authentication and uses one
 * thread for mutations and lookups. A loaded status is supplied by lifecycle events.
 */
public final class DeviceNetwork {
    private static final int MAX_DEVICES = 256;
    private static final int MAX_LINKS = 16;
    /** Maximum distance in blocks between two paired Wireless Network Bridges in one dimension. */
    public static final int BRIDGE_RANGE = 256;
    private final int maxRange;

    public DeviceNetwork() { this(32); }

    /** @param range maximum wireless edge length in blocks, from 1 through 64 */
    public DeviceNetwork(int range) {
        if (range < 1 || range > 64) {
            throw new IllegalArgumentException("Wireless range must be 1–64");
        }
        maxRange = range;
    }
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,31}");

    private final Map<UUID, Network> networks = new HashMap<>();
    private final Map<UUID, Device> devices = new HashMap<>();

    /**
     * Independent device capabilities. NETWORK devices join a computer's network. RELAY devices forward links.
     * HARDWARE devices expose device-specific operations. EXECUTION devices run FilaScript programs: the computer
     * always, and hardware only while a programmable drive is installed and started.
     */
    public enum Capability { NETWORK, RELAY, HARDWARE, EXECUTION }

    /**
     * Device types. Each type composes capabilities rather than inheriting a category. Persisted by
     * {@link #name()}; {@link #id()} is the lowercase script-facing name.
     */
    public enum Kind {
        COMPUTER(Capability.NETWORK, Capability.EXECUTION),
        INTERFACE(Capability.NETWORK, Capability.RELAY, Capability.HARDWARE),
        SENSOR(Capability.NETWORK, Capability.HARDWARE),
        REPEATER(Capability.NETWORK, Capability.RELAY),
        ENTITY_DETECTOR(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        WEATHER_STATION(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        REDSTONE_CONTROLLER(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        INVENTORY_MODULE(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        ENVIRONMENT_SENSOR(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        DISPLAY(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        ALARM(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        STORAGE_SERVER(Capability.NETWORK, Capability.HARDWARE),
        ACCESS_CONTROLLER(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        CONTROL_PANEL(Capability.NETWORK, Capability.HARDWARE, Capability.EXECUTION),
        WIRELESS_BRIDGE(Capability.NETWORK, Capability.RELAY);

        private final Set<Capability> capabilities;

        Kind(Capability... capabilities) {
            this.capabilities = java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(java.util.List.of(capabilities)));
        }

        public Set<Capability> capabilities() { return capabilities; }

        public boolean has(Capability capability) { return capabilities.contains(capability); }

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

        /** Computers and relays accept up to 16 links. Every other device is a leaf with one uplink. */
        int linkLimit() { return this == COMPUTER || has(Capability.RELAY) ? MAX_LINKS : 1; }
    }
    public enum Status { AVAILABLE, UNAVAILABLE, UNKNOWN }
    public enum Change { APPLIED, UNCHANGED, UNKNOWN_DEVICE, DUPLICATE_ID, DUPLICATE_ALIAS,
        INVALID_ALIAS, WRONG_NETWORK, WRONG_DIMENSION, INVALID_LINK, OUT_OF_RANGE, UNAVAILABLE, LIMIT }

    /** Dimension is a canonical host ID and position is a Minecraft block coordinate. */
    public record Location(String dimension, int x, int y, int z) {
        public Location {
            if (Objects.requireNonNull(dimension).isBlank()) {
                throw new IllegalArgumentException("A dimension ID is required");
            }
        }
    }

    /** A computer's device ID and its network ID are the same. A null alias is allowed. */
    public record Device(UUID id, UUID computer, Kind kind, Location location, String alias) {
        public Device {
            Objects.requireNonNull(id);
            Objects.requireNonNull(computer);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(location);
        }
    }

    /** Device metadata remains available while an alias is disconnected or unloaded. */
    public record Discovery(Status status, Optional<Device> device) {
    }

    /** Registers a loaded endpoint. It must have a unique ID and an existing owner computer. */
    public Change register(Device device) {
        if (devices.containsKey(device.id())) {
            return Change.DUPLICATE_ID;
        }
        if (device.alias() != null && !ALIAS.matcher(device.alias()).matches()) {
            return Change.INVALID_ALIAS;
        }
        boolean root = device.kind() == Kind.COMPUTER;
        if (root != device.id().equals(device.computer())) {
            return Change.WRONG_NETWORK;
        }
        Network network = root ? new Network() : networks.get(device.computer());
        if (network == null) {
            return Change.WRONG_NETWORK;
        }
        if (!root && !device.location().dimension().equals(devices.get(device.computer()).location().dimension())) {
            return Change.WRONG_DIMENSION;
        }
        if (network.links.size() == MAX_DEVICES) {
            return Change.LIMIT;
        }
        if (device.alias() != null && network.aliases.containsKey(device.alias())) {
            return Change.DUPLICATE_ALIAS;
        }
        if (root) {
            networks.put(device.computer(), network);
        }
        devices.put(device.id(), device);
        network.links.put(device.id(), new LinkedHashSet<>());
        network.loaded.add(device.id());
        if (device.alias() != null) {
            network.aliases.put(device.alias(), device.id());
        }
        network.invalidate();
        return Change.APPLIED;
    }

    /** Replaces or clears an alias. Unloaded devices retain their alias claims. */
    public Change alias(UUID id, String alias) {
        Device device = devices.get(id);
        if (device == null) {
            return Change.UNKNOWN_DEVICE;
        }
        if (Objects.equals(device.alias(), alias)) {
            return Change.UNCHANGED;
        }
        Network network = networks.get(device.computer());
        if (alias != null) {
            if (!ALIAS.matcher(alias).matches()) {
                return Change.INVALID_ALIAS;
            }
            if (network.aliases.containsKey(alias)) {
                return Change.DUPLICATE_ALIAS;
            }
        }
        network.aliases.remove(device.alias());
        if (alias != null) {
            network.aliases.put(alias, id);
        }
        devices.put(id, new Device(id, device.computer(), device.kind(), device.location(), alias));
        return Change.APPLIED;
    }

    /** Publishes a lifecycle change without deleting endpoints, aliases or links. */
    public Change loaded(UUID id, boolean loaded) {
        Device device = devices.get(id);
        if (device == null) {
            return Change.UNKNOWN_DEVICE;
        }
        Network network = networks.get(device.computer());
        boolean changed = loaded ? network.loaded.add(id) : network.loaded.remove(id);
        if (changed) {
            network.invalidate();
        }
        return changed ? Change.APPLIED : Change.UNCHANGED;
    }

    /** Links two loaded endpoints in one network after role, range and degree checks. */
    public Change link(UUID first, UUID second) {
        Device a = devices.get(first);
        Device b = devices.get(second);
        Change rejection = validateEndpoints(a, b);
        if (rejection != null) {
            return rejection;
        }
        if (first.equals(second)
                || (!a.kind().has(Capability.RELAY) && !b.kind().has(Capability.RELAY))) {
            return Change.INVALID_LINK;
        }
        Network network = networks.get(a.computer());
        if (!network.loaded.contains(first) || !network.loaded.contains(second)) {
            return Change.UNAVAILABLE;
        }
        boolean bridged = a.kind() == Kind.WIRELESS_BRIDGE && b.kind() == Kind.WIRELESS_BRIDGE;
        if (!withinRange(a.location(), b.location(), bridged ? BRIDGE_RANGE : maxRange)) {
            return Change.OUT_OF_RANGE;
        }
        Set<UUID> aLinks = network.links.get(first);
        Set<UUID> bLinks = network.links.get(second);
        if (aLinks.contains(second)) {
            return Change.UNCHANGED;
        }
        if (aLinks.size() == a.kind().linkLimit() || bLinks.size() == b.kind().linkLimit()) {
            return Change.LIMIT;
        }
        // A bridge pairs with exactly one other bridge; it may still link local devices within normal range.
        if (bridged && (bridgePeer(network, first) != null || bridgePeer(network, second) != null)) {
            return Change.LIMIT;
        }
        aLinks.add(second);
        bLinks.add(first);
        network.invalidate();
        return Change.APPLIED;
    }

    /** Removes a known link, including links involving unloaded devices. */
    public Change unlink(UUID first, UUID second) {
        Device a = devices.get(first);
        Change rejection = validateEndpoints(a, devices.get(second));
        if (rejection != null) {
            return rejection;
        }
        Network network = networks.get(a.computer());
        if (!network.links.get(first).remove(second)) {
            return Change.UNCHANGED;
        }
        network.links.get(second).remove(first);
        network.invalidate();
        return Change.APPLIED;
    }

    /** Removes an endpoint and its links. Removing the computer releases its entire network. */
    public Change remove(UUID id) {
        Device device = devices.remove(id);
        if (device == null) {
            return Change.UNKNOWN_DEVICE;
        }
        if (id.equals(device.computer())) {
            Network removed = networks.remove(id);
            removed.links.keySet().forEach(devices::remove);
        } else {
            Network network = networks.get(device.computer());
            for (UUID neighbor : network.links.remove(id)) {
                network.links.get(neighbor).remove(id);
            }
            network.loaded.remove(id);
            network.aliases.remove(device.alias());
            network.invalidate();
        }
        return Change.APPLIED;
    }

    /** Looks up an alias in the computer's network without reading the Minecraft world. */
    public Discovery discover(UUID computer, String alias) {
        Network network = networks.get(computer);
        UUID id = network == null ? null : network.aliases.get(alias);
        if (id == null) {
            return new Discovery(Status.UNKNOWN, Optional.empty());
        }
        return new Discovery(network.reachable(computer).contains(id) ? Status.AVAILABLE : Status.UNAVAILABLE,
                Optional.of(devices.get(id)));
    }

    /** Direct link neighbors of a device, including unloaded ones. Empty for an unknown device. */
    public Set<UUID> neighbors(UUID id) {
        Device device = devices.get(id);
        Network network = device == null ? null : networks.get(device.computer());
        return network == null ? Set.of() : Set.copyOf(network.links.get(id));
    }

    /** Tests cached connectivity without requiring an alias. */
    public boolean reachable(UUID computer, UUID id) {
        Network network = networks.get(computer);
        return network != null && network.reachable(computer).contains(id);
    }

    private static Change validateEndpoints(Device a, Device b) {
        if (a == null || b == null) {
            return Change.UNKNOWN_DEVICE;
        }
        if (!a.computer().equals(b.computer())) {
            return Change.WRONG_NETWORK;
        }
        if (!a.location().dimension().equals(b.location().dimension())) {
            return Change.WRONG_DIMENSION;
        }
        return null;
    }

    private static boolean withinRange(Location a, Location b, long range) {
        long x = (long) a.x() - b.x();
        long y = (long) a.y() - b.y();
        long z = (long) a.z() - b.z();
        return Math.abs(x) <= range && Math.abs(y) <= range && Math.abs(z) <= range
                && x * x + y * y + z * z <= range * range;
    }

    /** The wireless bridge linked to a bridge, or null. */
    public UUID bridgePeer(UUID bridge) {
        Device device = devices.get(bridge);
        Network network = device == null ? null : networks.get(device.computer());
        return network == null ? null : bridgePeer(network, bridge);
    }

    private UUID bridgePeer(Network network, UUID bridge) {
        if (devices.get(bridge).kind() != Kind.WIRELESS_BRIDGE) {
            return null;
        }
        for (UUID neighbor : network.links.get(bridge)) {
            if (devices.get(neighbor).kind() == Kind.WIRELESS_BRIDGE) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * Tests whether two devices of one network can exchange events now: both are loaded and joined by links through
     * loaded devices. Unlike {@link #reachable}, the path need not include the computer, so autonomous devices keep
     * cooperating while the computer's area is unloaded.
     */
    public boolean connected(UUID first, UUID second) {
        Device a = devices.get(first);
        Device b = devices.get(second);
        if (a == null || b == null || !a.computer().equals(b.computer())) {
            return false;
        }
        Network network = networks.get(a.computer());
        return network.loaded.contains(first) && network.component(first).contains(second);
    }

    private static final class Network {
        private final Map<String, UUID> aliases = new HashMap<>();
        private final Map<UUID, Set<UUID>> links = new HashMap<>();
        private final Set<UUID> loaded = new HashSet<>();
        private Set<UUID> reachable;
        private final Map<UUID, Set<UUID>> components = new HashMap<>();

        private void invalidate() {
            reachable = null;
            components.clear();
        }

        /** Loaded devices joined to a loaded start device through loaded devices, cached until the network changes. */
        private Set<UUID> component(UUID start) {
            Set<UUID> cached = components.get(start);
            if (cached != null) {
                return cached;
            }
            var found = new HashSet<UUID>();
            var work = new ArrayDeque<UUID>();
            found.add(start);
            work.add(start);
            while (!work.isEmpty()) {
                for (UUID adjacent : links.get(work.removeFirst())) {
                    if (loaded.contains(adjacent) && found.add(adjacent)) {
                        work.addLast(adjacent);
                    }
                }
            }
            for (UUID member : found) {
                components.put(member, found);
            }
            return found;
        }

        private Set<UUID> reachable(UUID computer) {
            if (reachable != null) {
                return reachable;
            }
            reachable = new HashSet<>();
            if (loaded.contains(computer)) {
                var work = new ArrayDeque<UUID>();
                reachable.add(computer);
                work.add(computer);
                while (!work.isEmpty()) {
                    for (UUID adjacent : links.get(work.removeFirst())) {
                        if (loaded.contains(adjacent) && reachable.add(adjacent)) {
                            work.addLast(adjacent);
                        }
                    }
                }
            }
            return reachable;
        }
    }
}
