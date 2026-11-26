package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** The Access Controller. Its status light shows {@code open} while it powers its outputs. */
final class AccessControllerBlock extends OrientedBlock {
    static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    AccessControllerBlock(Properties properties) {
        super(properties, Kind.ACCESS_CONTROLLER);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    /** Shows whether the controller is open. Only a loaded block changes, and neighbors are not notified. */
    static void show(PeripheralEntity controller, ServerLevel level, boolean open) {
        BlockState state = controller.getBlockState();
        if (state.hasProperty(OPEN) && state.getValue(OPEN) != open && !controller.isRemoved()
                && LoadedChunks.contains(level, controller.getBlockPos())) {
            level.setBlock(controller.getBlockPos(), state.setValue(OPEN, open), Block.UPDATE_CLIENTS);
        }
    }
}
