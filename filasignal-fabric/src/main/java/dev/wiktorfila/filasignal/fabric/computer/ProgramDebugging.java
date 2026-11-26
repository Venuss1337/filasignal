package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.runtime.debug.Debugger;
import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.DebugState;
import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.Request;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-thread boundary for debugger requests. Every request repeats the editor's proximity and ACL checks, so
 * debugging is available exactly to players who may edit the program. Requests never change the installed program.
 */
final class ProgramDebugging {
    private ProgramDebugging() {
    }

    static DebugState respond(ServerPlayer player, Request packet) {
        try {
            var host = ProgramEditing.host(player, packet.pos(), packet.target());
            String message = ComputerLifecycle.debug(player, host, packet.pos(), packet.target(), packet.action(), packet.source());
            return ComputerLifecycle.debugView(player.level(), host, packet.pos(), packet.requestId(), message);
        } catch (IllegalArgumentException error) {
            String message = error.getMessage() == null ? "Debugger request failed" : error.getMessage();
            return new DebugState(packet.pos(), packet.requestId(), false, EditorNetwork.shorten(message, 500),
                    new EditorNetwork.Status(DebugState.STOPPED, "", "", 0, 0, List.of(), ""),
                    EditorNetwork.Pause.NONE, List.of(), List.of());
        }
    }

    /** Parses comma-separated breakpoint lines. Duplicates collapse. */
    static List<Integer> breakpoints(String text) {
        var lines = new ArrayList<Integer>();
        if (text.isBlank()) {
            return lines;
        }
        String[] parts = text.split(",", Debugger.MAX_BREAKPOINTS + 2);
        if (parts.length > Debugger.MAX_BREAKPOINTS) {
            throw new IllegalArgumentException("At most " + Debugger.MAX_BREAKPOINTS + " breakpoints are supported");
        }
        for (String part : parts) {
            String digits = part.strip();
            if (digits.isEmpty() || digits.length() > 7 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("Breakpoints must be line numbers");
            }
            lines.add(Integer.parseInt(digits));
        }
        return lines;
    }
}
