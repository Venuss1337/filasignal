package dev.wiktorfila.filasignal.fabric.peripheral;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Location;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.UUID;

/** One versioned world record owns all topology, names and interface face configurations. */
final class NetworkSave extends SavedData {
    static final SavedDataType<NetworkSave> TYPE = new SavedDataType<>(PeripheralBlocks.id("device_network"),
            NetworkSave::new, Codec.PASSTHROUGH.xmap(NetworkSave::read, NetworkSave::write), null);

    private NetworkDirectory directory = new NetworkDirectory(32);
    private Dynamic<?> rejected;

    boolean enabled() { return rejected == null; }

    NetworkDirectory directory() {
        if (rejected != null) {
            throw new IllegalArgumentException("Network save is invalid; restore a compatible backup");
        }
        return directory;
    }

    void replace(NetworkDirectory next) {
        directory();
        directory = next;
        setDirty();
    }

    static NetworkSave read(Dynamic<?> raw) {
        var data = new NetworkSave();
        try {
            if (!(raw.convert(NbtOps.INSTANCE).getValue() instanceof CompoundTag tag)
                    || tag.getIntOr("version", -1) < 1 || tag.getIntOr("version", -1) > 3) {
                throw new IllegalArgumentException("Unsupported device save format");
            }
            ListTag nodes = tag.getList("nodes").orElseThrow();
            ListTag edges = tag.getList("links").orElseThrow();
            if (nodes.size() > NetworkDirectory.MAX_ENDPOINTS || edges.size() > NetworkDirectory.MAX_ENDPOINTS * 8) {
                throw new IllegalArgumentException("Network save exceeds limits");
            }
            var restored = new ArrayList<Node>();
            for (int i = 0; i < nodes.size(); i++) {
                restored.add(readNode(nodes.getCompound(i).orElseThrow()));
            }
            var links = new ArrayList<Edge>();
            for (int i = 0; i < edges.size(); i++) {
                CompoundTag edge = edges.getCompound(i).orElseThrow();
                links.add(new Edge(uuid(edge, "a"), uuid(edge, "b")));
            }
            data.directory = NetworkDirectory.restore(tag.getInt("range").orElseThrow(), restored, links);
            for (int i = 0; i < nodes.size(); i++) {
                CompoundTag node = nodes.getCompound(i).orElseThrow();
                if (node.contains("owner")) {
                    ListTag trusted = node.getList("trusted").orElseThrow();
                    if (trusted.size() > NetworkAccess.MAX_TRUSTED) {
                        throw new IllegalArgumentException("Too many trusted players");
                    }
                    var players = new java.util.HashSet<UUID>();
                    for (int slot = 0; slot < trusted.size(); slot++) {
                        players.add(uuid(trusted.getCompound(slot).orElseThrow(), "id"));
                    }
                    data.directory.access(restored.get(i).id(), new NetworkAccess(uuid(node, "owner"), players));
                }
            }
            var inventories = tag.getList("inventories").orElseGet(ListTag::new);
            if (inventories.size() > NetworkDirectory.MAX_ENDPOINTS * 6) {
                throw new IllegalArgumentException("Too many inventory bindings");
            }
            for (int i = 0; i < inventories.size(); i++) {
                var item = inventories.getCompound(i).orElseThrow();
                var binding = new InventoryBindings.Binding(uuid(item, "id"), uuid(item, "network"), uuid(item, "interface"),
                        Direction.valueOf(item.getString("face").orElseThrow()), item.getString("alias").orElseThrow(),
                        uuid(item, "target"), uuid(item, "owner"));
                Node node = data.directory.node(binding.interfaceId());
                NetworkAccess access = data.directory.access(binding.network());
                if (node == null || !PeripheralRuntime.inventoryFaces(node.kind()) || !binding.network().equals(node.network())
                        || access == null || !access.owner().equals(binding.owner())) {
                    throw new IllegalArgumentException("Invalid saved inventory grant");
                }
                data.directory.inventories().add(binding);
            }
            for (Node node : restored) {
                data.directory.detach(node.id());
            }
        } catch (IllegalArgumentException | java.util.NoSuchElementException error) {
            data.rejected = raw;
            LoggerFactory.getLogger("filasignal/devices").error("Device topology disabled: {}", error.getMessage());
        }
        return data;
    }

