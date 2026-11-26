package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Describes a chunk's lifecycle state for failure messages in tests that wait for chunks to unload or load. */
final class ChunkDiagnostics {
    private ChunkDiagnostics() {}

    static String describe(ServerLevel level, int x, int z) {
        var source = level.getChunkSource();
        return "chunk " + x + "," + z + " at tick " + level.getServer().getTickCount()
                + ": full=" + (source.getChunkNow(x, z) != null)
                + ", forced=" + level.getForceLoadedChunks().contains(ChunkPos.pack(x, z))
                + ", pendingTasks=" + source.getPendingTasksCount()
                + ", debug=[" + source.getChunkDebugData(new ChunkPos(x, z)) + "]";
    }
}
