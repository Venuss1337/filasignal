package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.runtime.debug.Debugger;
import dev.wiktorfila.filasignal.core.runtime.event.Simulation;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Debugger sessions for loaded programs, whether a computer's or a device's. Server-thread only.
 *
 * <p>A session exists while at least one authorized player has the debug panel open. Each viewer is a monitor
 * that remembers the editor target it attached through: the computer itself, a device's drive, or a remote
 * device reached through a computer. Monitors are rechecked through that same target every 20 ticks and dropped
 * when their player disconnects, changes level, moves out of range or loses access. The session's
 * {@link Debugger} follows the program across Stop and Restart. When the last monitor leaves, breakpoints and
 * pause requests are cleared and a paused handler continues before the debugger detaches. Session and thread
 * limits are server-wide.
 */
final class DebugSessions {
    static final int MAX_SESSIONS = 8;
    static final int MAX_MONITORS = 4;
    private static final int AUTHORIZE_INTERVAL = 20;
    private static final int REFRESH_INTERVAL = 40;
    private static final int PUSH_INTERVAL = 2;

    static final class Session {
        final Debugger debugger = new Debugger();
        private final Map<UUID, Monitor> monitors = new LinkedHashMap<>();
        private Debugger.Pause announced;
    }

    private static final class Monitor {
        private final BlockPos pos;
        private final String target;
        private long authorized;
        private long sent = Long.MIN_VALUE / 2;
        private EditorNetwork.DebugState last;

        private Monitor(BlockPos pos, String target, long tick) {
            this.pos = pos;
            this.target = target;
            authorized = tick;
        }
    }

    private final ServerComputers owner;
    private final Map<ProgramHost, Session> sessions = new LinkedHashMap<>();

    DebugSessions(ServerComputers owner) {
        this.owner = owner;
    }

    Session session(ProgramHost host) {
        return sessions.get(host);
    }

    /** Adds an already authorized player as a monitor, creating and attaching the session when needed. */
    Session attach(ProgramHost host, ServerPlayer player, BlockPos pos, String target, long tick) {
        Session session = sessions.get(host);
        if (session == null) {
            if (sessions.size() >= MAX_SESSIONS) {
                throw new IllegalArgumentException("Too many programs are being debugged on this server");
            }
            session = new Session();
            Simulation.Controller runtime = owner.runtime(host);
            if (runtime != null) {
                runtime.debug(session.debugger);
            }
            sessions.put(host, session);
            owner.status(host).lifecycle("Debugger attached");
        }
        Monitor existing = session.monitors.get(player.getUUID());
        if (existing == null && session.monitors.size() >= MAX_MONITORS) {
            throw new IllegalArgumentException("Too many players are debugging this program");
        }
        if (existing == null || !existing.pos.equals(pos) || !existing.target.equals(target)) {
            session.monitors.put(player.getUUID(), new Monitor(pos, target, tick));
        }
        return session;
    }

    /** Returns the session only when the player is one of its monitors. */
    Session monitored(ProgramHost host, UUID player) {
        Session session = sessions.get(host);
        if (session == null || !session.monitors.containsKey(player)) {
            throw new IllegalArgumentException("Open the debug panel first");
        }
        return session;
    }

    /** Removes the player's monitor opened at a position, without loading chunks or checking access. */
    void detach(ServerPlayer player, BlockPos pos) {
        for (Session session : sessions.values()) {
            Monitor monitor = session.monitors.get(player.getUUID());
            if (monitor != null && monitor.pos.equals(pos)) {
                session.monitors.remove(player.getUUID());
            }
        }
    }

    /** Attaches an existing session's debugger to a newly activated execution instance. */
    void activated(ProgramHost host, Simulation.Controller runtime) {
        Session session = sessions.get(host);
        if (session != null) {
            runtime.debug(session.debugger);
        }
    }

    /** Ends sessions for an unloaded or removed program. Its runtime has already been detached. */
    void forget(MinecraftServer server, ProgramHost host) {
        Session session = sessions.remove(host);
        if (session != null) {
            for (var monitor : session.monitors.entrySet()) {
                notifyDetached(server, monitor.getValue().pos, monitor.getKey(), "Debugger closed: the program is unavailable");
            }
        }
    }

