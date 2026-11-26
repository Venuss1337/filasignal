package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Shared block for every peripheral kind. Behavior that depends on hardware is selected by kind. */
class PeripheralBlock extends BaseEntityBlock {
    private final Kind kind;

    PeripheralBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
    }

    Kind kind() { return kind; }

    /** Interfaces and redstone controllers read and drive redstone on configured faces. */
    private boolean redstone() {
        return kind == Kind.INTERFACE || kind == Kind.REDSTONE_CONTROLLER;
    }

    /** Devices that emit redstone: the above and Access Controllers while open. */
    private boolean emitter() {
        return redstone() || kind == Kind.ACCESS_CONTROLLER;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel server && placer instanceof net.minecraft.server.level.ServerPlayer player
                && level.getBlockEntity(pos) instanceof PeripheralEntity entity) {
            try {
                PeripheralLifecycle.runtime(server.getServer()).placed(server, entity, entity.identity(), kind, player.getUUID());
            } catch (IllegalArgumentException error) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(error.getMessage()));
            }
        }
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level,
            BlockPos position, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty()) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // Using an Access Controller asks to be let in; sneaking opens its settings instead.
        if (kind == Kind.ACCESS_CONTROLLER && !player.isShiftKeyDown()) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && level.getBlockEntity(position) instanceof PeripheralEntity entity) {
                AdvancedApis.attempt(PeripheralLifecycle.runtime(serverPlayer.level().getServer()), serverPlayer, entity);
            }
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown() && kind != Kind.ACCESS_CONTROLLER) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            DeviceConfigNetwork.open(serverPlayer, position);
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    /** Using a data drive on a programmable device installs it. Installing never starts the program. */
    @Override
    protected net.minecraft.world.InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
            Level level, BlockPos position, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!dev.wiktorfila.filasignal.fabric.drive.Drives.isDrive(stack)) {
            return net.minecraft.world.InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            try {
                PeripheralLifecycle.programmableDevice(serverPlayer, position).insertDrive(stack);
                stack.shrink(1);
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                        "Drive installed. Open the device to start its program."));
            } catch (IllegalArgumentException error) {
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal(error.getMessage()));
                return net.minecraft.world.InteractionResult.FAIL;
            }
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new PeripheralEntity(position, state, kind);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos position, boolean moved) {
        PeripheralLifecycle.removed(level, position);
        super.affectNeighborsAfterRemoval(state, level, position, moved);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block neighbor,
                                   net.minecraft.world.level.redstone.Orientation orientation, boolean moved) {
        super.neighborChanged(state, level, pos, neighbor, orientation, moved);
        if (redstone() && level instanceof ServerLevel server) {
            PeripheralLifecycle.inputChanged(server, pos);
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return emitter();
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos position, Direction towardSource) {
        if (emitter() && level instanceof ServerLevel server
                && LoadedChunks.contains(server, position) && level.getBlockEntity(position) instanceof PeripheralEntity entity) {
            return entity.output(towardSource.getOpposite());
        }
        return 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos position, Direction towardSource) {
        return getSignal(state, level, position, towardSource);
    }
}
