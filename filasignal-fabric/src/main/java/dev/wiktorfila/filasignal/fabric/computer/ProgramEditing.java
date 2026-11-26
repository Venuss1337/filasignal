package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.fabric.drive.DriveRecord;
import dev.wiktorfila.filasignal.fabric.drive.DriveStore;
import dev.wiktorfila.filasignal.fabric.drive.DriveTag;
import dev.wiktorfila.filasignal.fabric.drive.Drives;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.UUID;

/**
 * Server-thread boundary for reading and editing programs. Every request names an editing target:
 * <ul>
 *   <li>{@code ""}: the computer's own program;</li>
 *   <li>{@code "drive"}: the drive in the computer's slot, which is edited but never run there;</li>
 *   <li>{@code "local"}: the drive installed in the device at the position;</li>
 *   <li>{@code "remote:NAME"}: the drive in a device on the computer's network.</li>
 * </ul>
 * Each target checks proximity and the ACL of the block the player is using before returning source or accepting
 * changes. A remote device must also be a reachable member of that computer's network.
 */
public final class ProgramEditing {
    public static final int MAX_SOURCE = 16_384;
    /** Upper bound of the completion catalog sent with an opening reply, in UTF-16 units. */
    public static final int MAX_CATALOG = 49_152;
    private static final int MAX_DETAIL = 160;
    /** Prefix of the validation failure that the owner can resolve by applying with fresh globals. */
    public static final String INCOMPATIBLE_GLOBALS = "Saved globals do not fit this program";
    public static final String COMPUTER = "";
    public static final String DRIVE = "drive";
    public static final String LOCAL = "local";
    public static final String REMOTE = "remote:";
    private static final UUID NO_RECORD = new UUID(0, 1);

    private ProgramEditing() {}

    /**
     * Program and runtime snapshot. {@code state} is a short execution status. {@code label} names the editing
     * target for the player, such as "Computer program" or "Entity detector gate · remote".
     */
    public record Snapshot(UUID revision, String source, boolean running, boolean enabled, String fault, String lastLog,
                           String state, String label) {}

    private sealed interface Target permits HostTarget, DriveSlot {}
    private record HostTarget(ProgramHost host, String label) implements Target {}
    private record DriveSlot(ComputerBlockEntity computer, ItemStackRef drive, String label) implements Target {}
    private record ItemStackRef(DriveTag tag) {}

    /** Validates a target key. Keys are bounded and contain no control characters. */
    static boolean validTarget(String key) {
        return key.length() <= 48 && (key.equals(COMPUTER) || key.equals(DRIVE) || key.equals(LOCAL)
                || (key.startsWith(REMOTE) && key.substring(REMOTE.length()).matches("[A-Za-z_][A-Za-z0-9_]{0,31}")));
    }

    private static Target target(ServerPlayer player, BlockPos pos, String key) {
        if (!validTarget(key)) {
            throw new IllegalArgumentException("Unknown editing target");
        }
        if (key.equals(LOCAL)) {
            var device = PeripheralLifecycle.programmableDevice(player, pos);
            return new HostTarget(device, PeripheralLifecycle.describeTarget(device, false));
        }
        ComputerBlockEntity computer = computer(player, pos);
        if (key.equals(COMPUTER)) {
            return new HostTarget(computer, "Computer program");
        }
        if (key.equals(DRIVE)) {
            DriveTag tag = Drives.tag(computer.drive());
            if (tag == null) {
                throw new IllegalArgumentException("Insert a drive into the computer first");
            }
            if (!DriveStore.of(player.level().getServer()).mount(tag.id(), computer)) {
                throw new IllegalArgumentException("That drive is installed in another block");
            }
            String label = tag.label().isEmpty() ? "Drive in computer" : "Drive " + tag.label() + " in computer";
            return new DriveSlot(computer, new ItemStackRef(tag), label + (tag.target().isEmpty() ? "" : " · " + tag.target()));
        }
        String alias = key.substring(REMOTE.length());
        var device = PeripheralLifecycle.remoteDevice(player.level(), computer.activateIdentity(), alias);
        return new HostTarget(device, PeripheralLifecycle.describeTarget(device, true));
    }

