package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Loaded-only queries used before accessing a device or its neighboring redstone. */
public final class LoadedChunks {
    private LoadedChunks() {}

    /** Server-thread check for a fully loaded chunk without requesting a ticket. */
    public static boolean contains(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    static boolean signalArea(ServerLevel level, BlockPos pos) {
        // A conductor can query its neighbors, and notifications can reach beyond the face.
        return contains(level, pos.offset(-2, 0, -2)) && contains(level, pos.offset(-2, 0, 2))
                && contains(level, pos.offset(2, 0, -2)) && contains(level, pos.offset(2, 0, 2));
    }
}
