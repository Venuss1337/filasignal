package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/**
 * The item of a FilaSignal block. Its tooltip says in one line what the device does, from the language key
 * {@code block.filasignal.NAME.tooltip}, and how to start using it.
 */
public final class DeviceItem extends BlockItem {
    public DeviceItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        String key = getDescriptionId();
        tooltip.accept(Component.translatable(key + ".tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable(key + ".hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