    /** The program host behind a target, for the debugger. A drive in a computer slot has none. */
    static ProgramHost host(ServerPlayer player, BlockPos pos, String key) {
        if (target(player, pos, key) instanceof HostTarget host) {
            return host.host();
        }
        throw new IllegalArgumentException("A drive in a computer cannot run; insert it into a device to debug it");
    }

    public static Snapshot inspect(ServerPlayer player, BlockPos position) throws IOException {
        return inspect(player, position, COMPUTER);
    }

    public static Snapshot inspect(ServerPlayer player, BlockPos position, String key) throws IOException {
        return snapshot(player, target(player, position, key));
    }

    /** Validates a draft for the computer program, optionally publishing it under the given edit revision. */
    public static Snapshot submit(ServerPlayer player, BlockPos position, UUID revision, String source, boolean apply)
            throws IOException {
        return submit(player, position, COMPUTER, revision, source, apply, false);
    }

    static Snapshot submit(ServerPlayer player, BlockPos position, String key, UUID revision, String source, boolean apply,
                           boolean reset) throws IOException {
        Target target = target(player, position, key);
        if (!revision(target).equals(revision)) {
            throw new IllegalArgumentException("Program changed on the server. Reload before applying.");
        }
        switch (target) {
            case HostTarget host -> ComputerLifecycle.program(player.level(), host.host(), source, reset, apply);
            case DriveSlot slot -> writeDrive(player, slot, source, reset, apply);
        }
        return snapshot(player, target);
    }

    /**
     * Publishes a draft and discards saved globals. Restricted to the owner or an administrator, like the
     * {@code program-reset} command.
     */
    static Snapshot applyWithFreshGlobals(ServerPlayer player, BlockPos position, UUID revision, String source)
            throws IOException {
        return applyWithFreshGlobals(player, position, COMPUTER, revision, source);
    }

    static Snapshot applyWithFreshGlobals(ServerPlayer player, BlockPos position, String key, UUID revision, String source)
            throws IOException {
        Target target = target(player, position, key);
        BlockPos authority = target instanceof HostTarget host && host.host() != null && !(host.host() instanceof ComputerBlockEntity)
                ? host.host().block().getBlockPos() : position;
        PeripheralLifecycle.authorizeReset(player, authority);
        return submit(player, position, key, revision, source, true, true);
    }

    static Snapshot control(ServerPlayer player, BlockPos position, UUID revision, boolean enabled) throws IOException {
        return control(player, position, COMPUTER, revision, enabled);
    }

    static Snapshot control(ServerPlayer player, BlockPos position, String key, UUID revision, boolean enabled) throws IOException {
        Target target = target(player, position, key);
        if (!(target instanceof HostTarget host)) {
            throw new IllegalArgumentException("A drive in a computer cannot run; insert it into a device");
        }
        if (!host.host().editRevision().equals(revision)) {
            throw new IllegalArgumentException("Program changed on the server. Reload before changing execution.");
        }
        ComputerLifecycle.control(player.level(), host.host(), enabled, "player " + player.getName().getString(), player.getUUID());
        return snapshot(player, target);
    }

    static Snapshot importBook(ServerPlayer player, BlockPos position, String key, UUID revision) throws IOException {
        target(player, position, key);
        return submit(player, position, key, revision, dev.wiktorfila.filasignal.fabric.peripheral.ProgramBook.read(player), true, false);
    }

    static Snapshot rename(ServerPlayer player, BlockPos position, UUID revision, String name) throws IOException {
        var computer = computer(player, position);
        if (!computer.editRevision().equals(revision)) {
            throw new IllegalArgumentException("Program changed on the server. Reopen before renaming.");
        }
        PeripheralLifecycle.renameComputer(player, position, name);
        return snapshot(player, new HostTarget(computer, "Computer program"));
    }

    // Drive slot of a computer

