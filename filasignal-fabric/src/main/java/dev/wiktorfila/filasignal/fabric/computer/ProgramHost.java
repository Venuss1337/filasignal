package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * A block that can own one FilaScript execution instance on the server's shared scheduler: the Programmable
 * Computer, or a hardware device with an installed drive. Everything execution-specific about a block lives behind
 * this interface; the scheduler, lifecycle, persistence checkpoints, console, editor and debugger stay shared.
 * Server-thread only.
 */
public interface ProgramHost {
    /** The program state a block can show, such as the computer's screen. */
    enum Indicator { OFF, RUNNING, ERROR }

    /** The block entity that owns the program. */
    BlockEntity block();

    /**
     * Shows the program state on the block. The scheduler calls it once per tick after a change, on the server
     * thread, only while the block is loaded, and never during chunk loading or unloading.
     */
    default void indicate(Indicator indicator) { }

    /** Scheduler identity, unique on the server, such as a computer UUID or {@code device:<uuid>}. */
    String executionId();

    /** Changes whenever the program, drive or enabled state changes outside the runtime, so it reactivates. */
    long revision();

    /** Changes whenever the installed program changes. Editor submissions must name the current value. */
    UUID editRevision();

    /** True when activation could succeed now. A device without an authorized drive is passive and returns false. */
    boolean runnable();

    /**
     * The installed program. {@code unavailable} explains why there is none to run, such as a missing drive.
     *
     * @throws IOException when the stored program record cannot be read
     */
    Program program() throws IOException;

    /** Replaces the program source. Existing globals are kept unless {@code resetGlobals}. */
    void install(String source, boolean resetGlobals) throws IOException;

    /**
     * Records whether the program should run, including after chunk reloads and restarts. {@code authority} is the
     * player whose access the program uses while it runs, when known; a device stops the program if that player later
     * loses access.
     */
    void enable(boolean enabled, String authorizedBy, java.util.UUID authority) throws IOException;

    /** Saves a complete globals snapshot taken from an idle runtime. */
    void publishSnapshot(byte[] snapshot) throws IOException;

    /** Host functions available to this program. Must not load chunks. */
    HostRegistry hosts(ServerLevel level);

    /** Capabilities granted to this program. */
    Set<String> capabilities();

    /** Called after activation, for example to reset event baselines. */
    default void activated(MinecraftServer server) { }

    /** Called after an explicit stop or restart, for example to clear outputs the program drove. */
    default void stopped(MinecraftServer server) { }

    /** A short name for consoles and logs, such as "computer" or "entity_detector". */
    String displayName();

    record Program(String source, byte[] snapshot, boolean enabled, String unavailable) {
        public Program {
            snapshot = snapshot == null ? null : snapshot.clone();
        }

        @Override
        public byte[] snapshot() {
            return snapshot == null ? null : snapshot.clone();
        }

        public static Program none(String reason) {
            return new Program("", null, false, reason);
        }
    }
}
