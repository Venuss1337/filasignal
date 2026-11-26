package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.*;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sole owner of persistent membership, aliases and links. Minecraft objects never enter the graph. */
final class NetworkDirectory {
    static final int MAX_ENDPOINTS = 1024;
    private final Map<UUID, Node> nodes = new LinkedHashMap<>();
    private final Map<Location, UUID> locations = new LinkedHashMap<>();
    private final LinkedHashSet<Edge> links = new LinkedHashSet<>();
    private final Map<UUID, PortTarget> portsById = new LinkedHashMap<>();
    private final Map<Address, PortTarget> ports = new LinkedHashMap<>();
    private final InventoryBindings inventories = new InventoryBindings();
    private final Map<UUID, NetworkAccess> permissions = new LinkedHashMap<>();
    private final DeviceNetwork graph;
    private final int range;

    enum Mode { INPUT, OUTPUT }
    record Port(UUID id, String alias, Direction face, Mode mode) {}
    record PortTarget(UUID device, Port port) {}
    private record Address(UUID network, String alias) {}
    record Edge(UUID first, UUID second) {
        Edge {
            if (first.compareTo(second) > 0) {
                UUID swap = first;
                first = second;
                second = swap;
            }
        }
    }
    record Node(UUID id, UUID network, Kind kind, Location location, String alias, List<Port> ports) {
        Node { ports = List.copyOf(ports); }

        Node in(UUID owner) { return new Node(id, owner, kind, location, alias, ports); }
        Device device() { return new Device(id, network, kind, location, alias); }
    }

    NetworkDirectory(int range) {
        graph = new DeviceNetwork(range);
        this.range = range;
    }

    int range() { return range; }
    InventoryBindings inventories() { return inventories; }
    NetworkAccess access(UUID id) { return permissions.get(id); }

    void access(UUID id, NetworkAccess access) {
        requireNode(id);
        permissions.put(id, access);
    }

    void copyConfigurationTo(NetworkDirectory replacement) {
        permissions.forEach(replacement::access);
        inventories.all().forEach(replacement.inventories::add);
    }
    List<Node> nodes() { return List.copyOf(nodes.values()); }
    List<Edge> links() { return List.copyOf(links); }
    Node node(UUID id) { return nodes.get(id); }
    Node at(Location location) { return nodes.get(locations.get(location)); }
    PortTarget port(UUID network, String alias) { return ports.get(new Address(network, alias)); }
    PortTarget port(UUID id) { return portsById.get(id); }
    Discovery discover(UUID network, String alias) { return graph.discover(network, alias); }
    boolean reachable(Node node) { return node.network() != null && graph.reachable(node.network(), node.id()); }
    /** True when two loaded devices of one network are joined through loaded links, with or without the computer. */
    boolean connected(UUID first, UUID second) { return graph.connected(first, second); }
    UUID bridgePeer(UUID bridge) { return graph.bridgePeer(bridge); }
    java.util.Set<UUID> neighbors(UUID id) { return graph.neighbors(id); }

    void attach(Node node) {
        Node known = nodes.get(node.id());
        if (known == null) {
            insert(node);
        } else if (!known.location().equals(node.location()) || known.kind() != node.kind()) {
            throw new IllegalArgumentException("Conflicting device identity");
        }
        graph.loaded(node.id(), true);
    }

    private void insert(Node node) {
        if (nodes.size() >= MAX_ENDPOINTS || nodes.containsKey(node.id()) || locations.containsKey(node.location())) {
            throw new IllegalArgumentException("Device ID or position conflict, or endpoint limit reached");
        }
        if ((node.kind() == Kind.COMPUTER) != node.id().equals(node.network())) {
            throw new IllegalArgumentException("Invalid network root");
        }
        validateNames(node);
        if (node.network() != null) {
            require(graph.register(node.device()));
        }
        nodes.put(node.id(), node);
        locations.put(node.location(), node.id());
        indexPorts(node);
    }

    void detach(UUID id) {
        graph.loaded(id, false);
    }

    void remove(UUID id) {
        Node node = nodes.get(id);
        if (node == null) {
            return;
        }
        if (node.kind() == Kind.COMPUTER) {
            for (Node member : nodes()) {
                if (id.equals(member.network()) && !id.equals(member.id())) {
                    nodes.put(member.id(), member.in(null));
                    removePorts(member);
                }
            }
        }
        links.removeIf(edge -> edge.first().equals(id) || edge.second().equals(id)
                || (node.kind() == Kind.COMPUTER && nodes.get(edge.first()).network() == null));
        graph.remove(id);
        removePorts(node);
        inventories.removeDevice(id);
        permissions.remove(id);
        nodes.remove(id);
        locations.remove(node.location());
    }

