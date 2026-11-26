package dev.wiktorfila.filasignal.fabric.computer;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Registers the common-side programmable computer block, item and block entity. */
public final class ComputerBlocks {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("filasignal", "programmable_computer");
    static Block computer;
    static BlockEntityType<ComputerBlockEntity> computerType;

    private ComputerBlocks() {
    }

    /** Returns the registered computer block for server-side interaction filtering. */
    public static Block block() { return computer; }

    /** Called once during mod initialization before registries freeze. */
    public static void register() {
        var blockKey = ResourceKey.create(Registries.BLOCK, ID);
        var itemKey = ResourceKey.create(Registries.ITEM, ID);
        computer = Registry.register(BuiltInRegistries.BLOCK, blockKey,
                new ComputerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).noOcclusion()
                        .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE).setId(blockKey)));
        Registry.register(BuiltInRegistries.ITEM, itemKey, new dev.wiktorfila.filasignal.fabric.peripheral.DeviceItem(
                computer, new Item.Properties().setId(itemKey)));
        computerType = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID,
                FabricBlockEntityTypeBuilder.create(ComputerBlockEntity::new, computer).build());
    }
}