    /** Applies a drive-slot action at a computer: {@code label}, {@code target} or {@code eject}. */
    static String driveAction(ServerPlayer player, BlockPos position, String action, String value) {
        ComputerBlockEntity computer = computer(player, position);
        DriveTag tag = Drives.tag(computer.drive());
        if (tag == null) {
            throw new IllegalArgumentException("Insert a drive into the computer first");
        }
        switch (action) {
            case "label" -> {
                String label = value.strip();
                if (label.length() > DriveTag.MAX_LABEL || label.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Labels are up to " + DriveTag.MAX_LABEL + " printable characters");
                }
                computer.drive().set(Drives.TAG, tag.withLabel(label));
                computer.driveChanged();
                return "Drive label saved";
            }
            case "target" -> {
                PeripheralLifecycle.driveCapabilities(value);
                computer.drive().set(Drives.TAG, tag.withTarget(value));
                computer.driveChanged();
                return "Drive written for " + value;
            }
            case "eject" -> {
                Drives.give(player, computer.ejectDrive());
                ComputerBlock.show(computer, null, false);
                return "Drive removed";
            }
            default -> throw new IllegalArgumentException("Unknown drive action");
        }
    }

    /** A bounded description of the computer's drive slot for the dashboard, or empty. */
    static String driveSummary(ServerPlayer player, BlockPos position) {
        ComputerBlockEntity computer = computer(player, position);
        DriveTag tag = Drives.tag(computer.drive());
        if (tag == null) {
            return Drives.isDrive(computer.drive()) ? "blank" : "";
        }
        DriveRecord record = DriveStore.of(player.level().getServer()).find(tag.id());
        String used = record == null || record.damage() != null ? "0" : Integer.toString(record.storage().used());
        String program = record == null || record.source().isBlank() ? "no program"
                : record.source().lines().count() + " lines";
        return String.join("\t", tag.label(), tag.target(), program, used, record != null && record.damage() != null ? "damaged" : "");
    }

    private static void writeDrive(ServerPlayer player, DriveSlot slot, String source, boolean reset, boolean apply) throws IOException {
        String kind = slot.drive().tag().target();
        if (kind.isEmpty()) {
            throw new IllegalArgumentException("Choose the drive's hardware type on the computer dashboard first");
        }
        var server = player.level().getServer();
        var store = DriveStore.of(server);
        DriveRecord existing = store.find(slot.drive().tag().id());
        if (existing != null && existing.damage() != null) {
            throw new IllegalArgumentException("The drive record is damaged: " + existing.damage());
        }
        ComputerLifecycle.validate("drive", source, PeripheralLifecycle.driveHosts(server, kind),
                PeripheralLifecycle.driveCapabilities(kind), reset || existing == null ? null : existing.globals());
        if (apply) {
            DriveRecord record = store.open(slot.drive().tag().id());
            record.program(source, reset);
            record.touch(player.level().getGameTime());
        }
    }

    private static UUID revision(Target target) {
        return switch (target) {
            case HostTarget host -> host.host().editRevision();
            case DriveSlot slot -> {
                DriveRecord record = DriveStore.of(slot.computer().getLevel().getServer()).find(slot.drive().tag().id());
                yield record == null ? NO_RECORD : record.revision();
            }
        };
    }

    static String catalog(ServerPlayer player, BlockPos position) throws IOException {
        return catalog(player, position, COMPUTER);
    }

    static String catalog(ServerPlayer player, BlockPos position, String key) throws IOException {
        Target target = target(player, position, key);
        var lines = new java.util.ArrayList<String>();
        lines.addAll(java.util.List.of("print(value)\tShow a value in the program console.",
                "str(value)\tTurn a value into text, such as \"Count: \" + str(count).",
                "json.parse(text)\tRead JSON text into a value.", "json.stringify(value)\tWrite a value as JSON text."));
        for (var event : dev.wiktorfila.filasignal.core.analysis.Resolver.builtinEvents()) {
            lines.add(event(event.name(), event));
        }
        HostRegistry registry;
        if (target instanceof HostTarget host && host.host() instanceof ComputerBlockEntity computer) {
            registry = PeripheralLifecycle.computer(player.level(), computer, computer.activateData().identity());
        } else {
            String kind = switch (target) {
                case HostTarget host -> host.host().displayName();
                case DriveSlot slot -> slot.drive().tag().target();
            };
            if (kind.isEmpty()) {
                return bounded(lines);
            }
            registry = PeripheralLifecycle.driveHosts(player.level().getServer(), kind);
        }
        // Events first, so a draft always offers its handlers even if a large catalog is shortened.
        for (var event : registry.events()) {
            lines.add(event(event.name(), event));
        }
        for (var type : registry.objects()) {
            type.events().values().stream().sorted(java.util.Comparator.comparing(HostRegistry.Event::name))
                    .forEach(event -> lines.add(event(type.name() + "." + event.name(), event)));
        }
        for (var entry : registry.enums().entrySet()) {
            for (String member : entry.getValue()) {
                lines.add(entry.getKey() + "." + member + "\t" + entry.getKey() + " constant");
            }
        }
        for (var function : registry.functions()) {
            lines.add(signature(function, false));
        }
        for (var type : registry.objects()) {
            type.properties().keySet().stream().sorted().forEach(name -> lines.add(type.name() + "." + name));
            type.methods().values().stream().sorted(java.util.Comparator.comparing(HostRegistry.Function::name))
                    .forEach(method -> lines.add(signature(method, true)));
        }
        return bounded(lines);
    }