    void link(UUID first, UUID second, boolean unlink) {
        Node a = requireNode(first);
        Node b = requireNode(second);
        Edge edge = new Edge(first, second);
        if (unlink) {
            if (!links.contains(edge)) {
                throw new IllegalArgumentException("These devices have no direct link");
            }
            require(graph.unlink(first, second));
            links.remove(edge);
            return;
        }
        if (links.contains(edge)) {
            throw new IllegalArgumentException("These devices are already linked");
        }
        if (a.network() == null && b.network() == null) {
            throw new IllegalArgumentException("Connect a relay (interface or repeater) to a computer first");
        }
        Node unattached = a.network() == null ? a : b.network() == null ? b : null;
        if (unattached != null) {
            Node adopted = unattached.in(a.network() == null ? b.network() : a.network());
            validateNames(adopted);
            require(graph.register(adopted.device()));
            Change result = graph.link(first, second);
            if (result != Change.APPLIED) {
                graph.remove(adopted.id());
                require(result);
            }
            nodes.put(adopted.id(), adopted);
            indexPorts(adopted);
        } else {
            require(graph.link(first, second));
        }
        links.add(edge);
    }

    void alias(UUID id, String alias) {
        Node old = requireNode(id);
        replace(old, new Node(id, old.network(), old.kind(), old.location(), alias, old.ports()));
    }

    void port(UUID id, Direction face, Mode mode, String alias) {
        Node old = requireNode(id);
        if (old.kind() != Kind.INTERFACE) {
            throw new IllegalArgumentException("Redstone ports require a Network Interface");
        }
        var next = new ArrayList<>(old.ports());
        next.removeIf(port -> port.face() == face);
        if (alias != null) {
            UUID identity = old.ports().stream().filter(port -> port.face() == face && port.mode() == mode)
                    .map(Port::id).findFirst().orElseGet(UUID::randomUUID);
            next.add(new Port(identity, alias, face, mode));
        }
        replace(old, new Node(id, old.network(), old.kind(), old.location(), old.alias(), next));
    }

    private void replace(Node old, Node next) {
        validateNames(next);
        if (next.network() != null) {
            require(graph.alias(next.id(), next.alias()));
        }
        removePorts(old);
        nodes.put(next.id(), next);
        indexPorts(next);
    }

    private void validateNames(Node node) {
        if (node.alias() != null) {
            name(node.alias());
        }
        if ((node.kind() != Kind.INTERFACE && !node.ports().isEmpty()) || node.ports().size() > 6) {
            throw new IllegalArgumentException("Invalid port configuration");
        }
        var seen = new java.util.HashSet<String>();
        var faces = java.util.EnumSet.noneOf(Direction.class);
        var identities = new java.util.HashSet<UUID>();
        for (Port port : node.ports()) {
            name(port.alias());
            PortTarget conflict = port(node.network(), port.alias());
            PortTarget duplicateId = portsById.get(port.id());
            if ((duplicateId != null && !duplicateId.device().equals(node.id())) || !identities.add(port.id())
                    || !seen.add(port.alias()) || !faces.add(port.face())
                    || (conflict != null && !conflict.device().equals(node.id()))) {
                throw new IllegalArgumentException("Duplicate port alias or face");
            }
        }
    }

    private void indexPorts(Node node) {
        if (node.network() != null) {
            for (Port port : node.ports()) {
                var target = new PortTarget(node.id(), port);
                ports.put(new Address(node.network(), port.alias()), target);
                portsById.put(port.id(), target);
            }
        }
    }

    private void removePorts(Node node) {
        if (node.network() != null) {
            for (Port port : node.ports()) {
                ports.remove(new Address(node.network(), port.alias()));
                portsById.remove(port.id());
            }
        }
    }

    private Node requireNode(UUID id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown device");
        }
        return node;
    }

    static NetworkDirectory restore(int range, List<Node> nodes, List<Edge> edges) {
        var directory = new NetworkDirectory(range);
        nodes.stream().filter(node -> node.kind() == Kind.COMPUTER).forEach(directory::insert);
        nodes.stream().filter(node -> node.kind() != Kind.COMPUTER).forEach(directory::insert);
        for (Edge edge : edges) {
            if (directory.node(edge.first()) == null || directory.node(edge.second()) == null) {
                throw new IllegalArgumentException("Link references an unknown endpoint");
            }
            require(directory.graph.link(edge.first(), edge.second()));
            if (!directory.links.add(edge)) {
                throw new IllegalArgumentException("Duplicate saved link");
            }
        }
        return directory;
    }

    static void name(String alias) {
        if (alias.length() > 32 || !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Alias must be a case-sensitive identifier of at most 32 characters");
        }
    }

    private static void require(Change change) {
        if (change != Change.APPLIED && change != Change.UNCHANGED) {
            throw new IllegalArgumentException("Network rejected the operation: " + change.name().toLowerCase(java.util.Locale.ROOT));
        }
    }
}
