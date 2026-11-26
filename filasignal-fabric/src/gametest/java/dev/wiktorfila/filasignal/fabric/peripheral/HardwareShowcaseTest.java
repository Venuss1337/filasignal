package dev.wiktorfila.filasignal.fabric.peripheral;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Renders the hardware in a real client and saves screenshots for review and the documentation: every block with its
 * visual states, the Display Module's world text, and a device form. It also checks that the display text reaches
 * the client and that the visual states are set.
 */
public final class HardwareShowcaseTest implements FabricClientGameTest {
    private static final List<String> FRONT = List.of("programmable_computer", "network_repeater", "entity_detector",
            "alarm_module", "display_module", "weather_station", "environment_sensor", "redstone_controller");
    private static final List<String> BACK = List.of("inventory_module", "control_panel", "access_controller",
            "storage_server", "wireless_bridge", "network_interface", "sensor");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            UUID playerId = context.computeOnClient(client -> client.player.getUUID());
            BlockPos origin = context.computeOnClient(client -> BlockPos.containing(client.player.position())).offset(-7, 0, -8);
            singleplayer.getServer().runOnServer(server -> {
                command(server, "time set noon");
                command(server, "weather clear");
                for (int i = 0; i < FRONT.size(); i++) {
                    place(server, origin.offset(i * 2, 0, 0), FRONT.get(i));
                }
                for (int i = 0; i < BACK.size(); i++) {
                    place(server, origin.offset(1 + i * 2, 0, -3), BACK.get(i));
                }
            });
            context.waitTicks(10);
            BlockPos display = origin.offset(8, 0, 0);
            BlockPos alarm = origin.offset(6, 0, 0);
            BlockPos access = origin.offset(5, 0, -3);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel level = server.overworld();
                var runtime = PeripheralLifecycle.runtime(server);
                ((PeripheralEntity) level.getBlockEntity(display)).display(List.of("FilaSignal", "Hello!"));
                runtime.alarms().activate((PeripheralEntity) level.getBlockEntity(alarm), level, server.getTickCount());
                runtime.pulses().open((PeripheralEntity) level.getBlockEntity(access), level, level.getGameTime() + 2400);
            });
            context.waitFor(client -> client.level.getBlockEntity(display) instanceof PeripheralEntity entity
                    && entity.displayLines().equals(List.of("FilaSignal", "Hello!")));
            context.waitFor(client -> client.level.getBlockState(origin).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING) == Direction.SOUTH
                    && client.level.getBlockState(origin).toString().contains("status=running"));

            // Camera positions are the player's feet; the eyes are 1.62 blocks higher.
            look(singleplayer, context, playerId, origin.getX() + 7.5, origin.getY() + 1.5, origin.getZ() + 9.5, 180f, 18f);
            screenshot(context, "filasignal-hardware-family");
            look(singleplayer, context, playerId, origin.getX() + 0.5, origin.getY(), origin.getZ() + 2.6, 180f, 32f);
            screenshot(context, "filasignal-computer");
            look(singleplayer, context, playerId, origin.getX() + 7.5, origin.getY(), origin.getZ() + 3.2, 180f, 26f);
            screenshot(context, "filasignal-display-alarm");
            look(singleplayer, context, playerId, origin.getX() + 6.0, origin.getY(), origin.getZ() - 0.7, 180f, 35f);
            screenshot(context, "filasignal-back-row");
            look(singleplayer, context, playerId, origin.getX() + 4.5, origin.getY(), origin.getZ() + 2.5, 180f, 30f);

            // A real device form, with its help button.
            BlockPos detector = origin.offset(4, 0, 0);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                // Blocks made with /setblock have no owner. Register the player as the detector's owner, as placing would.
                var level = server.overworld();
                level.getBlockState(detector).getBlock().setPlacedBy(level, detector, level.getBlockState(detector), player,
                        ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var hit = new BlockHitResult(Vec3.atCenterOf(detector), Direction.SOUTH, detector, false);
                player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            });
            context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.DeviceScreen);
            context.waitTicks(10);
            context.takeScreenshot("filasignal-detector-form");
            context.runOnClient(client -> client.gui.setScreen(null));
        }
    }

    private static void place(MinecraftServer server, BlockPos pos, String id) {
        String state = switch (id) {
            case "programmable_computer", "entity_detector", "display_module", "weather_station", "control_panel",
                 "access_controller", "storage_server", "wireless_bridge" -> "[facing=south]";
            default -> "";
        };
        command(server, "setblock %d %d %d filasignal:%s%s".formatted(pos.getX(), pos.getY(), pos.getZ(), id, state));
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Moves the player to a camera position and waits for the view to settle. */
    private static void look(TestSingleplayerContext singleplayer, ClientGameTestContext context, UUID playerId,
                             double x, double y, double z, float yaw, float pitch) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.setNoGravity(true);
            player.teleportTo(server.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, true);
        });
        context.waitTicks(20);
        singleplayer.getConnection().waitForChunksRender();
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        var path = context.takeScreenshot(name);
        org.slf4j.LoggerFactory.getLogger("filasignal/showcase").info("Screenshot {}", path.toAbsolutePath());
    }
}