    /** An event completion line: filters as placeholder values, then the handler's parameter names. */
    private static String event(String name, HostRegistry.Event event) {
        var arguments = new java.util.ArrayList<String>();
        for (var filter : event.filters()) {
            arguments.add(filter.types().contains(HostRegistry.Type.NUMBER) ? "20" : "\"" + filter.name() + "\"");
        }
        arguments.addAll(event.parameters());
        return "on " + name + (arguments.isEmpty() ? "" : "(" + String.join(", ", arguments) + ")") + "\t"
                + detail(event.documentation());
    }

    private static String detail(String documentation) {
        String clean = documentation.replace('\n', ' ').replace('\t', ' ');
        return clean.length() <= MAX_DETAIL ? clean : clean.substring(0, MAX_DETAIL - 1) + "…";
    }

    /** Joins catalog lines, dropping whole trailing lines rather than exceeding the payload bound. */
    static String bounded(java.util.List<String> lines) {
        var joined = new StringBuilder();
        for (String line : lines) {
            if (joined.length() + line.length() + 1 > MAX_CATALOG) {
                break;
            }
            if (!joined.isEmpty()) {
                joined.append('\n');
            }
            joined.append(line);
        }
        return joined.toString();
    }

    /** A function line; a third field names the device type it returns, so the editor can complete its members. */
    private static String signature(HostRegistry.Function function, boolean member) {
        String returned = function.returns().isEmpty() ? null : function.returns().getFirst().handleType();
        return function.name() + "(" + function.parameters().stream().skip(member ? 1 : 0)
                .map(HostRegistry.Slot::name)
                .collect(java.util.stream.Collectors.joining(", ")) + ")\t" + detail(function.documentation())
                + (returned == null ? "" : "\t" + returned);
    }

    private static Snapshot snapshot(ServerPlayer player, Target target) throws IOException {
        var level = player.level();
        return switch (target) {
            case HostTarget host -> {
                var program = host.host().program();
                yield new Snapshot(host.host().editRevision(), program.source(),
                        ComputerLifecycle.active(level.getServer(), host.host()), program.enabled(),
                        ComputerLifecycle.lastFault(level, host.host()), ComputerLifecycle.lastLog(level, host.host()),
                        ComputerLifecycle.stateLine(level, host.host()), host.label());
            }
            case DriveSlot slot -> {
                DriveRecord record = DriveStore.of(level.getServer()).find(slot.drive().tag().id());
                String source = record == null || record.damage() != null ? "" : record.source();
                yield new Snapshot(revision(slot), source, false, false, record != null && record.damage() != null
                        ? "Damaged: " + record.damage() : "", "", "Not executable in a computer; insert into a device", slot.label());
            }
        };
    }

    /** Checks the thread, spectator mode, proximity and ACL, then returns the loaded computer at the position. */
    static ComputerBlockEntity computer(ServerPlayer player, BlockPos position) {
        var level = player.level();
        if (!level.getServer().isSameThread() || player.isSpectator()
                || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) > 64) {
            throw new IllegalArgumentException("Approach the computer to edit its program");
        }
        PeripheralLifecycle.authorizeEditor(player, position);
        if (!(level.getBlockEntity(position) instanceof ComputerBlockEntity computer)) {
            throw new IllegalArgumentException("The computer is unavailable");
        }
        return computer;
    }
}
