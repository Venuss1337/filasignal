package dev.wiktorfila.filasignal.fabric.computer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * The Programmable Computer: a desktop computer model that faces the player who placed it. Its screen shows whether
 * the program runs, stopped or failed, and its slot shows an inserted drive. Both states are only visual.
 */
final class ComputerBlock extends BaseEntityBlock {
    static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    static final EnumProperty<Screen> STATUS = EnumProperty.create("status", Screen.class);
    static final BooleanProperty DRIVE = BooleanProperty.create("drive");

    /** Keyboard, desktop case and monitor of the north-facing model, rotated for each facing. */
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Shapes.or(
            Block.box(2, 0, 1, 14, 1, 5), Block.box(1, 0, 5, 15, 4, 16), Block.box(2, 4, 6, 14, 15, 14),
            Block.box(4, 6, 14, 12, 13, 16)));

    /** The screen picture. */
    enum Screen implements StringRepresentable {
        OFF, RUNNING, ERROR;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    ComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STATUS, Screen.OFF)
                .setValue(DRIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATUS, DRIVE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** Updates the screen and drive slot pictures without notifying neighbors. Only loaded, present blocks change. */
    static void show(ComputerBlockEntity computer, Screen screen, boolean drive) {
        if (!(computer.getLevel() instanceof ServerLevel level) || computer.isRemoved()
                || !level.isLoaded(computer.getBlockPos())) {
            return;
        }
        BlockState state = computer.getBlockState();
        if (state.hasProperty(STATUS)) {
            BlockState next = state.setValue(STATUS, screen == null ? state.getValue(STATUS) : screen).setValue(DRIVE, drive);
            if (next != state) {
                level.setBlock(computer.getBlockPos(), next, Block.UPDATE_CLIENTS);
            }
        }
    }

    @Override
    public void setPlacedBy(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof net.minecraft.server.level.ServerLevel server
                && placer instanceof net.minecraft.server.level.ServerPlayer player
                && level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            try {
                dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle.placedComputer(
                        server, computer, computer.activateData().identity(), player.getUUID());
            } catch (java.io.IOException | IllegalArgumentException error) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(error.getMessage()));
            }
        }
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
            BlockPos position, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        var held = player.getMainHandItem();
        if (player.isShiftKeyDown() || (!held.isEmpty()
                && held.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT) == null)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            EditorNetwork.open(serverPlayer, position);
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    /** Using a data drive on the computer puts it in the drive slot for programming. */
    @Override
    protected net.minecraft.world.InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
            net.minecraft.world.level.Level level, BlockPos position, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (!dev.wiktorfila.filasignal.fabric.drive.Drives.isDrive(stack)) {
            return net.minecraft.world.InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            try {
                var computer = ProgramEditing.computer(serverPlayer, position);
                var tag = dev.wiktorfila.filasignal.fabric.drive.Drives.tag(stack);
                if (tag != null && !dev.wiktorfila.filasignal.fabric.drive.DriveStore.of(serverPlayer.level().getServer())
                        .mount(tag.id(), computer)) {
                    throw new IllegalArgumentException("That drive is already installed in another block");
                }
                computer.insertDrive(stack);
                stack.shrink(1);
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                        "Drive inserted. Open the computer to label and program it."));
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
    protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel level,
                                               BlockPos position, boolean moved) {
        dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle.removed(level, position);
        super.affectNeighborsAfterRemoval(state, level, position, moved);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new ComputerBlockEntity(position, state);
    }
}
