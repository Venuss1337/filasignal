package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.*;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** Server-thread bridge between loaded block entities and persisted device identities. */
final class PeripheralRuntime {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("filasignal/devices");
    private static final int ATTACHMENTS_PER_TICK = 32;
    private final MinecraftServer server;
    private final Map<UUID, BlockEntity> loaded = new LinkedHashMap<>();
    private final Map<BlockEntity, UUID> identities = new java.util.IdentityHashMap<>();
    private final LinkedHashSet<PeripheralEntity> pending = new LinkedHashSet<>();
    private final PeripheralSampler sampler = new PeripheralSampler();
    private final EntityDetection detection = new EntityDetection();
    private final EventHub events = new EventHub(this);
    private final Alarms alarms = new Alarms(events);
    private final Pulses pulses = new Pulses();

    PeripheralRuntime(MinecraftServer server) {
        this.server = server;
    }

    private NetworkSave store() {
        return server.overworld().getDataStorage().computeIfAbsent(NetworkSave.TYPE);
    }

    NetworkDirectory directory() { return store().directory(); }
    Alarms alarms() { return alarms; }
    EntityDetection detection() { return detection; }
    EventHub events() { return events; }
    Pulses pulses() { return pulses; }

    /** Saves and re-routes after a topology edit made directly on the directory. */
    void topologyChanged() {
        store().setDirty();
        events.invalidate();
        resetDisconnectedOutputs();
    }
    long tickCount() { return server.getTickCount(); }
    void forgetSamples(UUID id) { sampler.forget(id); }
    dev.wiktorfila.filasignal.fabric.drive.DriveStore drives() { return dev.wiktorfila.filasignal.fabric.drive.DriveStore.of(server); }

    /** Faces of these kinds can be approved as script-accessible inventories. */
    static boolean inventoryFaces(Kind kind) {
        return kind == Kind.INTERFACE || kind == Kind.INVENTORY_MODULE;
    }

    boolean available(Node node) {
        return node != null && directory().reachable(node) && live(node.id()) != null;
    }

    BlockEntity live(UUID id) {
        BlockEntity entity = loaded.get(id);
        return entity != null && !entity.isRemoved() && entity.getLevel() instanceof ServerLevel level
                && LoadedChunks.contains(level, entity.getBlockPos()) ? entity : null;
    }

    private void attach(ServerLevel level, BlockEntity entity, UUID id, Kind kind) {
        if (id == null || (loaded.containsKey(id) && loaded.get(id) != entity)) {
            throw new IllegalArgumentException("Invalid or duplicate loaded device identity");
        }
        NetworkDirectory graph = directory();
        boolean newDevice = graph.node(id) == null;
        graph.attach(new Node(id, kind == Kind.COMPUTER ? id : null, kind, location(level, entity.getBlockPos()),
                null, java.util.List.of()));
        events.invalidate();
        loaded.put(id, entity);
        identities.put(entity, id);
        if (newDevice) {
            store().setDirty();
        }
    }

    void computer(ServerLevel level, BlockEntity entity, UUID id) {
        if (identities.containsKey(entity) && !id.equals(identities.get(entity))) {
            throw new IllegalArgumentException("A programmable computer's identity cannot be changed while loaded");
        }
        attach(level, entity, id, Kind.COMPUTER);
    }

    void placed(ServerLevel level, BlockEntity entity, UUID id, Kind kind, UUID owner) {
        attach(level, entity, id, kind);
        if (directory().access(id) == null) {
            access(id, new NetworkAccess(owner, java.util.Set.of()));
        }
    }

    void access(UUID id, NetworkAccess access) {
        directory().access(id, access);
        store().setDirty();
        events.invalidate();
    }

    void activated(UUID computer) {
        for (Node node : directory().nodes()) {
            if (computer.equals(node.network())) {
                sampler.forget(node.id());
            }
        }
    }

    void enqueue(PeripheralEntity entity) {
        if (pending.size() < NetworkDirectory.MAX_ENDPOINTS) {
            pending.add(entity);
        } else {
            LOG.warn("Peripheral activation queue is full at {}", entity.getBlockPos());
        }
    }

    void unload(BlockEntity entity) {
        pending.remove(entity);
        UUID id = identities.remove(entity);
        if (id == null) {
            return;
        }
        loaded.remove(id);
        directory().detach(id);
        sampler.forget(id);
        events.observers().forget(id);
        events.invalidate();
        if (entity instanceof PeripheralEntity peripheral) {
            alarms.forget(peripheral);
            pulses.forget(peripheral);
            detection.forget(peripheral);
            peripheral.releaseDrive();
        }
        if (entity instanceof PeripheralEntity peripheral && entity.getLevel() instanceof ServerLevel level) {
            peripheral.clearOutputs(level);
        }
        resetDisconnectedOutputs();
    }

    void removed(ServerLevel level, BlockPos pos) {
        if (server.overworld() == null || !store().enabled()) {
            return;
        }
        Node node = directory().at(location(level, pos));
        if (node != null) {
            BlockEntity entity = loaded.get(node.id());
            if (entity != null) {
                unload(entity);
            }
            directory().remove(node.id());
            store().setDirty();
            events.invalidate();
            resetDisconnectedOutputs();
        }
    }

