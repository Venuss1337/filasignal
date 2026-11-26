package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/**
 * Server-owned forms for configured peripherals. Each edit resolves the current loaded device at the requested
 * position, checks proximity and the ACL, confirms the device identity and kind, and validates every value.
 */
public final class DeviceConfigNetwork {
    public static final int ALIAS = 0;
    public static final int SENSOR = 1;
    public static final int PORT_INPUT = 2;
    public static final int PORT_OUTPUT = 3;
    public static final int INVENTORY = 4;
    public static final int RADIUS = 5;
    public static final int DISPLAY_TEXT = 6;
    public static final int DISPLAY_CLEAR = 7;
    public static final int ALARM_SETTINGS = 8;
    public static final int ALARM_TEST = 9;
    public static final int FACE_MODE = 10;
    public static final int REFRESH = 11;
    public static final int TRUST = 12;
    /** Program and drive actions on programmable devices. */
    public static final int PROGRAM_EDIT = 13;
    public static final int PROGRAM_START = 14;
    public static final int PROGRAM_STOP = 15;
    public static final int DRIVE_EJECT = 16;
    public static final int DRIVE_LABEL = 17;
    public static final int STORAGE_VIEW = 18;
    public static final int STORAGE_CLEAR = 19;
    /** Settings for the event and new hardware features. */
    public static final int DETECT_FILTER = 20;
    public static final int THRESHOLD = 21;
    public static final int SERVER_WRITES = 22;
    public static final int PUBLIC_USE = 23;
    public static final int ACCESS_ALLOW = 24;
    public static final int ACCESS_OUTPUT = 25;
    public static final int ACCESS_TEST = 26;
    public static final int PANEL_LABEL = 27;
    public static final int PANEL_PRESS = 28;
    public static final int BRIDGE_PAIR = 29;
    public static final int BRIDGE_UNPAIR = 30;
    static final int SETTINGS = 512;
    static final int READINGS = 1024;
    static final int PROGRAM = 512;

    private DeviceConfigNetwork() {}

