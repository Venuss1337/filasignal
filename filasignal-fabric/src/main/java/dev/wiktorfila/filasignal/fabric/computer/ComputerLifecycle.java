package dev.wiktorfila.filasignal.fabric.computer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.fabric.peripheral.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Fabric entry points for programs on both dedicated and integrated servers. */
public final class ComputerLifecycle {
    // Fabric's lifecycle callbacks own each server entry. Access from unrelated server threads is synchronized.
    private static final Map<MinecraftServer, ServerComputers> SERVERS =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    private static volatile ProgramObserver observer = ProgramObserver.NONE;

    private ComputerLifecycle() {
    }

    /** Installs the hardware layer's lifecycle observer during mod initialization. */
    public static void observe(ProgramObserver next) {
        observer = java.util.Objects.requireNonNull(next);
    }

    static ProgramObserver observer() {
        return observer;
    }

    /** The running program with this execution identity, or null. Server thread only. */
    public static ProgramHost running(MinecraftServer server, String executionId) {
        ServerComputers runtime = SERVERS.get(server);
        return runtime == null ? null : runtime.host(executionId);
    }

    /** Every running program on the server. Server thread only. */
    public static List<ProgramHost> running(MinecraftServer server) {
        ServerComputers runtime = SERVERS.get(server);
        return runtime == null ? List.of() : runtime.hosts();
    }

    /** Registers common-side hooks once during mod initialization. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> SERVERS.put(server, new ServerComputers(server)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerComputers runtime = SERVERS.get(server);
            if (runtime != null) {
                runtime.stop();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(SERVERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle.attachPending(server);
            ServerComputers runtime = SERVERS.get(server);
            if (runtime != null) {
                runtime.tick();
            }
        });
        ServerLevelEvents.UNLOAD.register((server, level) -> {
            ServerComputers runtime = SERVERS.get(server);
            if (runtime != null) {
                runtime.unloadLevel(level);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ProgramHost host) {
                schedule(level.getServer(), host);
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof ProgramHost host) {
                ServerComputers runtime = SERVERS.get(level.getServer());
                if (runtime != null) {
                    runtime.unload(host);
                }
            }
            if (entity instanceof ComputerBlockEntity computer) {
                computer.releaseDrive();
            }
        });
    }

    private static ServerComputers runtime(MinecraftServer server) {
        ServerComputers runtime = SERVERS.get(server);
        if (runtime == null) {
            throw new IllegalArgumentException("The program runtime is unavailable");
        }
        return runtime;
    }

    /** Queues a program for activation when it is enabled. */
    public static void schedule(MinecraftServer server, ProgramHost host) {
        ServerComputers runtime = SERVERS.get(server);
        if (runtime != null) {
            runtime.enqueue(host);
        }
    }

    /** Saves a running program's globals now, if it has unsaved changes and is not paused. */
    public static void checkpoint(MinecraftServer server, ProgramHost host) {
        ServerComputers runtime = SERVERS.get(server);
        if (runtime != null) {
            runtime.checkpoint(host);
        }
    }

    /** Stops a program without recording a stop, saving its globals and ending debugging, for example before its drive leaves. */
    public static void release(MinecraftServer server, ProgramHost host, String reason) {
        ServerComputers runtime = SERVERS.get(server);
        if (runtime != null) {
            runtime.release(host, reason);
        }
    }

    /**
     * Queues a start or stop requested by a running program. It applies before the next dispatch, never inside one.
     *
     * @return false when too many changes are already waiting
     */
    public static boolean requestControl(MinecraftServer server, ProgramHost host, boolean enabled, String authorizedBy) {
        return requestControl(server, host, enabled, authorizedBy, null, null);
    }

    /** As {@link #requestControl(MinecraftServer, ProgramHost, boolean, String)}, recording an authority and reason. */
    public static boolean requestControl(MinecraftServer server, ProgramHost host, boolean enabled, String authorizedBy,
                                         UUID authority, String reason) {
        ServerComputers runtime = runtime(server);
        return runtime.request(() -> {
            if (host.block().isRemoved()) {
                return;
            }
            try {
                runtime.control(host, enabled, authorizedBy, authority, reason);
            } catch (IOException error) {
                throw new IllegalArgumentException(error.getMessage(), error);
            }
        });
    }

    /** Enqueues a typed host event for a running computer on the server thread. */
    public static boolean emit(MinecraftServer server, UUID id, String name, List<Value> arguments, Value payload) {
        return emit(server, id.toString(), name, arguments, payload);
    }

    /** Enqueues a typed host event for a running program by its execution identity. */
    public static boolean emit(MinecraftServer server, String executionId, String name, List<Value> arguments, Value payload) {
        ServerComputers runtime = SERVERS.get(server);
        return runtime != null && runtime.emit(executionId, name, arguments, payload);
    }

    /** Applies a validated source change from a server operator. The caller must authorize the command. */
    public static void program(ServerLevel level, BlockPos pos, String source, boolean reset) throws IOException {
        if (!LoadedChunks.contains(level, pos) || !(level.getBlockEntity(pos) instanceof ComputerBlockEntity block)) {
            throw new IllegalArgumentException("Select a loaded Programmable Computer");
        }
        runtime(level.getServer()).program(block, source, reset, true);
    }

