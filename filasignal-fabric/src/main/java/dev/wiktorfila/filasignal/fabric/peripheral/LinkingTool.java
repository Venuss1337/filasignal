package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

final class LinkingTool extends Item {
    private static final int SELECTION_TICKS = 200;
    private static final Map<MinecraftServer, Map<UUID, Selection>> SELECTIONS =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    private record Selection(UUID endpoint, int selectedAt) {}

    LinkingTool(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        tooltip.accept(Component.translatable("item.filasignal.linking_tool.tooltip")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.filasignal.linking_tool.hint")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player) || player.isSpectator()) {
            return InteractionResult.FAIL;
        }
        var server = level.getServer();
        var selections = SELECTIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        Selection selection = selections.remove(player.getUUID());
        try {
            var runtime = PeripheralLifecycle.runtime(server);
            var target = runtime.requireDevice(level, context.getClickedPos());
            var actor = NetworkPermission.Actor.from(player);
            NetworkPermission.require(runtime.directory(), target, actor, false);
            if (selection == null || server.getTickCount() - selection.selectedAt() >= SELECTION_TICKS) {
                if (selections.size() >= 128) {
                    throw new IllegalArgumentException("Too many pending tool selections");
                }
                selections.put(player.getUUID(), new Selection(target.id(), server.getTickCount()));
                player.sendOverlayMessage(Component.literal("First device selected. Click another to link, or sneak-click to unlink."));
            } else if (selection.endpoint().equals(target.id())) {
                player.sendOverlayMessage(Component.literal("Selection cleared."));
            } else {
                NetworkPermission.require(runtime.directory(), runtime.directory().node(selection.endpoint()), actor, false);
                boolean unlink = player.isShiftKeyDown();
                runtime.link(selection.endpoint(), target.id(), unlink);
                player.sendOverlayMessage(Component.literal(unlink ? "Link removed." : "Devices linked."));
            }
            return InteractionResult.SUCCESS;
        } catch (IllegalArgumentException error) {
            player.sendOverlayMessage(Component.literal(error.getMessage()));
            return InteractionResult.FAIL;
        }
    }

    static void expire(MinecraftServer server) {
        var selections = SELECTIONS.get(server);
        if (selections != null) {
            selections.entrySet().removeIf(entry -> server.getTickCount() - entry.getValue().selectedAt() >= SELECTION_TICKS
                    || server.getPlayerList().getPlayer(entry.getKey()) == null);
        }
    }

    static void clear(MinecraftServer server) {
        SELECTIONS.remove(server);
    }
}