    public record Edit(BlockPos pos, UUID identity, int action, String value, String option) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filasignal", "device_edit_v2"));
        static final StreamCodec<ByteBuf, Edit> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Edit::pos,
                UUIDUtil.STREAM_CODEC, Edit::identity, ByteBufCodecs.VAR_INT, Edit::action,
                ByteBufCodecs.stringUtf8(SETTINGS), Edit::value, ByteBufCodecs.stringUtf8(64), Edit::option, Edit::new);
        @Override public Type<Edit> type() { return TYPE; }
    }

    /**
     * A device snapshot. {@code kind} is the device kind name, {@code settings} prefills the kind's fields and
     * {@code readings} are server-formatted live values. An empty title marks a failure reply.
     */
    public record Form(BlockPos pos, UUID identity, boolean opening, String kind, String title, String alias, String settings,
                       String readings, String status, String program) implements CustomPacketPayload {
        public static final Type<Form> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filasignal", "device_form_v3"));
        static final StreamCodec<ByteBuf, Form> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Form::pos,
                UUIDUtil.STREAM_CODEC, Form::identity, ByteBufCodecs.BOOL, Form::opening,
                ByteBufCodecs.stringUtf8(32), Form::kind, ByteBufCodecs.stringUtf8(32), Form::title,
                ByteBufCodecs.stringUtf8(32), Form::alias, ByteBufCodecs.stringUtf8(SETTINGS), Form::settings,
                ByteBufCodecs.stringUtf8(READINGS), Form::readings, ByteBufCodecs.stringUtf8(256), Form::status,
                ByteBufCodecs.stringUtf8(PROGRAM), Form::program, Form::new);
        @Override public Type<Form> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Edit.TYPE, Edit.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Form.TYPE, Form.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Edit.TYPE, (packet, context) -> edit(context.player(), packet));
    }

    static void open(ServerPlayer player, BlockPos pos) {
        if (!ServerPlayNetworking.canSend(player, Form.TYPE) || !EditorNetwork.admit(player)) {
            return;
        }
        try {
            var runtime = PeripheralLifecycle.runtime(player.level().getServer());
            var node = authorize(player, pos, runtime, true);
            ServerPlayNetworking.send(player, form(runtime, player, node, pos, true, ""));
        } catch (IllegalArgumentException error) {
            player.sendOverlayMessage(Component.literal(error.getMessage()));
        }
    }

    private static void edit(ServerPlayer player, Edit packet) {
        if (!ServerPlayNetworking.canSend(player, Form.TYPE) || !EditorNetwork.admit(player)) {
            return;
        }
        ServerPlayNetworking.send(player, respond(player, packet));
    }

    /** Applies one edit and returns the refreshed form, or a failure form without device data. */
    public static Form respond(ServerPlayer player, Edit packet) {
        try {
            var runtime = PeripheralLifecycle.runtime(player.level().getServer());
            var node = authorize(player, packet.pos(), runtime, packet.action() == PANEL_PRESS || packet.action() == REFRESH);
            if (!packet.identity().equals(node.id())) {
                throw new IllegalArgumentException("Device changed. Reopen the configuration.");
            }
            String message = apply(player, runtime, node, packet);
            Form form = form(runtime, player, runtime.directory().node(node.id()), packet.pos(), false, message);
            if (packet.action() == STORAGE_VIEW) {
                var entity = (PeripheralEntity) runtime.live(node.id());
                form = new Form(form.pos(), form.identity(), false, form.kind(), form.title(), form.alias(), form.settings(),
                        EditorNetwork.shorten(node.kind() == Kind.STORAGE_SERVER ? DeviceReadings.served(entity)
                                : DeviceReadings.storage(entity), READINGS), form.status(), form.program());
            }
            return form;
        } catch (IllegalArgumentException | HostFailure error) {
            return new Form(packet.pos(), packet.identity(), false, "", "", "", "", "", EditorNetwork.shorten(
                    error.getMessage() == null ? "Request failed" : error.getMessage(), 256), "");
        }
    }

    private static String apply(ServerPlayer player, PeripheralRuntime runtime, NetworkDirectory.Node node, Edit edit) {
        var entity = (PeripheralEntity) runtime.live(node.id());
        var level = (ServerLevel) entity.getLevel();
        String alias = edit.value().isEmpty() || edit.value().equals("-") ? null : edit.value();
        if (edit.action() == PANEL_PRESS) {
            require(node, Kind.CONTROL_PANEL);
            return AdvancedApis.press(runtime, player, entity, number(edit.option()));
        }
        if (edit.action() != REFRESH && !NetworkPermission.allows(runtime.directory(), node, NetworkPermission.Actor.from(player), false)) {
            throw new IllegalArgumentException("Only the owner, a trusted player or an administrator can change this device");
        }
        switch (edit.action()) {
            case ALIAS -> runtime.alias(node, alias);
            case SENSOR -> {
                require(node, Kind.SENSOR);
                entity.configureSensor(number(edit.value()), number(edit.option()));
            }
            case PORT_INPUT, PORT_OUTPUT -> {
                require(node, Kind.INTERFACE);
                runtime.port(node, face(edit.option()), edit.action() == PORT_INPUT
                        ? NetworkDirectory.Mode.INPUT : NetworkDirectory.Mode.OUTPUT, alias);
            }
            case INVENTORY -> {
                if (!PeripheralRuntime.inventoryFaces(node.kind())) {
                    throw new IllegalArgumentException("This device has no inventory faces");
                }
                NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), true);
                runtime.inventory(node, face(edit.option()), alias);
            }
            case RADIUS -> {
                require(node, Kind.ENTITY_DETECTOR);
                entity.configureRadius(number(edit.value()));
                runtime.detection().forget(entity);
            }
            case DISPLAY_TEXT -> {
                require(node, Kind.DISPLAY);
                // The single-line form field uses | between lines.
                entity.display(DisplayText.lines(edit.value().replace('|', '\n')));
            }
            case DISPLAY_CLEAR -> {
                require(node, Kind.DISPLAY);
                entity.display(java.util.List.of());
            }
            case ALARM_SETTINGS -> {
                require(node, Kind.ALARM);
                String[] numbers = edit.option().split(",", 2);
                entity.alarm(new AlarmSettings(edit.value().strip().toLowerCase(Locale.ROOT), number(numbers[0]),
                        number(numbers.length > 1 ? numbers[1] : "")));
            }
            case ALARM_TEST -> {
                require(node, Kind.ALARM);
                if (edit.value().equals("on")) {
                    runtime.alarms().activate(entity, level, runtime.tickCount());
                } else {
                    runtime.alarms().deactivate(entity, level);
                }
            }
            case FACE_MODE -> {
                require(node, Kind.REDSTONE_CONTROLLER);
                String mode = edit.option();
                if (!mode.equals("input") && !mode.equals("output")) {
                    throw new IllegalArgumentException("Choose input or output");
                }
                entity.faceMode(level, face(edit.value()), mode.equals("output"));
                runtime.forgetSamples(node.id());
            }
            case REFRESH -> {
                return "Readings refreshed";
            }
            case PROGRAM_EDIT -> {
                requireProgrammable(entity);
                EditorNetwork.openDevice(player, edit.pos());
                return "Opening the program editor";
            }
            case PROGRAM_START, PROGRAM_STOP -> {
                requireProgrammable(entity);
                try {
                    dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.control(level, entity,
                            edit.action() == PROGRAM_START, "player " + player.getName().getString(), player.getUUID());
                } catch (java.io.IOException error) {
                    throw new IllegalArgumentException(error.getMessage());
                }
                return edit.action() == PROGRAM_START ? "Program starting" : "Program stopped";
            }
            case DRIVE_EJECT -> {
                requireProgrammable(entity);
                dev.wiktorfila.filasignal.fabric.drive.Drives.give(player, entity.ejectDrive());
                return "Drive removed";
            }
            case DRIVE_LABEL -> {
                requireProgrammable(entity);
                String label = edit.value().strip();
                if (label.length() > dev.wiktorfila.filasignal.fabric.drive.DriveTag.MAX_LABEL
                        || label.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Labels are up to 32 printable characters");
                }
                entity.labelDrive(label);
                return "Drive label saved";
            }
            case DETECT_FILTER -> {
                require(node, Kind.ENTITY_DETECTOR);
                entity.detectFilter(edit.value().strip().toLowerCase(Locale.ROOT));
                return "Events now report " + entity.detectFilter() + " entities";
            }
            case THRESHOLD -> {
                require(node, Kind.ENVIRONMENT_SENSOR);
                entity.threshold(number(edit.value()));
            }
            case SERVER_WRITES -> {
                require(node, Kind.STORAGE_SERVER);
                NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), true);
                entity.deviceWrites(edit.value().equals("write"));
                return entity.deviceWrites() ? "Device programs may now write" : "Device programs may now only read";
            }
            case PUBLIC_USE -> {
                NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), true);
                entity.publicUse(edit.value().equals("public"));
                return entity.publicUse() ? "Anyone can use it now" : "Only allowed players can use it now";
            }
            case ACCESS_ALLOW -> {
                require(node, Kind.ACCESS_CONTROLLER);
                NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), true);
                return allow(player, entity, edit.value().strip(), edit.option().equals("add"));
            }
            case ACCESS_OUTPUT -> {
                require(node, Kind.ACCESS_CONTROLLER);
                entity.output(number(edit.value()), edit.option().strip().toLowerCase(Locale.ROOT));
                runtime.pulses().close(entity, level);
            }
            case ACCESS_TEST -> {
                require(node, Kind.ACCESS_CONTROLLER);
                runtime.pulses().open(entity, level, runtime.tickCount() + entity.pulseSeconds() * 20L);
                return "Opened for " + entity.pulseSeconds() + " s";
            }
            case PANEL_LABEL -> {
                require(node, Kind.CONTROL_PANEL);
                entity.label(number(edit.option()), edit.value());
                return "Label saved";
            }
            case BRIDGE_PAIR -> {
                require(node, Kind.WIRELESS_BRIDGE);
                UUID target;
                try {
                    target = UUID.fromString(edit.value().strip());
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Choose a bridge from the list");
                }
                return AdvancedApis.pair(runtime, player, node, target);
            }
            case BRIDGE_UNPAIR -> {
                require(node, Kind.WIRELESS_BRIDGE);
                return AdvancedApis.unpair(runtime, player, node);
            }
            case STORAGE_VIEW -> {
                if (node.kind() != Kind.STORAGE_SERVER) {
                    requireProgrammable(entity);
                }
                return "Showing stored data";
            }
            case STORAGE_CLEAR -> {
                if (node.kind() == Kind.STORAGE_SERVER) {
                    NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), true);
                    entity.served().clear();
                    entity.storageChanged();
                    return "Server data cleared";
                }
                requireProgrammable(entity);
                NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), true);
                var record = entity.driveRecord(false);
                if (record != null) {
                    record.clearStorage();
                }
                return "Stored data cleared";
            }
            case TRUST -> {
                return trust(player, runtime, node, edit);
            }
            default -> throw new IllegalArgumentException("Unknown device action");
        }
        return "Settings updated";
    }

    private static String trust(ServerPlayer player, PeripheralRuntime runtime, NetworkDirectory.Node node, Edit edit) {
        var directory = runtime.directory();
        NetworkPermission.require(directory, node, NetworkPermission.Actor.from(player), true);
        UUID authority = node.network() == null ? node.id() : node.network();
        NetworkAccess access = directory.access(authority);
        if (access == null) {
            throw new IllegalArgumentException("An administrator must claim this device first");
        }
        var target = player.level().getServer().getPlayerList().getPlayerByName(edit.value());
        UUID id;
        if (target != null) {
            id = target.getUUID();
        } else {
            try {
                id = UUID.fromString(edit.value());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Enter the name of an online player or a player UUID");
            }
        }
        boolean grant = edit.option().equals("trust");
        if (id.equals(access.owner())) {
            throw new IllegalArgumentException("The owner already has full access");
        }
        runtime.access(authority, access.trust(id, grant));
        return (grant ? "Trusted " : "Removed trust for ") + edit.value()
                + (node.network() == null ? "" : " on the whole network");
    }

    private static String allow(ServerPlayer player, PeripheralEntity entity, String name, boolean add) {
        var online = player.level().getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            entity.allow(online.getUUID(), online.getName().getString(), add);
            return (add ? "Added " : "Removed ") + online.getName().getString();
        }
        if (!add) {
            for (var entry : entity.allowed().entrySet()) {
                if (entry.getValue().equalsIgnoreCase(name)) {
                    entity.allow(entry.getKey(), entry.getValue(), false);
                    return "Removed " + entry.getValue();
                }
            }
        }
        throw new IllegalArgumentException(add ? "Enter the name of a player who is online now" : name + " is not on the list");
    }

    private static void requireProgrammable(PeripheralEntity entity) {
        if (!entity.programmable()) {
            throw new IllegalArgumentException("This device cannot run programs");
        }
    }

    private static void require(NetworkDirectory.Node node, Kind kind) {
        if (node.kind() != kind) {
            throw new IllegalArgumentException("That setting does not apply to this device");
        }
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter whole numbers");
        }
    }

    private static Direction face(String text) {
        for (Direction direction : Direction.values()) {
            if (direction.getSerializedName().equals(text.strip().toLowerCase(Locale.ROOT))) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Enter a world face such as north or up");
    }

    /**
     * Checks proximity and access. With {@code visitors}, a public control panel accepts any player so they can press
     * its buttons; every change still requires access.
     */
    private static NetworkDirectory.Node authorize(ServerPlayer player, BlockPos pos, PeripheralRuntime runtime, boolean visitors) {
        if (player.isSpectator() || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64) {
            throw new IllegalArgumentException("Approach the device to use it");
        }
        var node = runtime.requireDevice(player.level(), pos);
        if (!(runtime.live(node.id()) instanceof PeripheralEntity entity)) {
            throw new IllegalArgumentException("Select a FilaSignal device");
        }
        if (visitors && node.kind() == Kind.CONTROL_PANEL && AdvancedApis.mayPress(runtime, player, entity)) {
            return node;
        }
        NetworkPermission.require(runtime.directory(), node, NetworkPermission.Actor.from(player), false);
        return node;
    }

    private static Form form(PeripheralRuntime runtime, ServerPlayer player, NetworkDirectory.Node node, BlockPos pos,
                             boolean opening, String message) {
        var entity = (PeripheralEntity) runtime.live(node.id());
        String network = runtime.available(node) ? "Connected to its computer" : node.network() == null ? "Not linked to a computer yet"
                : "Computer unreachable: unlinked or its area isn't loaded";
        String status = opening ? network : message + "  ·  " + network;
        boolean configures = NetworkPermission.allows(runtime.directory(), node, NetworkPermission.Actor.from(player), false);
        String settings = node.kind() == Kind.WIRELESS_BRIDGE ? DeviceReadings.bridgeCandidates(runtime, player, node)
                : DeviceReadings.settings(runtime, node, entity);
        if (node.kind() == Kind.CONTROL_PANEL && !configures) {
            // Visitors to a public panel see only its buttons.
            settings = settings + "\tvisitor";
        }
        return new Form(pos, node.id(), opening, node.kind().name(), DeviceReadings.title(node.kind()),
                node.alias() == null ? "" : node.alias(),
                EditorNetwork.shorten(settings, SETTINGS),
                // A visitor receives only what its screen shows: the buttons and the status line, never the access
                // summary or the device program's state, errors and starter.
                configures ? EditorNetwork.shorten(DeviceReadings.describe(runtime, player, node, entity), READINGS) : "",
                EditorNetwork.shorten(status, 256), configures ? EditorNetwork.shorten(DeviceReadings.program(entity), PROGRAM) : "");
    }
}
