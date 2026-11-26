package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle;
import dev.wiktorfila.filasignal.fabric.computer.ProgramHost;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Capability;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.drive.DriveRecord;
import dev.wiktorfila.filasignal.fabric.drive.DriveStore;
import dev.wiktorfila.filasignal.fabric.drive.DriveTag;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.io.IOException;
import dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Block entity for every peripheral kind. It owns the device's stable identity and the configuration that belongs
 * to the physical block. Network membership, aliases and access lists live in the topology record. Fields that do
 * not apply to a kind keep their defaults and are not saved.
 *
 * <p>Programmable kinds also hold one drive. The drive's program, globals and stored data live in its world drive
 * record. The device keeps only whether that drive was authorized to run here. Inserting a drive never starts it:
 * a player with access must start it, and removing the drive withdraws the authorization. Server-thread only.
 */
public final class PeripheralEntity extends BlockEntity implements ProgramHost {
    static final int MIN_RANGE = 1;
    static final int MAX_RANGE = 16;

    private final Kind kind;
    private UUID identity;
    private boolean invalid;
    // Sensor player range, or entity detector radius.
    private int range;
    private int period = 10;
    // Transient redstone strengths. Outputs reset when the device unloads or disconnects.
    private final EnumMap<Direction, Integer> outputs = new EnumMap<>(Direction.class);
    // Redstone controller faces configured as outputs. Other faces are inputs.
    private final EnumSet<Direction> outputFaces = EnumSet.noneOf(Direction.class);
    private List<String> display = List.of();
    private AlarmSettings alarm = AlarmSettings.DEFAULT;
    // Entity detector: which entities its events report, as "all" or an entity category.
    private String detectFilter = "all";
    // Environment sensor: darkened and brightened fire when light crosses this level.
    private int threshold = 8;
    // Storage server data. It belongs to this block and is deleted when the block is broken.
    private NamedStorage served;
    private String servedDamage;
    // A damaged record is saved back unchanged so an operator can recover it.
    private byte[] servedRaw;
    private boolean deviceWrites = true;
    // Access controller and control panel settings.
    private boolean publicUse;
    private final LinkedHashMap<UUID, String> allowed = new LinkedHashMap<>();
    private int pulseSeconds = 3;
    private String outputFace = "all";
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private final String[] labels = {"Button 1", "Button 2", "Button 3", "Button 4"};
    private ItemStack drive = ItemStack.EMPTY;
    // Run authorization for the installed drive. It survives reloads but never moves with the drive.
    private boolean autostart;
    private String startedBy = "";
    // The player whose access a started program uses. Null for programs started before this was recorded.
    private UUID startedById;
    private long programRevision;
    private UUID editRevision = UUID.randomUUID();

    PeripheralEntity(BlockPos position, BlockState state, Kind kind) {
        super(type(kind), position, state);
        this.kind = kind;
        range = kind == Kind.ENTITY_DETECTOR ? 8 : 6;
    }

    private static BlockEntityType<PeripheralEntity> type(Kind kind) {
        return switch (kind) {
            case INTERFACE -> PeripheralBlocks.interfaceType;
            case SENSOR -> PeripheralBlocks.sensorType;
            default -> PeripheralBlocks.hardwareType;
        };
    }

    Kind kind() { return kind; }
    int range() { return range; }
    int period() { return period; }

    /** The device UUID, assigned on first use. Null for a block whose saved identity is invalid. */
    public UUID identity() {
        if (invalid) {
            return null;
        }
        if (identity == null) {
            identity = UUID.randomUUID();
            setChanged();
        }
        return identity;
    }

    void configureSensor(int nextRange, int nextPeriod) {
        if (kind != Kind.SENSOR || nextRange < MIN_RANGE || nextRange > MAX_RANGE || nextPeriod < 5 || nextPeriod > 200) {
            throw new IllegalArgumentException("Sensor range must be 1–16 and polling 5–200 ticks");
        }
        range = nextRange;
        period = nextPeriod;
        setChanged();
    }