    private static Node readNode(CompoundTag tag) {
        var ports = new ArrayList<Port>();
        ListTag list = tag.getList("ports").orElseThrow();
        if (list.size() > 6) {
            throw new IllegalArgumentException("Too many redstone ports");
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag port = list.getCompound(i).orElseThrow();
            ports.add(new Port(port.contains("id") ? uuid(port, "id") : UUID.randomUUID(), port.getString("alias").orElseThrow(),
                    Direction.valueOf(port.getString("face").orElseThrow()),
                    Mode.valueOf(port.getString("mode").orElseThrow())));
        }
        return new Node(uuid(tag, "id"), tag.contains("network") ? uuid(tag, "network") : null,
                Kind.valueOf(tag.getString("kind").orElseThrow()),
                new Location(tag.getString("dimension").orElseThrow(), tag.getInt("x").orElseThrow(),
                        tag.getInt("y").orElseThrow(), tag.getInt("z").orElseThrow()),
                tag.getString("alias").orElse(null), ports);
    }

    private static UUID uuid(CompoundTag tag, String key) {
        return UUID.fromString(tag.getString(key).orElseThrow());
    }

    Dynamic<?> write() {
        if (rejected != null) {
            return rejected;
        }
        var tag = new CompoundTag();
        // Version 3 adds the dedicated hardware kinds. Versions 1 and 2 read unchanged.
        tag.putInt("version", 3);
        tag.putInt("range", directory.range());
        var nodes = new ListTag();
        for (Node node : directory.nodes()) {
            var item = new CompoundTag();
            item.putString("id", node.id().toString());
            if (node.network() != null) {
                item.putString("network", node.network().toString());
            }
            item.putString("kind", node.kind().name());
            item.putString("dimension", node.location().dimension());
            item.putInt("x", node.location().x());
            item.putInt("y", node.location().y());
            item.putInt("z", node.location().z());
            if (node.alias() != null) {
                item.putString("alias", node.alias());
            }
            var ports = new ListTag();
            for (Port port : node.ports()) {
                var data = new CompoundTag();
                data.putString("id", port.id().toString());
                data.putString("alias", port.alias());
                data.putString("face", port.face().name());
                data.putString("mode", port.mode().name());
                ports.add(data);
            }
            item.put("ports", ports);
            NetworkAccess access = directory.access(node.id());
            if (access != null) {
                item.putString("owner", access.owner().toString());
                var trusted = new ListTag();
                access.trusted().stream().sorted().forEach(player -> {
                    var entry = new CompoundTag();
                    entry.putString("id", player.toString());
                    trusted.add(entry);
                });
                item.put("trusted", trusted);
            }
            nodes.add(item);
        }
        tag.put("nodes", nodes);
        var links = new ListTag();
        for (Edge edge : directory.links()) {
            var item = new CompoundTag();
            item.putString("a", edge.first().toString());
            item.putString("b", edge.second().toString());
            links.add(item);
        }
        tag.put("links", links);
        var inventories = new ListTag();
        for (var binding : directory.inventories().all()) {
            var item = new CompoundTag();
            item.putString("id", binding.id().toString());
            item.putString("network", binding.network().toString());
            item.putString("interface", binding.interfaceId().toString());
            item.putString("face", binding.face().name());
            item.putString("alias", binding.alias());
            item.putString("target", binding.inventoryId().toString());
            item.putString("owner", binding.owner().toString());
            inventories.add(item);
        }
        tag.put("inventories", inventories);
        return new Dynamic<>(NbtOps.INSTANCE, tag);
    }
}