    /** Attaches a loaded device now if it is still waiting in the queue, for example before its own program starts. */
    void ensureAttached(PeripheralEntity entity) {
        if (identities.containsKey(entity) || entity.isRemoved() || !(entity.getLevel() instanceof ServerLevel level)
                || !LoadedChunks.contains(level, entity.getBlockPos())) {
            return;
        }
        pending.remove(entity);
        attach(level, entity, entity.identity(), entity.kind());
        if (entity.kind() == Kind.ALARM) {
            alarms.loaded(entity, level);
        }
        entity.loadedInWorld(level);
    }

    void attachPending() {
        var queue = pending.iterator();
        // Attaching only records a loaded device in the topology, so many can join per tick.
        for (int i = 0; i < ATTACHMENTS_PER_TICK && queue.hasNext(); i++) {
            PeripheralEntity entity = queue.next();
            queue.remove();
            if (!entity.isRemoved() && entity.getLevel() instanceof ServerLevel level
                    && LoadedChunks.contains(level, entity.getBlockPos())) {
                try {
                    attach(level, entity, entity.identity(), entity.kind());
                    if (entity.kind() == Kind.ALARM) {
                        alarms.loaded(entity, level);
                    }
                    entity.loadedInWorld(level);
                } catch (IllegalArgumentException ex) {
                    LOG.warn("Peripheral at {} rejected: {}", entity.getBlockPos(), ex.getMessage());
                }
            }
        }
    }

    void tick() {
        long started = System.nanoTime();
        if (!loaded.isEmpty()) {
            sampler.tick(this, server, loaded);
        }
        alarms.tick(server.getTickCount());
        pulses.tick(server.getTickCount());
        events.tick(server);
        latestNanos = System.nanoTime() - started;
    }

    private long latestNanos;

    /** Time spent sampling devices, running alarms and routing events in the latest tick. */
    long latestNanos() { return latestNanos; }

    /** Loaded devices attached to the topology. */
    int loadedDevices() { return loaded.size(); }

    void inputChanged(ServerLevel level, BlockPos pos) {
        if (loaded.isEmpty() || !LoadedChunks.contains(level, pos)) {
            return;
        }
        Node node = directory().at(location(level, pos));
        if (node != null && live(node.id()) instanceof PeripheralEntity entity
                && (available(node) || PeripheralSampler.local(server, entity)
                || events.wantsAny(node.id(), PeripheralSampler.CONTROLLER_EVENTS))) {
            sampler.neighborChanged(this, server, level, node, entity, available(node));
        }
    }

    Node requireDevice(ServerLevel level, BlockPos pos) {
        Node node = directory().at(location(level, pos));
        if (node == null || live(node.id()) == null || level.getBlockEntity(pos) != live(node.id())) {
            throw new IllegalArgumentException("No loaded FilaSignal device at that position");
        }
        return node;
    }

    void link(UUID first, UUID second, boolean unlink) {
        if (live(first) == null || live(second) == null) {
            throw new IllegalArgumentException("Both selected devices must remain loaded");
        }
        directory().link(first, second, unlink);
        store().setDirty();
        events.invalidate();
        resetDisconnectedOutputs();
    }

    void alias(Node node, String alias) {
        directory().alias(node.id(), alias);
        sampler.forget(node.id());
        store().setDirty();
        events.invalidate();
    }

    void port(Node node, Direction face, Mode mode, String alias) {
        directory().port(node.id(), face, mode, alias);
        if (live(node.id()) instanceof PeripheralEntity endpoint && endpoint.getLevel() instanceof ServerLevel level) {
            endpoint.output(level, face, 0);
        }
        store().setDirty();
        sampler.forget(node.id());
    }

    void inventory(Node node, Direction face, String alias) {
        if (!inventoryFaces(node.kind()) || node.network() == null) {
            throw new IllegalArgumentException("Select a Network Interface or Inventory Module connected to a computer");
        }
        new InventoryService(this).bind(node, face, alias);
        store().setDirty();
    }

    void range(int range) {
        NetworkDirectory next = NetworkDirectory.restore(range, directory().nodes(), directory().links());
        for (Node node : next.nodes()) {
            if (live(node.id()) == null) {
                next.detach(node.id());
            }
        }
        directory().copyConfigurationTo(next);
        store().replace(next);
    }

    private void resetDisconnectedOutputs() {
        for (var entry : loaded.entrySet()) {
            if (entry.getValue() instanceof PeripheralEntity peripheral
                    && peripheral.getLevel() instanceof ServerLevel level
                    && !directory().reachable(directory().node(entry.getKey()))) {
                peripheral.clearOutputs(level);
                sampler.forget(entry.getKey());
            }
        }
    }

    private static Location location(ServerLevel level, BlockPos position) {
        return new Location(level.dimension().identifier().toString(), position.getX(), position.getY(), position.getZ());
    }
}