    String detectFilter() { return detectFilter; }

    /** Sets which entities detector events report: "all" or a category such as "hostile". */
    void detectFilter(String filter) {
        if (kind != Kind.ENTITY_DETECTOR || !(filter.equals("all") || EntityDetection.CATEGORIES.contains(filter))) {
            throw new IllegalArgumentException("Choose all, player, hostile, passive, item or other");
        }
        if (!filter.equals(detectFilter)) {
            detectFilter = filter;
            setChanged();
        }
    }

    int threshold() { return threshold; }

    void threshold(int level) {
        if (kind != Kind.ENVIRONMENT_SENSOR || level < 1 || level > 15) {
            throw new IllegalArgumentException("The light threshold is a whole number from 1 through 15");
        }
        threshold = level;
        setChanged();
    }

    // Storage server

    static final int SERVER_CAPACITY = 262_144;
    static final int SERVER_KEYS = 1024;

    /**
     * The storage server's data.
     *
     * @throws HostFailure {@code storage.damaged} when the saved data could not be read; it is kept unchanged
     */
    NamedStorage served() {
        if (kind != Kind.STORAGE_SERVER) {
            throw new HostFailure("device.type", "Only a Storage Server stores shared data");
        }
        if (servedDamage != null) {
            throw new HostFailure("storage.damaged", "The server's data is damaged: " + servedDamage);
        }
        if (served == null) {
            served = new NamedStorage(SERVER_CAPACITY, SERVER_KEYS);
        }
        return served;
    }

    /** Stored keys a Storage Server would lose if broken; damaged data counts as one. */
    int storedKeys() {
        if (kind != Kind.STORAGE_SERVER) {
            return 0;
        }
        return servedDamage != null ? 1 : served == null ? 0 : served.size();
    }

    boolean deviceWrites() { return deviceWrites; }

    void deviceWrites(boolean allowed) {
        deviceWrites = allowed;
        setChanged();
    }

    void storageChanged() {
        setChanged();
    }

    // Access controller and control panel

    boolean publicUse() { return publicUse; }

    void publicUse(boolean value) {
        if (kind != Kind.ACCESS_CONTROLLER && kind != Kind.CONTROL_PANEL) {
            throw new IllegalArgumentException("That setting does not apply to this device");
        }
        publicUse = value;
        setChanged();
    }

    static final int MAX_ALLOWED = 32;
    static final int MAX_RECENT = 10;

    java.util.Map<UUID, String> allowed() { return java.util.Collections.unmodifiableMap(allowed); }

    void allow(UUID player, String name, boolean add) {
        if (kind != Kind.ACCESS_CONTROLLER) {
            throw new IllegalArgumentException("That setting does not apply to this device");
        }
        if (add && !allowed.containsKey(player) && allowed.size() >= MAX_ALLOWED) {
            throw new IllegalArgumentException("An Access Controller remembers at most " + MAX_ALLOWED + " players");
        }
        if (add) {
            allowed.put(player, name);
        } else if (allowed.remove(player) == null) {
            throw new IllegalArgumentException(name + " is not on the list");
        }
        setChanged();
    }

    int pulseSeconds() { return pulseSeconds; }
    String outputFace() { return outputFace; }

    void output(int seconds, String face) {
        if (kind != Kind.ACCESS_CONTROLLER || seconds < 1 || seconds > 30) {
            throw new IllegalArgumentException("Open for 1 to 30 seconds");
        }
        if (!face.equals("all")) {
            HardwareHosts.face(new dev.wiktorfila.filasignal.core.runtime.value.Value.Text(face));
        }
        pulseSeconds = seconds;
        outputFace = face;
        setChanged();
    }