    /** Installs a program on any host. The caller must authorize the player. */
    public static void program(ServerLevel level, ProgramHost host, String source, boolean reset, boolean apply) throws IOException {
        runtime(level.getServer()).program(host, source, reset, apply);
    }

    /** Starts or stops a program now. The caller must have authorized the actor and must not be inside dispatch. */
    public static void control(ServerLevel level, ProgramHost host, boolean enabled, String authorizedBy) throws IOException {
        runtime(level.getServer()).control(host, enabled, authorizedBy);
    }

    /** Starts or stops a program on behalf of a known player, whose access a device program then uses. */
    public static void control(ServerLevel level, ProgramHost host, boolean enabled, String authorizedBy, UUID authority)
            throws IOException {
        runtime(level.getServer()).control(host, enabled, authorizedBy, authority, null);
    }

    /** Checks a program against a registry without a host, for drives being written in a computer slot. */
    static void validate(String name, String source, dev.wiktorfila.filasignal.core.host.HostRegistry hosts,
                         java.util.Set<String> capabilities, byte[] snapshot) throws IOException {
        ServerComputers.validate(name, source, hosts, capabilities, snapshot);
    }

    static String budgetSummary(ServerLevel level) {
        var runtime = SERVERS.get(level.getServer());
        return runtime == null ? "Scheduler unavailable" : runtime.budgetSummary();
    }

    static String lastLog(ServerLevel level, ProgramHost host) {
        var runtime = SERVERS.get(level.getServer());
        return runtime == null ? "" : runtime.lastLog(host);
    }

    public static String lastFault(ServerLevel level, ProgramHost host) {
        var runtime = SERVERS.get(level.getServer());
        return runtime == null ? "" : runtime.lastFault(host);
    }

    /**
     * Scheduler figures for the most recent tick: running programs, programs waiting to start, queued events, operations
     * used against the tick budget, the scheduler's own time, and totals of handlers, faults and dropped events over the
     * running programs.
     */
    public record Metrics(int running, int activating, int queuedEvents, long operations, long budget, long tickNanos,
                          long handlers, long faults, long dropped) {
    }

    /** Current scheduler figures, or null when no runtime exists for the server. Server thread only. */
    public static Metrics metrics(MinecraftServer server) {
        ServerComputers runtime = SERVERS.get(server);
        return runtime == null ? null : runtime.metrics();
    }

    public static boolean active(MinecraftServer server, ProgramHost host) {
        ServerComputers runtime = SERVERS.get(server);
        return runtime != null && runtime.active(host);
    }

    /** Execution figures for a running program, or null when it is not running. */
    static dev.wiktorfila.filasignal.core.runtime.event.Simulation.Statistics statistics(MinecraftServer server,
                                                                                         ProgramHost host) {
        var runtime = SERVERS.get(server);
        var controller = runtime == null ? null : runtime.runtime(host);
        return controller == null ? null : controller.statistics();
    }

    /** One-line execution statistics for status displays. */
    public static String statistics(ServerLevel level, ProgramHost host) {
        var runtime = SERVERS.get(level.getServer());
        return runtime == null ? "" : runtime.statistics(host);
    }

    public static String stateLine(ServerLevel level, ProgramHost host) {
        var runtime = SERVERS.get(level.getServer());
        return runtime == null ? "Runtime unavailable" : runtime.state(host).headline();
    }

    /** The busiest running programs and the total count, for operator telemetry. */
    public static List<String> busiest(MinecraftServer server, int limit) {
        var runtime = SERVERS.get(server);
        if (runtime == null) {
            return List.of();
        }
        var lines = new java.util.ArrayList<String>();
        lines.add(runtime.running() + " programs running · " + runtime.budgetSummary());
        lines.addAll(runtime.busiest(limit));
        return lines;
    }

    /** The recent console lines of a program, oldest first. */
    public static List<String> console(ServerLevel level, ProgramHost host, int limit) {
        var runtime = SERVERS.get(level.getServer());
        if (runtime == null) {
            return List.of();
        }
        var entries = runtime.status(host).console.entries();
        return entries.subList(Math.max(0, entries.size() - limit), entries.size()).stream().map(entry -> entry.text()).toList();
    }

    /** Applies an authorized debugger action and returns a short confirmation. */
    static String debug(ServerPlayer player, ProgramHost host, BlockPos pos, String target, int action, String argument) {
        return runtime(player.level().getServer()).debug(player, host, pos, target, action, argument);
    }

    static EditorNetwork.DebugState debugView(ServerLevel level, ProgramHost host, BlockPos pos, UUID requestId,
                                              String message) {
        return runtime(level.getServer()).debugView(host, pos, requestId, true, message);
    }

    static void detachDebugger(ServerPlayer player, BlockPos pos) {
        var runtime = SERVERS.get(player.level().getServer());
        if (runtime != null) {
            runtime.detachDebugger(player, pos);
        }
    }
}
