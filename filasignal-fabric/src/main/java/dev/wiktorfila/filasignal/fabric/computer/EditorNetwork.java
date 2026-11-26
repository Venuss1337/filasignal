package dev.wiktorfila.filasignal.fabric.computer;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Versioned play payloads for deliberate editor actions. The server derives the actor and level from the connection. */
public final class EditorNetwork {
    static final UUID EMPTY_REQUEST = new UUID(0, 0);
    private static final UUID EMPTY = EMPTY_REQUEST;
    private static final Map<ServerPlayer, Long> LAST_REQUEST = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<net.minecraft.server.MinecraftServer, TickLimit> SERVERS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final class TickLimit {
        long tick = -1;
        int count;
    }

    private static final StreamCodec<ByteBuf, String> SOURCE = ByteBufCodecs.byteArray(ProgramEditing.MAX_SOURCE * 3)
            .map(EditorNetwork::decode, value -> value.getBytes(StandardCharsets.UTF_8));

    private EditorNetwork() {}

    /**
     * An editor action. {@code target} selects what is edited: {@code ""} for the computer program, {@code drive}
     * for the drive in the computer, {@code local} for the device at {@code pos}, or {@code remote:NAME} for a
     * device on the computer's network. The server validates the target like every other field.
     */
    public record Request(BlockPos pos, UUID revision, int action, UUID requestId, String source, String target)
            implements CustomPacketPayload {
        public Request(BlockPos pos, UUID revision, int action, UUID requestId, String source) {
            this(pos, revision, action, requestId, source, "");
        }

        public static final int RELOAD = 0;
        public static final int VALIDATE = 1;
        public static final int APPLY = 2;
        public static final int IMPORT_BOOK = 3;
        public static final int STOP = 4;
        public static final int START_OR_RESTART = 5;
        public static final int RENAME = 6;
        /** Debugger actions ignore the edit revision because they never change the installed program. */
        public static final int DEBUG_ATTACH = 7;
        public static final int DEBUG_DETACH = 8;
        public static final int DEBUG_PAUSE = 9;
        public static final int DEBUG_RESUME = 10;
        public static final int DEBUG_STEP = 11;
        /** The source field carries comma-separated breakpoint lines for the installed program. */
        public static final int DEBUG_BREAKPOINTS = 12;
        /** Saves the draft and discards saved globals. Owner or administrator only. */
        public static final int APPLY_RESET = 13;
        /** Opens the editor for the request target. */
        public static final int OPEN = 14;
        /** Computer drive slot actions. The source field carries the label or kind ID. */
        public static final int DRIVE_LABEL = 15;
        public static final int DRIVE_TARGET = 16;
        public static final int DRIVE_EJECT = 17;
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filasignal", "editor_request_v2"));
        public static final StreamCodec<ByteBuf, Request> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Request::pos, UUIDUtil.STREAM_CODEC, Request::revision,
                ByteBufCodecs.VAR_INT, Request::action, UUIDUtil.STREAM_CODEC, Request::requestId, SOURCE, Request::source,
                ByteBufCodecs.stringUtf8(48), Request::target, Request::new);