    /** The faces an access controller powers while open. */
    List<Direction> pulseFaces() {
        if (outputFace.equals("all")) {
            return List.of(Direction.values());
        }
        return List.of(HardwareHosts.face(new dev.wiktorfila.filasignal.core.runtime.value.Value.Text(outputFace)));
    }

    List<String> recent() { return List.copyOf(recent); }

    void remember(String entry) {
        recent.addFirst(entry.length() > 80 ? entry.substring(0, 80) : entry);
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
        setChanged();
    }

    List<String> labels() { return List.of(labels); }

    void label(int button, String label) {
        if (kind != Kind.CONTROL_PANEL || button < 1 || button > 4) {
            throw new IllegalArgumentException("Choose button 1 to 4");
        }
        String clean = label.strip();
        if (clean.isEmpty() || clean.length() > 24 || clean.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Button labels are 1 to 24 printable characters");
        }
        labels[button - 1] = clean;
        setChanged();
    }

    // Program authority

    UUID startedById() { return startedById; }

    /**
     * True while the player who started this device's program may still use the device: its owner, a trusted player
     * of its network or an administrator. Programs started before starters were recorded use the owner's authority.
     */
    boolean authorityValid(MinecraftServer server) {
        if (!autostart || startedById == null) {
            return true;
        }
        var runtime = PeripheralLifecycle.runtime(server);
        var node = runtime.directory().node(identity());
        if (node == null) {
            return true;
        }
        var online = server.getPlayerList().getPlayer(startedById);
        boolean administrator = online != null ? NetworkPermission.Actor.from(online).administrator()
                : server.getPlayerList().isOp(new net.minecraft.server.players.NameAndId(startedById, ""));
        return NetworkPermission.allows(runtime.directory(), node, new NetworkPermission.Actor(startedById, administrator), false);
    }

    void configureRadius(int radius) {
        if (kind != Kind.ENTITY_DETECTOR || radius < MIN_RANGE || radius > MAX_RANGE) {
            throw new IllegalArgumentException("Detection radius must be a whole number from 1 through 16");
        }
        range = radius;
        setChanged();
    }

    // Redstone

    int output(Direction face) { return outputs.getOrDefault(face, 0); }

    void output(ServerLevel level, Direction face, int power) {
        if (output(face) == power) {
            return;
        }
        if (power == 0) {
            outputs.remove(face);
        } else {
            outputs.put(face, power);
        }
        refreshOutput(level, face);
        if (kind == Kind.ACCESS_CONTROLLER) {
            AccessControllerBlock.show(this, level, !outputs.isEmpty());
        }
    }

    /** Resets visual states saved while active, such as an Access Controller saved open. Called after loading. */
    void loadedInWorld(ServerLevel level) {
        if (kind == Kind.ACCESS_CONTROLLER) {
            AccessControllerBlock.show(this, level, !outputs.isEmpty());
        }
    }

    void refreshOutput(ServerLevel level, Direction face) {
        // Refresh saved neighbor state on load. Updating both blocks also propagates strong power through a conductor.
        if (LoadedChunks.signalArea(level, getBlockPos())) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        BlockPos neighbor = getBlockPos().relative(face);
        if (LoadedChunks.signalArea(level, neighbor)) {
            level.updateNeighborsAt(neighbor, getBlockState().getBlock());
        }
    }

    void clearOutputs(ServerLevel level) {
        for (Direction face : List.copyOf(outputs.keySet())) {
            output(level, face, 0);
        }
    }

    Set<Direction> outputFaces() { return java.util.Collections.unmodifiableSet(outputFaces); }

    /** Switches a redstone controller face between input and output. Leaving output mode clears the face. */
    void faceMode(ServerLevel level, Direction face, boolean output) {
        if (kind != Kind.REDSTONE_CONTROLLER) {
            throw new IllegalArgumentException("Only a Redstone Controller has configurable faces");
        }
        if (output ? outputFaces.add(face) : outputFaces.remove(face)) {
            if (!output) {
                output(level, face, 0);
            }
            setChanged();
        }
    }

