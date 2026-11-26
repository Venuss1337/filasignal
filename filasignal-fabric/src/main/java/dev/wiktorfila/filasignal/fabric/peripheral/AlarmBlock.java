package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Alarm module. The {@code lit} state shows whether the alarm is sounding and makes the block glow. */
final class AlarmBlock extends PeripheralBlock {
    AlarmBlock(Properties properties) {
        super(properties, Kind.ALARM);
        registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.LIT);
    }
}
