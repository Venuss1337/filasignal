package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.peripheral.InventoryBindings.Binding;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.UUID;

/** Looks up sided storage only for the duration of a call on the owning server thread. */
final class InventoryService {
    private static final int MAX_SLOTS = 54;
    private static AttachmentType<UUID> identityType;
    private final PeripheralRuntime runtime;

    record OpenInventory(UUID id, SlottedStorage<ItemVariant> storage, int slots) {
        Storage<ItemVariant> boundedStorage() {
            var exposed = new ArrayList<SingleSlotStorage<ItemVariant>>(slots);
            for (int slot = 0; slot < slots; slot++) {
                exposed.add(storage.getSlot(slot));
            }
            return new CombinedStorage<>(exposed);
        }
    }
    private record Target(AttachmentTarget attachment, SlottedStorage<ItemVariant> storage, int slots) {}

    static void register() {
        // Chunk NBT can be decoded before the first controller or inventory lookup.
        identityType = AttachmentRegistry.createPersistent(PeripheralBlocks.id("inventory_identity"), UUIDUtil.CODEC);
    }

    InventoryService(PeripheralRuntime runtime) {
        this.runtime = runtime;
    }

    void bind(Node node, Direction face, String alias) {
        var directory = runtime.directory();
        var bindings = directory.inventories();
        var old = bindings.at(node.id(), face);
        if (alias == null) {
            bindings.remove(old);
            return;
        }
        NetworkDirectory.name(alias);
        NetworkAccess access = directory.access(node.network());
        if (access == null) {
            throw new IllegalArgumentException("Inventory automation requires an owned computer");
        }
        Binding conflict = bindings.byAlias(node.network(), alias);
        if (conflict != null && conflict != old) {
            throw new IllegalArgumentException("This inventory alias is already in use");
        }
        try {
            Target target = target(node, face, access.owner(), true);
            UUID identity = target.attachment().getAttachedOrCreate(identityType, UUID::randomUUID);
            // Keep old handles on a rename, but never carry one over to a different physical block.
            UUID id = old != null && old.inventoryId().equals(identity) && old.owner().equals(access.owner())
                    ? old.id() : UUID.randomUUID();
            bindings.remove(old);
            bindings.add(new Binding(id, node.network(), node.id(), face, alias, identity, access.owner()));
        } catch (HostFailure error) {
            throw new IllegalArgumentException(error.getMessage());
        }
    }

    OpenInventory open(UUID controller, Binding binding, boolean write) {
        return open(controller, binding, write, true);
    }

    /**
     * Opens a granted inventory. With {@code routed}, the device must be reachable from the computer through loaded
     * relays. Without it, as for a device running its own program, the device only needs to be loaded.
     */
    OpenInventory open(UUID controller, Binding binding, boolean write, boolean routed) {
        if (binding == null || !controller.equals(binding.network())) {
            throw new HostFailure("inventory.unknown", "No inventory with that identity exists in this network");
        }
        var directory = runtime.directory();
        NetworkAccess access = directory.access(controller);
        if (access == null || !access.owner().equals(binding.owner())) {
            throw new HostFailure("host.denied", "The controller no longer owns this inventory grant");
        }
        var node = directory.node(binding.interfaceId());
        if (node == null || !controller.equals(node.network())) {
            throw new HostFailure("inventory.unavailable", "The interface no longer belongs to this network");
        }
        Target target = target(node, binding.face(), access.owner(), write, routed);
        if (!binding.inventoryId().equals(target.attachment().getAttached(identityType))) {
            throw new HostFailure("inventory.stale", "The inventory block was replaced; configure the face again");
        }
        return new OpenInventory(binding.inventoryId(), target.storage(), target.slots());
    }

    private Target target(Node node, Direction face, UUID owner, boolean write) {
        return target(node, face, owner, write, true);
    }

    private Target target(Node node, Direction face, UUID owner, boolean write, boolean routed) {
        if ((routed && !runtime.available(node)) || !(runtime.live(node.id()) instanceof PeripheralEntity endpoint)
                || !(endpoint.getLevel() instanceof ServerLevel level)) {
            throw new HostFailure("device.unavailable", "The inventory interface is unloaded or disconnected");
        }
        BlockPos pos = endpoint.getBlockPos().relative(face);
        if (!LoadedChunks.contains(level, pos)) {
            throw new HostFailure("world.unloaded", "The inventory chunk is unloaded");
        }
        if (!InventoryProtection.check(level, pos, owner, write)) {
            throw new HostFailure("host.denied", "World protection denied access to this inventory");
        }
        var state = level.getBlockState(pos);
        if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            throw new HostFailure("inventory.unsupported", "Use a single chest or another single-block inventory");
        }
        var entity = level.getBlockEntity(pos);
        if (entity == null || entity.isRemoved()) {
            throw new HostFailure("inventory.missing", "No inventory block exists on that face");
        }
        if (entity instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity container && container.isLocked()) {
            throw new HostFailure("host.denied", "The inventory is locked");
        }
        if (entity instanceof net.minecraft.world.RandomizableContainer container && container.getLootTable() != null) {
            throw new HostFailure("inventory.unavailable", "Open the inventory normally before automating it");
        }
        var storage = ItemStorage.SIDED.find(level, pos, state, entity, face.getOpposite());
        if (!(storage instanceof SlottedStorage<ItemVariant> slotted)) {
            throw new HostFailure("inventory.unsupported", "The inventory does not expose sided item slots");
        }
        int slots = slotted.getSlotCount();
        if (slots < 0 || slots > MAX_SLOTS) {
            throw new HostFailure("inventory.limit", "Inventory automation supports up to 54 sided slots");
        }
        return new Target(AttachmentTarget.class.cast(entity), slotted, slots);
    }
}