    // Display

    List<String> display() { return display; }

    /** A Display Module's text, the only device data a client receives. Empty for other kinds. */
    public List<String> displayLines() { return display; }

    // Client-side renderer layout for the current text. The server never sets it.
    private Object renderCache;

    /** The client renderer's cached layout, or null. Render thread only. */
    public Object renderCache() { return renderCache; }

    public void renderCache(Object cache) { renderCache = cache; }

    void display(List<String> lines) {
        if (kind != Kind.DISPLAY || lines.size() > DisplayText.MAX_LINES) {
            throw new IllegalArgumentException("Invalid display content");
        }
        if (!lines.equals(display)) {
            display = List.copyOf(lines);
            setChanged();
            // Players see the text on the block's face. Only changed text is sent.
            if (getLevel() instanceof ServerLevel level && LoadedChunks.contains(level, getBlockPos())) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Clients receive only a Display Module's visible text, never programs, storage, access lists or other settings.
     */
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        var tag = new net.minecraft.nbt.CompoundTag();
        if (kind == Kind.DISPLAY) {
            tag.putString("fila_display", String.join("\n", display));
        }
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return kind == Kind.DISPLAY ? net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this) : null;
    }

    // Alarm

    AlarmSettings alarm() { return alarm; }

    void alarm(AlarmSettings settings) {
        if (kind != Kind.ALARM) {
            throw new IllegalArgumentException("Only an Alarm Module has alarm settings");
        }
        if (!settings.equals(alarm)) {
            alarm = settings;
            setChanged();
        }
    }

    // Drive slot and program hosting

    /** True for kinds that can run a program from a drive. */
    boolean programmable() {
        return kind.has(Capability.EXECUTION);
    }

    ItemStack drive() { return drive; }

    /** The installed drive's identity, or null. */
    UUID driveId() {
        DriveTag tag = Drives.tag(drive);
        return tag == null ? null : tag.id();
    }

    String startedBy() { return startedBy; }

    /**
     * Installs a drive without starting it. A blank drive is assigned an identity and this device's kind.
     *
     * @throws IllegalArgumentException when the device cannot hold drives, the slot is full or the drive is installed
     *                                  elsewhere
     */
    void insertDrive(ItemStack stack) {
        if (!programmable()) {
            throw new IllegalArgumentException("This device cannot run programs");
        }
        if (!drive.isEmpty() || !Drives.isDrive(stack)) {
            throw new IllegalArgumentException("The drive slot is occupied");
        }
        ItemStack next = stack.copyWithCount(1);
        DriveTag tag = Drives.identify(next);
        if (tag.target().isEmpty()) {
            next.set(Drives.TAG, tag.withTarget(kind.id()));
        }
        if (getLevel() instanceof ServerLevel level && !DriveStore.of(level.getServer()).mount(tag.id(), this)) {
            throw new IllegalArgumentException("That drive is already installed in another block");
        }
        drive = next;
        autostart = false;
        startedBy = "";
        startedById = null;
        programChanged();
    }