        @Override public Type<Request> type() { return TYPE; }
    }

    /**
     * Editor snapshot or acknowledgement. {@code state} is the server's one-line execution status. {@code target}
     * echoes the request target and {@code label} names it. {@code drive} summarizes a computer's drive slot as
     * tab-separated label, kind, program and used bytes, {@code blank} for a blank drive or empty for none.
     */
    public record Reply(BlockPos pos, UUID revision, UUID requestId, boolean opening, boolean success, boolean running,
                        String status, String source, String catalog, String dashboard, String state, String target,
                        String label, String drive)
            implements CustomPacketPayload {
        public Reply(BlockPos pos, UUID revision, UUID requestId, boolean opening, boolean success, boolean running,
                     String status, String source, String catalog, String dashboard, String state) {
            this(pos, revision, requestId, opening, success, running, status, source, catalog, dashboard, state, "", "", "");
        }

        public static final Type<Reply> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filasignal", "editor_reply_v4"));
        public static final StreamCodec<ByteBuf, Reply> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Reply::pos, UUIDUtil.STREAM_CODEC, Reply::revision,
                UUIDUtil.STREAM_CODEC, Reply::requestId, ByteBufCodecs.BOOL, Reply::opening, ByteBufCodecs.BOOL, Reply::success, ByteBufCodecs.BOOL, Reply::running,
                ByteBufCodecs.stringUtf8(512), Reply::status, SOURCE, Reply::source, ByteBufCodecs.stringUtf8(ProgramEditing.MAX_CATALOG), Reply::catalog,
                ByteBufCodecs.stringUtf8(4096), Reply::dashboard, ByteBufCodecs.stringUtf8(STATE), Reply::state,
                ByteBufCodecs.stringUtf8(48), Reply::target, ByteBufCodecs.stringUtf8(STATE), Reply::label,
                ByteBufCodecs.stringUtf8(160), Reply::drive, Reply::new);

        @Override public Type<Reply> type() { return TYPE; }
    }

    static final int STATE = 160;
    static final int MAX_FRAMES = 16;
    static final int MAX_VARIABLES = 64;
    static final int MAX_BREAKPOINTS = dev.wiktorfila.filasignal.core.runtime.debug.Debugger.MAX_BREAKPOINTS;
    static final int MAX_CONSOLE = dev.wiktorfila.filasignal.core.runtime.debug.ProgramConsole.CAPACITY;

    /** Debugger and runtime view of one computer. Every list and string is bounded by its codec. */
    public record DebugState(BlockPos pos, UUID requestId, boolean success, String message, Status status, Pause pause,
                             List<Integer> breakpoints, List<ConsoleLine> console) implements CustomPacketPayload {
        public static final int STOPPED = 0;
        public static final int STARTING = 1;
        public static final int RUNNING = 2;
        public static final int PAUSED = 3;
        public static final int REJECTED = 4;
        public static final int PAUSING = 5;
        public static final Type<DebugState> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filasignal", "editor_debug_v1"));
        public static final StreamCodec<ByteBuf, DebugState> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, DebugState::pos, UUIDUtil.STREAM_CODEC, DebugState::requestId,
                ByteBufCodecs.BOOL, DebugState::success, ByteBufCodecs.stringUtf8(512), DebugState::message,
                Status.CODEC, DebugState::status, Pause.CODEC, DebugState::pause,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_BREAKPOINTS)), DebugState::breakpoints,
                ConsoleLine.CODEC.apply(ByteBufCodecs.list(MAX_CONSOLE)), DebugState::console, DebugState::new);

        public DebugState {
            breakpoints = List.copyOf(breakpoints);
            console = List.copyOf(console);
        }

        /** Ends the client's debug view without disclosing program data. */
        static DebugState detached(BlockPos pos, String message) {
            return new DebugState(pos, EMPTY_REQUEST, false, shorten(message),
                    new Status(STOPPED, "Debugger detached", "", 0, 0, List.of(), ""), Pause.NONE, List.of(), List.of());
        }

        /** True for a server-initiated update rather than the reply to a request. */
        public boolean pushed() {
            return requestId.equals(EMPTY_REQUEST);
        }

        @Override public Type<DebugState> type() { return TYPE; }
    }

    /** Execution state, the latest runtime fault or activation rejection, and recent statistics. Line 0 means none. */
    public record Status(int state, String headline, String fault, int faultLine, int faultColumn, List<Frame> faultFrames,
                         String statistics) {
        static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Status::state,
                ByteBufCodecs.stringUtf8(STATE), Status::headline, ByteBufCodecs.stringUtf8(512), Status::fault,
                ByteBufCodecs.VAR_INT, Status::faultLine, ByteBufCodecs.VAR_INT, Status::faultColumn,
                Frame.CODEC.apply(ByteBufCodecs.list(MAX_FRAMES)), Status::faultFrames,
                ByteBufCodecs.stringUtf8(512), Status::statistics, Status::new);

        public Status { faultFrames = List.copyOf(faultFrames); }
    }

    /** The suspended statement, when line is positive, with its call stack and variable summaries. */
    public record Pause(int line, int column, String reason, List<Frame> frames, List<Variable> variables) {
        public static final Pause NONE = new Pause(0, 0, "", List.of(), List.of());
        static final StreamCodec<ByteBuf, Pause> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Pause::line,
                ByteBufCodecs.VAR_INT, Pause::column, ByteBufCodecs.stringUtf8(32), Pause::reason,
                Frame.CODEC.apply(ByteBufCodecs.list(MAX_FRAMES)), Pause::frames,
                Variable.CODEC.apply(ByteBufCodecs.list(MAX_VARIABLES)), Pause::variables, Pause::new);

        public Pause {
            frames = List.copyOf(frames);
            variables = List.copyOf(variables);
        }
    }

    /** A function, method or handler name with the line it is executing or waiting on. */
    public record Frame(String function, int line) {
        static final int NAME = 64;
        static final StreamCodec<ByteBuf, Frame> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(NAME),
                Frame::function, ByteBufCodecs.VAR_INT, Frame::line, Frame::new);
    }

    public record Variable(String scope, String name, String value) {
        static final int NAME = 64;
        static final int VALUE = 128;
        static final StreamCodec<ByteBuf, Variable> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(8),
                Variable::scope, ByteBufCodecs.stringUtf8(NAME), Variable::name, ByteBufCodecs.stringUtf8(VALUE),
                Variable::value, Variable::new);
    }

    /** Kind is the ordinal of {@link dev.wiktorfila.filasignal.core.runtime.debug.ProgramConsole.Kind}. */
    public record ConsoleLine(int kind, String text) {
        static final StreamCodec<ByteBuf, ConsoleLine> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                ConsoleLine::kind, ByteBufCodecs.stringUtf8(dev.wiktorfila.filasignal.core.runtime.debug.ProgramConsole.MAX_LINE),
                ConsoleLine::text, ConsoleLine::new);
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().registerLarge(Request.TYPE, Request.CODEC, 52_000);
        // Worst case: 49,152 source bytes, 49,152 catalog bytes and a few kilobytes of status text.
        PayloadTypeRegistry.clientboundPlay().registerLarge(Reply.TYPE, Reply.CODEC, 112_000);
        PayloadTypeRegistry.clientboundPlay().register(DebugState.TYPE, DebugState.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (packet, context) -> handle(context.player(), packet));
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        if (!ServerPlayNetworking.canSend(player, Reply.TYPE)) {
            player.sendOverlayMessage(Component.literal("This client does not support the FilaSignal editor"));
            return;
        }
        if (!admit(player)) {
            return;
        }
        try {
            send(player, pos, true, EMPTY, ProgramEditing.inspect(player, pos), "Program loaded", ProgramEditing.COMPUTER);
        } catch (IOException | IllegalArgumentException error) {
            player.sendOverlayMessage(Component.literal(message(error)));
        }
    }

    /** Opens the editor on a device's own drive, from the device form. */
    public static void openDevice(ServerPlayer player, BlockPos pos) {
        if (!ServerPlayNetworking.canSend(player, Reply.TYPE)) {
            return;
        }
        try {
            send(player, pos, false, EMPTY, ProgramEditing.inspect(player, pos, ProgramEditing.LOCAL), "Program loaded",
                    ProgramEditing.LOCAL, true);
        } catch (IOException | IllegalArgumentException error) {
            player.sendOverlayMessage(Component.literal(message(error)));
        }
    }

    private static void handle(ServerPlayer player, Request packet) {
        if (!ServerPlayNetworking.canSend(player, Reply.TYPE)) {
            return;
        }
        // Detaching only releases the sender's own monitor, so it bypasses the quota and needs no reply.
        if (packet.action() == Request.DEBUG_DETACH) {
            ComputerLifecycle.detachDebugger(player, packet.pos());
            return;
        }
        if (!admit(player)) {
            return;
        }
        if (packet.action() >= Request.DEBUG_ATTACH && packet.action() <= Request.DEBUG_BREAKPOINTS) {
            if (ServerPlayNetworking.canSend(player, DebugState.TYPE)) {
                ServerPlayNetworking.send(player, ProgramDebugging.respond(player, packet));
            }
            return;
        }
        try {
            respond(player, packet);
        } catch (IOException | IllegalArgumentException error) {
            ServerPlayNetworking.send(player, new Reply(packet.pos(), EMPTY, packet.requestId(), false, false, false,
                    message(error), "", "", "", "", packet.target(), "", ""));
        }
    }

    /** Shared per-player server-thread response and mutation limit for editor and device forms. */
    public static boolean admit(ServerPlayer player) {
        long tick = player.level().getServer().getTickCount();
        Long previous = LAST_REQUEST.get(player);
        if (previous != null && tick - previous < 5) {
            return false;
        }
        var quota = SERVERS.computeIfAbsent(player.level().getServer(), ignored -> new TickLimit());
        if (quota.tick != tick) {
            quota.tick = tick;
            quota.count = 0;
        }
        if (quota.count >= 8) {
            return false;
        }
        quota.count++;
        LAST_REQUEST.put(player, tick);
        return true;
    }

    private static void respond(ServerPlayer player, Request packet) throws IOException {
        String target = packet.target();
        if (!ProgramEditing.validTarget(target)) {
            throw new IllegalArgumentException("Unknown editing target");
        }
        if (packet.action() == Request.DRIVE_LABEL || packet.action() == Request.DRIVE_TARGET
                || packet.action() == Request.DRIVE_EJECT) {
            String action = switch (packet.action()) {
                case Request.DRIVE_LABEL -> "label";
                case Request.DRIVE_TARGET -> "target";
                default -> "eject";
            };
            String status = ProgramEditing.driveAction(player, packet.pos(), action, packet.source());
            // The dashboard reopens with the updated drive slot.
            send(player, packet.pos(), true, packet.requestId(), ProgramEditing.inspect(player, packet.pos()), status,
                    ProgramEditing.COMPUTER);
            return;
        }
        var snapshot = switch (packet.action()) {
            case Request.RELOAD, Request.OPEN -> ProgramEditing.inspect(player, packet.pos(), target);
            case Request.VALIDATE -> ProgramEditing.submit(player, packet.pos(), target, packet.revision(), packet.source(), false, false);
            case Request.APPLY -> ProgramEditing.submit(player, packet.pos(), target, packet.revision(), packet.source(), true, false);
            case Request.APPLY_RESET -> ProgramEditing.applyWithFreshGlobals(player, packet.pos(), target, packet.revision(),
                    packet.source());
            case Request.RENAME -> ProgramEditing.rename(player, packet.pos(), packet.revision(), packet.source());
            case Request.IMPORT_BOOK -> ProgramEditing.importBook(player, packet.pos(), target, packet.revision());
            case Request.STOP, Request.START_OR_RESTART -> ProgramEditing.control(player, packet.pos(), target, packet.revision(),
                    packet.action() == Request.START_OR_RESTART);
            default -> throw new IllegalArgumentException("Unknown editor action");
        };
        String status = switch (packet.action()) {
            case Request.VALIDATE -> "Draft passed server validation";
            case Request.APPLY_RESET -> "Program saved with fresh globals";
            case Request.APPLY, Request.IMPORT_BOOK -> snapshot.enabled() ? "Program saved and queued for activation"
                    : "Program saved. Execution remains stopped.";
            case Request.RENAME -> "Name saved";
            case Request.STOP -> "Program stopped";
            case Request.START_OR_RESTART -> "Program queued for activation";
            case Request.OPEN -> "Program loaded";
            default -> "Program reloaded";
        };
        // A validation result acknowledges the request without replacing the draft or sending it back.
        if (packet.action() == Request.VALIDATE) {
            ServerPlayNetworking.send(player, new Reply(packet.pos(), snapshot.revision(), packet.requestId(), false, true,
                    snapshot.running(), status, "", "", "", shorten(snapshot.state(), STATE), target,
                    shorten(snapshot.label(), STATE), ""));
        } else if (packet.action() == Request.RENAME) {
            send(player, packet.pos(), true, packet.requestId(), snapshot, status, ProgramEditing.COMPUTER);
        } else {
            send(player, packet.pos(), false, packet.requestId(), snapshot, status, target, packet.action() == Request.OPEN);
        }
    }

    private static void send(ServerPlayer player, BlockPos pos, boolean opening, UUID requestId, ProgramEditing.Snapshot snapshot,
                             String status, String target) throws IOException {
        send(player, pos, opening, requestId, snapshot, status, target, false);
    }

    /**
     * Sends a snapshot. An opening dashboard reply carries the catalog, network summary and drive slot. An editor
     * opening for a target carries that target's catalog.
     */
    private static void send(ServerPlayer player, BlockPos pos, boolean opening, UUID requestId, ProgramEditing.Snapshot snapshot,
                             String status, String target, boolean editor) throws IOException {
        boolean computer = target.equals(ProgramEditing.COMPUTER);
        boolean catalog = opening || editor;
        ServerPlayNetworking.send(player, new Reply(pos, snapshot.revision(), requestId, opening, true, snapshot.running(),
                runtimeStatus(snapshot, catalog, status), snapshot.source(),
                catalog ? ProgramEditing.catalog(player, pos, target) : "",
                opening && computer ? shorten(dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle.editorSummary(player, pos)
                        + "\n" + ComputerLifecycle.budgetSummary(player.level()), 4096) : "", shorten(snapshot.state(), STATE),
                editor ? target : (computer ? "" : target), shorten(snapshot.label(), STATE),
                opening && computer ? shorten(ProgramEditing.driveSummary(player, pos), 160) : ""));
    }

    // Action results stay visible. The latest fault or log line is only the fallback for a freshly opened view.
    private static String runtimeStatus(ProgramEditing.Snapshot snapshot, boolean opening, String fallback) {
        if (!opening || !fallback.equals("Program loaded")) {
            return fallback;
        }
        if (!snapshot.fault().isEmpty()) {
            return shorten("Runtime: " + snapshot.fault());
        }
        return !snapshot.lastLog().isEmpty() ? shorten("Last output: " + snapshot.lastLog()) : fallback;
    }

    /** Shortens text to a codec limit in UTF-16 units without splitting a surrogate pair. */
    public static String shorten(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = limit - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        if (message == null) {
            return "Unable to process the program";
        }
        return shorten(message);
    }

    private static String shorten(String message) {
        return message.length() <= 500 ? message : message.substring(0, message.offsetByCodePoints(0, 250));
    }

    private static String decode(byte[] bytes) {
        try {
            String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (source.length() > ProgramEditing.MAX_SOURCE) {
                throw new io.netty.handler.codec.DecoderException("Program exceeds the editor limit");
            }
            return source;
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new io.netty.handler.codec.DecoderException("Malformed UTF-8 program payload", error);
        }
    }
}