    /** Detaches every debugger before server shutdown so shutdown handlers cannot pause. */
    void closeAll() {
        for (var entry : sessions.entrySet()) {
            entry.getValue().debugger.reset();
            Simulation.Controller runtime = owner.runtime(entry.getKey());
            if (runtime != null) {
                runtime.debug(null);
            }
        }
        sessions.clear();
    }

    /** Validates monitors, retires idle sessions and pushes changed views. Runs after scheduler dispatch. */
    void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ProgramHost host = entry.getKey();
            Session session = entry.getValue();
            announcePause(host, session);
            session.monitors.entrySet().removeIf(monitor -> !valid(server, host, monitor.getKey(), monitor.getValue(), tick));
            if (session.monitors.isEmpty()) {
                session.debugger.reset();
                if (!session.debugger.suspended()) {
                    Simulation.Controller runtime = owner.runtime(host);
                    if (runtime != null) {
                        runtime.debug(null);
                    }
                    owner.status(host).lifecycle("Debugger detached");
                    iterator.remove();
                }
                continue;
            }
            push(server, host, session, tick);
        }
    }

    private void announcePause(ProgramHost host, Session session) {
        var pause = session.debugger.pause().orElse(null);
        if (pause != null && pause != session.announced) {
            owner.status(host).lifecycle("Paused at line " + pause.line() + " ("
                    + pause.reason().name().toLowerCase(java.util.Locale.ROOT) + ")");
        }
        session.announced = pause;
    }

    private boolean valid(MinecraftServer server, ProgramHost host, UUID id, Monitor monitor, long tick) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null) {
            return false;
        }
        String problem = null;
        if (player.level() != host.block().getLevel() || player.isSpectator()
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(monitor.pos)) > 64) {
            problem = "Debugger closed: approach the block to keep debugging";
        } else if (tick - monitor.authorized >= AUTHORIZE_INTERVAL) {
            try {
                if (ProgramEditing.host(player, monitor.pos, monitor.target) != host) {
                    problem = "Debugger closed: the program changed";
                }
                monitor.authorized = tick;
            } catch (IllegalArgumentException denied) {
                problem = "Debugger closed: " + denied.getMessage();
            }
        }
        if (problem != null) {
            notifyDetached(server, monitor.pos, id, problem);
            return false;
        }
        return true;
    }

    private void push(MinecraftServer server, ProgramHost host, Session session, long tick) {
        for (var entry : session.monitors.entrySet()) {
            Monitor monitor = entry.getValue();
            if (tick - monitor.sent < PUSH_INTERVAL) {
                continue;
            }
            var view = owner.debugView(host, monitor.pos, EditorNetwork.EMPTY_REQUEST, true, "");
            boolean changed = monitor.last == null || !sameExceptStatistics(monitor.last, view);
            if (changed || tick - monitor.sent >= REFRESH_INTERVAL) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null && ServerPlayNetworking.canSend(player, EditorNetwork.DebugState.TYPE)) {
                    ServerPlayNetworking.send(player, view);
                }
                monitor.last = view;
                monitor.sent = tick;
            }
        }
    }

    private static boolean sameExceptStatistics(EditorNetwork.DebugState a, EditorNetwork.DebugState b) {
        return a.status().state() == b.status().state() && a.status().headline().equals(b.status().headline())
                && a.status().fault().equals(b.status().fault()) && a.status().faultLine() == b.status().faultLine()
                && a.pause().equals(b.pause()) && a.breakpoints().equals(b.breakpoints())
                && a.console().equals(b.console());
    }

    private static void notifyDetached(MinecraftServer server, BlockPos pos, UUID id, String message) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null && ServerPlayNetworking.canSend(player, EditorNetwork.DebugState.TYPE)) {
            ServerPlayNetworking.send(player, EditorNetwork.DebugState.detached(pos, message));
        }
    }
}