    /**
     * Stops the program, saves its globals to the drive record, ends debugging and returns the drive. The device
     * keeps its own configuration.
     */
    ItemStack ejectDrive() {
        if (drive.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (getLevel() instanceof ServerLevel level) {
            ComputerLifecycle.release(level.getServer(), this, "Drive removed");
            UUID id = driveId();
            if (id != null) {
                DriveStore.of(level.getServer()).release(id, this);
            }
        }
        ItemStack removed = drive;
        drive = ItemStack.EMPTY;
        autostart = false;
        startedBy = "";
        startedById = null;
        programChanged();
        return removed;
    }

    /** Releases the drive claim when the block unloads, so another block may use the drive. */
    void releaseDrive() {
        UUID id = driveId();
        if (id != null && getLevel() instanceof ServerLevel level) {
            DriveStore.of(level.getServer()).release(id, this);
        }
    }

    /** Updates the drive label, the only drive data kept in the item itself besides identity and target. */
    void labelDrive(String label) {
        DriveTag tag = Drives.tag(drive);
        if (tag == null) {
            throw new IllegalArgumentException("No drive is installed");
        }
        drive.set(Drives.TAG, tag.withLabel(label.strip()));
        setChanged();
    }

    /**
     * The installed drive record, claiming the drive for this block.
     *
     * @return the record, or null when it has never been written and {@code create} is false
     * @throws HostFailure {@code drive.missing}, {@code drive.in_use} or {@code drive.damaged}
     */
    DriveRecord driveRecord(boolean create) {
        UUID id = driveId();
        if (id == null || !(getLevel() instanceof ServerLevel level)) {
            throw new HostFailure("drive.missing", "No drive is installed");
        }
        var store = DriveStore.of(level.getServer());
        if (!store.mount(id, this)) {
            throw new HostFailure("drive.in_use", "The drive is installed in another block");
        }
        DriveRecord record = create ? store.open(id) : store.find(id);
        if (record != null && record.damage() != null) {
            throw new HostFailure("drive.damaged", "The drive record is damaged: " + record.damage());
        }
        return record;
    }

    private void programChanged() {
        programRevision++;
        editRevision = UUID.randomUUID();
        setChanged();
    }

    @Override public BlockEntity block() { return this; }
    @Override public long revision() { return programRevision; }
    @Override public UUID editRevision() { return editRevision; }
    @Override public String displayName() { return kind.id(); }

    @Override
    public String executionId() {
        return "device:" + identity();
    }

    @Override
    public boolean runnable() {
        return programmable() && !drive.isEmpty() && autostart;
    }

    @Override
    public Program program() {
        if (!programmable()) {
            return Program.none("This device cannot run programs");
        }
        DriveTag tag = Drives.tag(drive);
        if (tag == null) {
            return Program.none("No drive installed");
        }
        if (!tag.target().isEmpty() && !tag.target().equals(kind.id())) {
            return Program.none("Incompatible drive: written for " + tag.target());
        }
        DriveRecord record;
        try {
            record = driveRecord(false);
        } catch (HostFailure unavailable) {
            return Program.none(unavailable.getMessage());
        }
        if (record == null || record.source().isBlank()) {
            return new Program("", null, false, null);
        }
        // A program whose starter has lost access stays installed but does not start again on its own.
        boolean authorized = !(getLevel() instanceof ServerLevel level) || authorityValid(level.getServer());
        return new Program(record.source(), record.globals(), autostart && authorized, null);
    }

    @Override
    public void install(String source, boolean resetGlobals) {
        Program current = program();
        if (current.unavailable() != null) {
            throw new IllegalArgumentException(current.unavailable());
        }
        DriveRecord record = driveRecord(true);
        record.program(source, resetGlobals);
        record.touch(getLevel().getGameTime());
        DriveTag tag = Drives.tag(drive);
        if (tag.target().isEmpty()) {
            drive.set(Drives.TAG, tag.withTarget(kind.id()));
        }
        programChanged();
    }

    @Override
    public void enable(boolean enabled, String authorizedBy, UUID authority) {
        if (enabled) {
            Program current = program();
            if (current.unavailable() != null) {
                throw new IllegalArgumentException(current.unavailable());
            }
            if (current.source().isBlank()) {
                throw new IllegalArgumentException("The drive has no program yet");
            }
        }
        autostart = enabled;
        startedBy = enabled ? authorizedBy : "";
        startedById = enabled ? authority : null;
        programChanged();
    }

    @Override
    public void publishSnapshot(byte[] snapshot) throws IOException {
        DriveRecord record = driveRecord(true);
        record.globals(snapshot);
        record.touch(getLevel().getGameTime());
    }

    @Override
    public HostRegistry hosts(ServerLevel level) {
        var runtime = PeripheralLifecycle.runtime(level.getServer());
        runtime.ensureAttached(this);
        return LocalHosts.registry(runtime, this);
    }

    @Override
    public Set<String> capabilities() {
        return LocalHosts.capabilities(kind);
    }

    @Override
    public void activated(MinecraftServer server) {
        PeripheralLifecycle.runtime(server).forgetSamples(identity());
    }

    @Override
    public void stopped(MinecraftServer server) {
        if (getLevel() instanceof ServerLevel level && kind == Kind.REDSTONE_CONTROLLER) {
            clearOutputs(level);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (getLevel() != null && !drive.isEmpty()) {
            Containers.dropItemStack(getLevel(), pos.getX(), pos.getY(), pos.getZ(), ejectDrive());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int version = input.getIntOr("fila_version", 0);
        try {
            if (version == 0) {
                identity = null;
            } else if (version == 1) {
                identity = UUID.fromString(input.getStringOr("fila_id", ""));
            } else {
                throw new IllegalArgumentException("Unknown peripheral data version");
            }
            range = input.getIntOr("fila_range", range);
            period = input.getIntOr("fila_period", 10);
            invalid = range < MIN_RANGE || range > MAX_RANGE || period < 5 || period > 200;
        } catch (IllegalArgumentException ex) {
            invalid = true;
        }
        // Optional hardware settings fall back to defaults instead of disabling the device.
        outputFaces.clear();
        int mask = input.getIntOr("fila_outputs", 0);
        for (Direction face : Direction.values()) {
            if ((mask & (1 << face.ordinal())) != 0) {
                outputFaces.add(face);
            }
        }
        try {
            String text = input.getStringOr("fila_display", "");
            display = text.isEmpty() ? List.of() : DisplayText.lines(text);
        } catch (HostFailure ignored) {
            display = List.of();
        }
        alarm = AlarmSettings.parse(input.getStringOr("fila_alarm_sound", AlarmSettings.DEFAULT.sound()),
                input.getIntOr("fila_alarm_volume", AlarmSettings.DEFAULT.volume()),
                input.getIntOr("fila_alarm_seconds", AlarmSettings.DEFAULT.seconds()));
        drive = input.read("fila_drive", ItemStack.CODEC).filter(Drives::isDrive).orElse(ItemStack.EMPTY);
        autostart = !drive.isEmpty() && input.getBooleanOr("fila_autostart", false);
        startedBy = input.getStringOr("fila_started_by", "");
        try {
            String starter = input.getStringOr("fila_started_by_id", "");
            startedById = starter.isEmpty() ? null : UUID.fromString(starter);
        } catch (IllegalArgumentException invalid) {
            // An unreadable starter cannot be checked, so the program must be started again.
            startedById = null;
            autostart = false;
        }
        String filter = input.getStringOr("fila_detect", "all");
        detectFilter = filter.equals("all") || EntityDetection.CATEGORIES.contains(filter) ? filter : "all";
        int light = input.getIntOr("fila_threshold", 8);
        threshold = light >= 1 && light <= 15 ? light : 8;
        deviceWrites = input.getBooleanOr("fila_device_writes", true);
        served = null;
        servedDamage = null;
        if (kind == Kind.STORAGE_SERVER) {
            var bytes = input.read("fila_served", com.mojang.serialization.Codec.BYTE_BUFFER).map(buffer -> {
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                return data;
            });
            if (bytes.isPresent()) {
                try {
                    served = NamedStorage.load(bytes.get(), SERVER_CAPACITY, SERVER_KEYS);
                } catch (dev.wiktorfila.filasignal.core.runtime.persistence.StateException damaged) {
                    servedDamage = damaged.getMessage();
                    servedRaw = bytes.get();
                }
            }
        }
        publicUse = input.getBooleanOr("fila_public", false);
        int seconds = input.getIntOr("fila_pulse", 3);
        pulseSeconds = seconds >= 1 && seconds <= 30 ? seconds : 3;
        String face = input.getStringOr("fila_output", "all");
        outputFace = face.equals("all") || java.util.Arrays.stream(Direction.values())
                .anyMatch(direction -> direction.getSerializedName().equals(face)) ? face : "all";
        allowed.clear();
        for (String entry : input.getStringOr("fila_allowed", "").split("\n")) {
            int split = entry.indexOf(' ');
            if (split > 0 && allowed.size() < MAX_ALLOWED) {
                try {
                    allowed.put(UUID.fromString(entry.substring(0, split)), entry.substring(split + 1));
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed entry rather than granting anything.
                }
            }
        }
        recent.clear();
        for (String entry : input.getStringOr("fila_recent", "").split("\n")) {
            if (!entry.isEmpty() && recent.size() < MAX_RECENT) {
                recent.addLast(entry);
            }
        }
        String[] saved = input.getStringOr("fila_labels", "").split("\n", -1);
        for (int i = 0; i < labels.length; i++) {
            String label = i < saved.length ? saved[i].strip() : "";
            labels[i] = label.isEmpty() || label.length() > 24 ? "Button " + (i + 1) : label;
        }
        programRevision++;
        editRevision = UUID.randomUUID();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (invalid) {
            output.putInt("fila_version", -1);
            return;
        }
        if (identity == null) {
            return;
        }
        output.putInt("fila_version", 1);
        output.putString("fila_id", identity.toString());
        output.putInt("fila_range", range);
        output.putInt("fila_period", period);
        if (!outputFaces.isEmpty()) {
            int mask = 0;
            for (Direction face : outputFaces) {
                mask |= 1 << face.ordinal();
            }
            output.putInt("fila_outputs", mask);
        }
        if (!display.isEmpty()) {
            output.putString("fila_display", String.join("\n", display));
        }
        if (!drive.isEmpty()) {
            if (getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
                ComputerLifecycle.checkpoint(level.getServer(), this);
            }
            output.store("fila_drive", ItemStack.CODEC, drive);
            output.putBoolean("fila_autostart", autostart);
            output.putString("fila_started_by", startedBy);
            if (startedById != null) {
                output.putString("fila_started_by_id", startedById.toString());
            }
        }
        if (kind == Kind.ENTITY_DETECTOR && !detectFilter.equals("all")) {
            output.putString("fila_detect", detectFilter);
        }
        if (kind == Kind.ENVIRONMENT_SENSOR) {
            output.putInt("fila_threshold", threshold);
        }
        if (kind == Kind.STORAGE_SERVER) {
            output.putBoolean("fila_device_writes", deviceWrites);
            byte[] data = servedDamage != null ? servedRaw : served == null ? null : served.save();
            if (data != null) {
                output.store("fila_served", com.mojang.serialization.Codec.BYTE_BUFFER, java.nio.ByteBuffer.wrap(data));
            }
        }
        if (kind == Kind.ACCESS_CONTROLLER || kind == Kind.CONTROL_PANEL) {
            output.putBoolean("fila_public", publicUse);
        }
        if (kind == Kind.ACCESS_CONTROLLER) {
            output.putInt("fila_pulse", pulseSeconds);
            output.putString("fila_output", outputFace);
            var entries = new java.util.ArrayList<String>();
            allowed.forEach((id, name) -> entries.add(id + " " + name));
            output.putString("fila_allowed", String.join("\n", entries));
            output.putString("fila_recent", String.join("\n", recent));
        }
        if (kind == Kind.CONTROL_PANEL) {
            output.putString("fila_labels", String.join("\n", labels));
        }
        if (kind == Kind.ALARM) {
            output.putString("fila_alarm_sound", alarm.sound());
            output.putInt("fila_alarm_volume", alarm.volume());
            output.putInt("fila_alarm_seconds", alarm.seconds());
        }
    }
}
