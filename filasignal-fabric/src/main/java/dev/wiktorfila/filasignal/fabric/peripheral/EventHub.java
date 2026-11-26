package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.runtime.event.Simulation;
import dev.wiktorfila.filasignal.core.runtime.execution.Interpreter;
import dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.computer.ProgramHost;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Routes native hardware events and custom messages to running programs. Server-thread only.
 *
 * <p>Each running program's handlers are indexed when it activates. A handler on a device constant, such as
 * {@code on gate.entityEnter(entity)}, is a <em>remote listener</em>: its device name is resolved against the
 * program's network every {@value #RESOLVE_INTERVAL} ticks and whenever the network changes. A device's own program
 * handles {@code device.*} events. Producers ask {@link #wants} before observing anything, so hardware nobody listens
 * to does no extra work, and one observation is shared by every listener. Delivery only queues the event for the
 * program's next turn on the shared scheduler; no handler runs synchronously and no thread is created.
 */
final class EventHub {
    static final int RESOLVE_INTERVAL = 20;
    static final int MAX_REMOTE_LISTENERS = 64;
    static final int MAX_EMITS_PER_TICK = 16;
    static final int MAX_FANOUT = 64;
    static final int MAX_CUSTOM_BYTES = 4096;
    static final int MAX_CUSTOM_NAME = 64;
    private static final java.util.regex.Pattern CUSTOM_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,63}");
    private static final int AUTHORITY_INTERVAL = 100;

    private final PeripheralRuntime runtime;
    private final Map<String, Listening> programs = new LinkedHashMap<>();
    // device -> event name -> routes, rebuilt after resolution.
    private final Map<UUID, Map<String, List<Route>>> routes = new HashMap<>();
    private final DeviceObservers observers;
    private boolean stale = true;
    private long lastResolve = Long.MIN_VALUE;
    private long delivered;
    private long dropped;
    private long customSent;
    private long customRejected;

    EventHub(PeripheralRuntime runtime) {
        this.runtime = runtime;
        this.observers = new DeviceObservers(runtime, this);
    }

    DeviceObservers observers() { return observers; }

    /** One handler that receives a device's events. A negative subscription means a {@code device.*} handler. */
    private record Route(Listening program, int subscription) {}

    /** A handler on a device constant and the device it currently resolves to. */
    private static final class Remote {
        final int subscription;
        final String event;
        final String name;
        final Kind kind;
        UUID device;
        Boolean connected;
        String problem = "Not resolved yet";

        Remote(int subscription, String event, String name, Kind kind) {
            this.subscription = subscription;
            this.event = event;
            this.name = name;
            this.kind = kind;
        }
    }

    private static final class Listening {
        final ProgramHost host;
        final Simulation.Controller controller;
        final UUID self;
        final boolean computer;
        final List<Remote> remotes = new ArrayList<>();
        final Set<String> local = new java.util.HashSet<>();
        // Custom event names this program handles with on events.custom("name", ...).
        final Set<String> custom = new java.util.HashSet<>();
        long emitTick = -1;
        int emits;

        Listening(ProgramHost host, Simulation.Controller controller, UUID self, boolean computer) {
            this.host = host;
            this.controller = controller;
            this.self = self;
            this.computer = computer;
        }
    }

    // Program lifecycle

    void attached(ProgramHost host, Simulation.Controller controller) {
        UUID self;
        boolean computer = !(host instanceof PeripheralEntity);
        try {
            self = host instanceof PeripheralEntity device ? device.identity() : UUID.fromString(host.executionId());
        } catch (IllegalArgumentException invalid) {
            return;
        }
        var listening = new Listening(host, controller, self, computer);
        for (var subscription : controller.subscriptions()) {
            if (subscription.source() != null) {
                Kind kind = HardwareHosts.kindOf(subscription.source().type());
                if (kind != null && subscription.source().reference() != null
                        && listening.remotes.size() < MAX_REMOTE_LISTENERS) {
                    listening.remotes.add(new Remote(subscription.index(), subscription.name(),
                            subscription.source().reference(), kind));
                }
            } else if (subscription.name().startsWith("device.")) {
                listening.local.add(subscription.name().substring("device.".length()));
            } else if (subscription.name().equals("events.custom") && !subscription.filters().isEmpty()
                    && subscription.filters().getFirst() instanceof Text name) {
                listening.custom.add(name.value());
            }
        }
        programs.put(host.executionId(), listening);
        stale = true;
        if (!computer) {
            publish(self, "programStarted", List.of());
        }
    }

    void detached(ProgramHost host) {
        Listening removed = programs.remove(host.executionId());
        if (removed != null) {
            stale = true;
            if (!removed.computer) {
                publish(removed.self, "programStopped", List.of());
            }
        }
    }

    void failed(ProgramHost host, String message) {
        if (host instanceof PeripheralEntity device && device.identity() != null) {
            publish(device.identity(), "programFailed", List.of(new Text(message.length() > 200 ? message.substring(0, 200) : message)));
        }
    }

    /** The network changed: relink listeners on the next tick instead of waiting for the periodic pass. */
    void invalidate() {
        stale = true;
    }

    void tick(MinecraftServer server) {
        long now = runtime.tickCount();
        if (stale || now - lastResolve >= RESOLVE_INTERVAL) {
            resolveAll();
            lastResolve = now;
            stale = false;
        }
        if (now % AUTHORITY_INTERVAL == 0) {
            checkAuthority(server);
        }
        observers.tick(now);
    }

    // Routing

    /** True when a running program handles this event from this device. Producers check before observing. */
    boolean wants(UUID device, String event) {
        var events = routes.get(device);
        return events != null && events.containsKey(event);
    }

    /** True when any program handles any event from this device. */
    boolean wantsAny(UUID device, Set<String> events) {
        var handled = routes.get(device);
        if (handled == null) {
            return false;
        }
        for (String event : events) {
            if (handled.containsKey(event)) {
                return true;
            }
        }
        return false;
    }

    /** Devices that currently have at least one listener. */
    Set<UUID> watched() {
        return routes.keySet();
    }

    /**
     * Queues a native event for every handler listening to this device. Values must be valid event values; each
     * handler receives its own copy.
     */
    void publish(UUID device, String event, List<Value> values) {
        var events = routes.get(device);
        List<Route> targets = events == null ? null : events.get(event);
        if (targets == null) {
            return;
        }
        for (Route route : targets) {
            boolean accepted = route.subscription() < 0
                    ? route.program().controller.emit("device." + event, List.of(), values)
                    : route.program().controller.deliver(route.subscription(), values);
            if (accepted) {
                delivered++;
            } else {
                dropped++;
            }
        }
    }

    private void resolveAll() {
        routes.clear();
        for (Listening program : programs.values()) {
            for (String event : program.local) {
                route(program.self, event, new Route(program, -1));
            }
            for (Remote remote : program.remotes) {
                Node node;
                try {
                    node = resolve(program.self, program.computer, remote.name, remote.kind);
                    remote.problem = null;
                } catch (HostFailure failure) {
                    node = null;
                    remote.problem = failure.getMessage();
                }
                boolean connected = node != null;
                if (remote.connected != null && remote.connected != connected) {
                    // Connection changes are the only events a listener raises itself.
                    if (remote.event.equals(connected ? "connected" : "disconnected")) {
                        if (program.controller.deliver(remote.subscription, List.of())) {
                            delivered++;
                        } else {
                            dropped++;
                        }
                    }
                }
                remote.connected = connected;
                remote.device = node == null ? null : node.id();
                if (node != null) {
                    route(node.id(), remote.event, new Route(program, remote.subscription));
                }
            }
        }
    }

    private void route(UUID device, String event, Route route) {
        routes.computeIfAbsent(device, ignored -> new HashMap<>()).computeIfAbsent(event, ignored -> new ArrayList<>()).add(route);
    }

    /**
     * Resolves a device name for a program. A computer reaches loaded devices connected to it. A device reaches
     * loaded devices of its own network joined to it through loaded links, even while the computer is unloaded.
     *
     * @throws HostFailure {@code device.unknown}, {@code device.type} or {@code device.unavailable}
     */
    Node resolve(UUID self, boolean computer, String name, Kind kind) {
        var directory = runtime.directory();
        Node own = directory.node(self);
        UUID network = computer ? self : own == null ? null : own.network();
        if (network == null) {
            throw new HostFailure("device.unknown", "This device is not linked to a computer's network yet");
        }
        var found = directory.discover(network, name);
        Node node = found.device().map(device -> directory.node(device.id())).orElse(null);
        if (node == null) {
            throw new HostFailure("device.unknown", "No device named " + name + " is in this network. Check the name in its device form");
        }
        if (node.kind() != kind) {
            throw new HostFailure("device.type", name + " is a " + HardwareHosts.title(node.kind()) + ", not a " + HardwareHosts.title(kind));
        }
        boolean reachable = computer ? runtime.available(node) : directory.connected(self, node.id()) && runtime.live(node.id()) != null;
        if (!reachable) {
            throw new HostFailure("device.unavailable", name + " is disconnected or its area isn't loaded");
        }
        return node;
    }

    // Custom events

    /**
     * Sends a custom event from a running program to programs on the same network that handle
     * {@code events.custom(name, ...)}. With a target, only the program running on that named device receives it.
     *
     * @return the number of programs that received the event
     * @throws HostFailure {@code event.invalid}, {@code event.limit} or a device resolution failure
     */
    int send(String senderExecution, String target, String name, Value value) {
        Listening sender = programs.get(senderExecution);
        if (sender == null) {
            throw new HostFailure("event.invalid", "Only a running program can send events");
        }
        if (name.length() > MAX_CUSTOM_NAME || !CUSTOM_NAME.matcher(name).matches()) {
            customRejected++;
            throw new HostFailure("event.invalid", "Event names start with a letter and use up to 64 letters, digits or _ . : -");
        }
        if (!Interpreter.eventValue(value)) {
            customRejected++;
            throw new HostFailure("event.invalid", "Events carry text, numbers, true/false, nil, lists and dictionaries, without devices");
        }
        try {
            if (NamedStorage.encode(value).length > MAX_CUSTOM_BYTES) {
                customRejected++;
                throw new HostFailure("event.limit", "An event value can be at most " + MAX_CUSTOM_BYTES + " bytes");
            }
        } catch (NamedStorage.Failure failure) {
            customRejected++;
            throw new HostFailure("event.invalid", failure.getMessage());
        }
        long now = runtime.tickCount();
        if (sender.emitTick != now) {
            sender.emitTick = now;
            sender.emits = 0;
        }
        if (++sender.emits > MAX_EMITS_PER_TICK) {
            customRejected++;
            throw new HostFailure("event.limit", "A program can send at most " + MAX_EMITS_PER_TICK + " events per tick");
        }
        var receivers = new ArrayList<Listening>();
        if (target != null) {
            Node node = resolveAny(sender, target);
            for (Listening program : programs.values()) {
                if (program.self.equals(node.id()) && program.custom.contains(name)) {
                    receivers.add(program);
                }
            }
        } else {
            for (Listening program : programs.values()) {
                if (program != sender && program.custom.contains(name) && receivers.size() < MAX_FANOUT
                        && sameNetwork(sender, program)) {
                    receivers.add(program);
                }
            }
        }
        DictValue from = senderInfo(sender);
        int count = 0;
        for (Listening receiver : receivers) {
            if (receiver.controller.emit("events.custom", List.of(new Text(name)), List.of(value, from))) {
                count++;
                customSent++;
            } else {
                dropped++;
            }
        }
        return count;
    }

    private Node resolveAny(Listening sender, String name) {
        var directory = runtime.directory();
        Node own = directory.node(sender.self);
        UUID network = sender.computer ? sender.self : own == null ? null : own.network();
        var found = network == null ? null : directory.discover(network, name);
        Node node = found == null ? null : found.device().map(device -> directory.node(device.id())).orElse(null);
        if (node == null) {
            throw new HostFailure("device.unknown", "No device named " + name + " is in this network");
        }
        if (!directory.connected(sender.self, node.id()) || runtime.live(node.id()) == null) {
            throw new HostFailure("device.unavailable", name + " is disconnected or its area isn't loaded");
        }
        return node;
    }

    private boolean sameNetwork(Listening a, Listening b) {
        return runtime.directory().connected(a.self, b.self);
    }

    /** Server-made sender details. A program cannot set them, so a receiver can trust who sent an event. */
    private DictValue senderInfo(Listening sender) {
        Node node = runtime.directory().node(sender.self);
        var values = new LinkedHashMap<String, Value>();
        values.put("id", new Text(sender.self.toString()));
        values.put("kind", new Text(node == null ? (sender.computer ? "computer" : "device") : node.kind().id()));
        values.put("name", node == null || node.alias() == null ? new Text(sender.computer ? "computer" : "unnamed") : new Text(node.alias()));
        return new DictValue(values);
    }

    // Authority

    /**
     * Stops device programs whose starter no longer has access to the device, for example after being removed from
     * the trusted list. The program's stored data and globals are kept.
     */
    private void checkAuthority(MinecraftServer server) {
        for (Listening program : List.copyOf(programs.values())) {
            if (program.host instanceof PeripheralEntity device && !device.authorityValid(server)) {
                ComputerLifecycle.requestControl(server, device, false, "", null,
                        "Stopped: the player who started this program no longer has access to the device");
            }
        }
    }

    // Diagnostics

    /** A short status line for the dashboard's diagnostics. */
    String summary() {
        int listeners = programs.values().stream().mapToInt(program -> program.remotes.size() + program.local.size()).sum();
        return listeners + " event handlers · " + delivered + " delivered · " + dropped + " dropped · "
                + customSent + " custom sent · " + customRejected + " rejected";
    }

    /** Remote listener problems for one program, such as an unknown or unloaded device, for its status. */
    List<String> problems(String executionId) {
        Listening program = programs.get(executionId);
        if (program == null) {
            return List.of();
        }
        var problems = new ArrayList<String>();
        for (Remote remote : program.remotes) {
            if (remote.problem != null && problems.size() < 4) {
                problems.add(remote.name + "." + remote.event + ": " + remote.problem);
            }
        }
        return problems;
    }

    /** Devices a program listens to, by name, for the dashboard. */
    int listening(String executionId) {
        Listening program = programs.get(executionId);
        return program == null ? 0 : program.remotes.size() + program.local.size();
    }
}
