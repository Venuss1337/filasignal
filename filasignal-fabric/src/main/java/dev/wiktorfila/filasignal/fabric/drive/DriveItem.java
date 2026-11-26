package dev.wiktorfila.filasignal.fabric.drive;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/** Programmable Data Drive. Insert it by using it on a computer or a programmable device. */
public final class DriveItem extends Item {
    public DriveItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        DriveTag tag = stack.get(Drives.TAG);
        if (tag == null) {
            tooltip.accept(Component.translatable("item.filasignal.data_drive.blank").withStyle(ChatFormatting.GRAY));
        } else {
            if (!tag.label().isEmpty()) {
                tooltip.accept(Component.literal(tag.label()).withStyle(ChatFormatting.AQUA));
            }
            tooltip.accept(Component.translatable("item.filasignal.data_drive.target",
                    tag.target().isEmpty() ? "-" : tag.target()).withStyle(ChatFormatting.GRAY));
            if (flag.isAdvanced()) {
                tooltip.accept(Component.literal(tag.id().toString()).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        tooltip.accept(Component.translatable("item.filasignal.data_drive.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
