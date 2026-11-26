package dev.wiktorfila.filasignal.fabric.drive;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Registers the Programmable Data Drive and its item component, and offers helpers for drive stacks. */
public final class Drives {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("filasignal", "data_drive");
    public static DataComponentType<DriveTag> TAG;
    public static Item DRIVE;

    private Drives() {}

    public static void register() {
        TAG = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath("filasignal", "drive"),
                DataComponentType.<DriveTag>builder().persistent(DriveTag.CODEC).networkSynchronized(DriveTag.STREAM_CODEC).build());
        var key = ResourceKey.create(Registries.ITEM, ID);
        DRIVE = Registry.register(BuiltInRegistries.ITEM, key, new DriveItem(new Item.Properties().setId(key).stacksTo(1)));
        ServerLifecycleEvents.SERVER_STOPPED.register(DriveStore::forget);
    }

    public static boolean isDrive(ItemStack stack) {
        return !stack.isEmpty() && stack.is(DRIVE);
    }

    /** The drive's component, or null for a blank drive that has never been inserted. */
    public static DriveTag tag(ItemStack stack) {
        return isDrive(stack) ? stack.get(TAG) : null;
    }

    /** Assigns an identity to a blank drive. Returns the existing tag otherwise. */
    public static DriveTag identify(ItemStack stack) {
        DriveTag tag = stack.get(TAG);
        if (tag == null) {
            tag = new DriveTag(UUID.randomUUID(), "", "");
            stack.set(TAG, tag);
        }
        return tag;
    }

    /** Gives a removed drive to a player, dropping it at their feet when their inventory is full. */
    public static void give(net.minecraft.server.level.ServerPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) {
            net.minecraft.world.Containers.dropItemStack(player.level(), player.getX(), player.getY(), player.getZ(), stack);
        }
    }

    public static ItemStack blank() {
        return new ItemStack(DRIVE);
    }
}
