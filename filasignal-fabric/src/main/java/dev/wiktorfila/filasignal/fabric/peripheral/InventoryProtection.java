package dev.wiktorfila.filasignal.fabric.peripheral;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** Lets protection mods deny automation for the owning player, including when that player is offline. */
@FunctionalInterface
public interface InventoryProtection {
    Event<InventoryProtection> CHECK = EventFactory.createArrayBacked(InventoryProtection.class, listeners ->
            (level, position, owner, write) -> {
                for (InventoryProtection listener : listeners) {
                    if (!listener.allows(level, position, owner, write)) {
                        return false;
                    }
                }
                return true;
            });

    /**
     * Called on the server thread for a loaded inventory. Return false to veto discovery or transfer.
     * Implementations must avoid loading chunks and should use their server-side claim data.
     */
    boolean allows(ServerLevel level, BlockPos position, UUID owner, boolean write);

    static boolean check(ServerLevel level, BlockPos position, UUID owner, boolean write) {
        if (!level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        if (level.getServer() instanceof DedicatedServer dedicated) {
            var spawn = level.getRespawnData();
            int radius = dedicated.spawnProtectionRadius();
            if (radius > 0 && !dedicated.getPlayerList().getOps().isEmpty() && level.dimension() == spawn.dimension()
                    && Math.max(Math.abs(position.getX() - spawn.pos().getX()),
                                Math.abs(position.getZ() - spawn.pos().getZ())) <= radius) {
                return false;
            }
        }
        return CHECK.invoker().allows(level, position, owner, write);
    }
}
