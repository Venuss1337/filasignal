package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.runtime.event.Simulation;
import net.minecraft.server.MinecraftServer;

/**
 * Receives program lifecycle changes so the hardware layer can route native events to handlers and release them when
 * a program stops. Calls arrive on the server thread, outside handler dispatch.
 */
public interface ProgramObserver {
    /** A program's execution instance became active with its activated subscriptions. */
    void attached(MinecraftServer server, ProgramHost host, Simulation.Controller controller);

    /** A program's execution instance was removed for any reason: stop, replacement, unload or shutdown. */
    void detached(MinecraftServer server, ProgramHost host);

    /** A handler stopped on a runtime fault. The program keeps running. */
    void failed(MinecraftServer server, ProgramHost host, String message);

    /** Called once per server tick after dispatch, for periodic checks. */
    void ticked(MinecraftServer server);

    /** A short note about a running program's event handlers, such as a device name that does not resolve, or "". */
    default String note(MinecraftServer server, ProgramHost host) {
        return "";
    }

    ProgramObserver NONE = new ProgramObserver() {
        @Override public void attached(MinecraftServer server, ProgramHost host, Simulation.Controller controller) { }
        @Override public void detached(MinecraftServer server, ProgramHost host) { }
        @Override public void failed(MinecraftServer server, ProgramHost host, String message) { }
        @Override public void ticked(MinecraftServer server) { }
    };
}
