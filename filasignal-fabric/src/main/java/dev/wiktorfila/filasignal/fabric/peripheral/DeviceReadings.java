package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Text for device configuration forms: titles, field prefills and live readings. Server-thread only. */
final class DeviceReadings {
    private DeviceReadings() {}

    static String title(Kind kind) {
        return HardwareHosts.title(kind);
    }

    /** Values the client uses to prefill the kind's fields. */
    static String settings(PeripheralRuntime runtime, NetworkDirectory.Node node, PeripheralEntity entity) {
        return switch (node.kind()) {
            case SENSOR -> entity.range() + "," + entity.period();
            case ENTITY_DETECTOR -> entity.range() + "," + entity.detectFilter();
            case ENVIRONMENT_SENSOR -> Integer.toString(entity.threshold());
            case STORAGE_SERVER -> entity.deviceWrites() ? "write" : "read";
            case ACCESS_CONTROLLER -> (entity.publicUse() ? "public" : "restricted") + "," + entity.pulseSeconds() + ","
                    + entity.outputFace();
            case CONTROL_PANEL -> (entity.publicUse() ? "public" : "restricted") + "\t" + String.join("\t", entity.labels());
            case WIRELESS_BRIDGE -> "";
            case DISPLAY -> String.join("|", entity.display());
            case ALARM -> entity.alarm().sound() + "," + entity.alarm().volume() + "," + entity.alarm().seconds();
            case REDSTONE_CONTROLLER -> String.join(",", entity.outputFaces().stream().map(Direction::getSerializedName).toList());
            default -> "";
        };
    }

    /** Human-readable access summary and live readings, one item per line. */
    static String describe(PeripheralRuntime runtime, ServerPlayer player, NetworkDirectory.Node node, PeripheralEntity entity) {
        var lines = new ArrayList<String>();
        lines.add(access(runtime, player, node));
        var level = (ServerLevel) entity.getLevel();
        try {
            switch (node.kind()) {
                case INTERFACE, INVENTORY_MODULE -> faces(runtime, node, lines);
                case SENSOR -> lines.add("Player range " + entity.range() + " · poll every " + entity.period() + " ticks");
                case REPEATER -> {
                    var neighbors = runtime.directory().neighbors(node.id());
                    long connected = neighbors.stream().map(runtime.directory()::node).filter(runtime::available).count();
                    lines.add("Links " + neighbors.size() + " · connected " + connected);
                }
                case ENTITY_DETECTOR -> {
                    var scan = runtime.detection().scan(entity, level, runtime.tickCount());
                    var counts = new StringBuilder("Radius " + entity.range());
                    for (String category : EntityDetection.CATEGORIES) {
                        long count = scan.sightings().stream().filter(sighting -> sighting.category().equals(category)).count();
                        counts.append(" · ").append(category).append(' ').append(count);
                    }
                    lines.add(counts + (scan.truncated() ? " · more not counted" : ""));
                }
                case WEATHER_STATION -> lines.add(summary(WorldReadings.weather(level, entity.getBlockPos()),
                        "raining", "thundering", "precipitation", "wet", "dayTime", "isDay"));
                case ENVIRONMENT_SENSOR -> lines.add(summary(WorldReadings.environment(level, entity.getBlockPos()),
                        "light", "blockLight", "skyLight", "skyVisible", "biome", "temperature"));
                case REDSTONE_CONTROLLER -> {
                    var faces = new ArrayList<String>();
                    for (Direction face : Direction.values()) {
                        boolean output = entity.outputFaces().contains(face);
                        var neighbor = entity.getBlockPos().relative(face);
                        int power = output ? entity.output(face)
                                : LoadedChunks.signalArea(level, neighbor) ? level.getSignal(neighbor, face) : 0;
                        faces.add(face.getSerializedName() + (output ? " out " : " in ") + power);
                    }
                    lines.add(String.join(" · ", faces));
                }
                case DISPLAY -> lines.add(entity.display().isEmpty() ? "Display is blank"
                        : entity.display().size() + " of " + DisplayText.MAX_LINES + " lines in use");
                case STORAGE_SERVER -> {
                    try {
                        var served = entity.served();
                        lines.add("Using " + served.used() + " of " + served.capacity() + " bytes · " + served.size() + " of "
                                + served.maxEntries() + " keys");
                    } catch (HostFailure damaged) {
                        lines.add(damaged.getMessage());
                    }
                    lines.add(entity.deviceWrites() ? "Device programs on this network may read and write"
                            : "Device programs on this network may only read");
                    lines.add("Breaking the server deletes its data");
                }
                case ACCESS_CONTROLLER -> {
                    lines.add((entity.publicUse() ? "Public: anyone can open it" : "Restricted: owner, trusted and listed players")
                            + " · opens " + entity.pulseSeconds() + " s on " + (entity.outputFace().equals("all") ? "all sides"
                            : entity.outputFace()) + (runtime.pulses().open(entity) ? " · open now" : ""));
                    lines.add(entity.allowed().isEmpty() ? "No listed players"
                            : "Listed: " + String.join(", ", entity.allowed().values()));
                    lines.add(entity.recent().isEmpty() ? "No recent visitors" : "Recent: " + String.join(" | ", entity.recent()));
                }
                case CONTROL_PANEL -> lines.add((entity.publicUse() ? "Public: anyone can press the buttons"
                        : "Restricted: owner and trusted players can press the buttons"));
                case WIRELESS_BRIDGE -> {
                    var status = AdvancedApis.bridgeStatus(runtime, node);
                    if (status.get("paired") instanceof Bool paired && paired.value()) {
                        Value peer = status.get("peer");
                        lines.add("Paired with " + (peer instanceof Text name ? name.value() : "an unnamed bridge") + " · "
                                + summary(status, "distance") + " blocks · "
                                + (status.get("connected") instanceof Bool on && on.value() ? "connected" : "other bridge unloaded"));
                    } else {
                        int count = AdvancedApis.candidates(runtime, player, node).size();
                        lines.add("Not paired · " + (count == 0 ? "no unpaired bridge of yours within "
                                + dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.BRIDGE_RANGE + " blocks is loaded"
                                : count + " bridge" + (count == 1 ? "" : "s") + " in range"));
                    }
                }
                case ALARM -> {
                    long remaining = runtime.alarms().remaining(entity, runtime.tickCount());
                    lines.add((runtime.alarms().active(entity) ? "Sounding · " + (remaining + 19) / 20 + " s left" : "Silent")
                            + " · " + entity.alarm().sound() + " · volume " + entity.alarm().volume()
                            + " · " + entity.alarm().seconds() + " s");
                }
                case COMPUTER -> { }
            }
        } catch (HostFailure busy) {
            lines.add(busy.getMessage());
        }
        return String.join("\n", lines);
    }

    /**
     * The program section of a device form as tab-separated fields: programmable (1 or 0), drive label, drive
     * target, state, storage used, last error and who started the program. Empty fields mean none.
     */
    static String program(PeripheralEntity entity) {
        if (!entity.programmable()) {
            return "0";
        }
        var level = (ServerLevel) entity.getLevel();
        var tag = dev.wiktorfila.filasignal.fabric.drive.Drives.tag(entity.drive());
        String used = "";
        try {
            var record = tag == null ? null : entity.driveRecord(false);
            used = record == null ? "0" : Integer.toString(record.storage().used());
        } catch (HostFailure unavailable) {
            used = "-";
        }
        return String.join("\t", "1", tag == null ? "" : tag.label(), tag == null ? "" : tag.target(),
                dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.stateLine(level, entity), used,
                dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.lastFault(level, entity).replace('\t', ' '),
                entity.startedBy());
    }

    /** Stored keys with short value summaries and recent console lines, bounded for the form. */
    static String storage(PeripheralEntity entity) {
        var lines = new ArrayList<String>();
        try {
            var record = entity.driveRecord(false);
            if (record == null || record.storage().size() == 0) {
                lines.add("No stored data");
            } else {
                var storage = record.storage();
                lines.add(storage.size() + " keys · " + storage.used() + " of "
                        + dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage.CAPACITY + " bytes");
                for (String key : storage.keys()) {
                    if (lines.size() >= 12) {
                        lines.add("…");
                        break;
                    }
                    lines.add(key + " = " + dev.wiktorfila.filasignal.core.runtime.value.ValueSummary.describe(storage.read(key), 60));
                }
            }
        } catch (HostFailure unavailable) {
            lines.add(unavailable.getMessage());
        }
        var level = (ServerLevel) entity.getLevel();
        var console = dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.console(level, entity, 3);
        if (!console.isEmpty()) {
            lines.add("Console: " + String.join(" | ", console));
        }
        return String.join("\n", lines);
    }

    /** Pairing choices visible to one player, as tab-separated "id;label" entries. */
    static String bridgeCandidates(PeripheralRuntime runtime, ServerPlayer player, NetworkDirectory.Node node) {
        var entries = new ArrayList<String>();
        for (var candidate : AdvancedApis.candidates(runtime, player, node)) {
            String name = candidate.alias() == null ? "Bridge at " + candidate.location().x() + " " + candidate.location().y()
                    + " " + candidate.location().z() : candidate.alias();
            entries.add(candidate.id() + ";" + name + " (" + Math.round(AdvancedApis.distance(node, candidate)) + " blocks)");
        }
        return String.join("\t", entries);
    }

    /** The server's stored keys with short value summaries, bounded for the form. */
    static String served(PeripheralEntity entity) {
        var lines = new ArrayList<String>();
        try {
            var storage = entity.served();
            lines.add(storage.size() == 0 ? "No stored data" : storage.size() + " keys · " + storage.used() + " of "
                    + storage.capacity() + " bytes");
            for (String key : storage.keys()) {
                if (lines.size() >= 12) {
                    lines.add("…");
                    break;
                }
                lines.add(key + " = " + dev.wiktorfila.filasignal.core.runtime.value.ValueSummary.describe(storage.read(key), 60));
            }
        } catch (HostFailure damaged) {
            lines.add(damaged.getMessage());
        }
        return String.join("\n", lines);
    }

    private static void faces(PeripheralRuntime runtime, NetworkDirectory.Node node, List<String> lines) {
        var configured = new ArrayList<String>();
        for (Direction side : Direction.values()) {
            var binding = runtime.directory().inventories().at(node.id(), side);
            if (binding != null) {
                configured.add(side.getSerializedName() + ": inventory " + binding.alias());
            }
            node.ports().stream().filter(port -> port.face() == side).forEach(port -> configured.add(
                    side.getSerializedName() + ": " + port.alias() + " " + port.mode().name().toLowerCase(Locale.ROOT)));
        }
        lines.add(configured.isEmpty() ? "No configured faces" : String.join(" · ", configured));
    }

    private static String access(PeripheralRuntime runtime, ServerPlayer player, NetworkDirectory.Node node) {
        UUID authority = node.network() == null ? node.id() : node.network();
        NetworkAccess access = runtime.directory().access(authority);
        if (access == null) {
            return "Owner: unclaimed";
        }
        String role = access.owner().equals(player.getUUID()) ? "owner"
                : access.trusted().contains(player.getUUID()) ? "trusted"
                : NetworkPermission.Actor.from(player).administrator() ? "administrator" : "visitor";
        var owner = player.level().getServer().getPlayerList().getPlayer(access.owner());
        return "Owner " + (owner == null ? access.owner().toString().substring(0, 8) : owner.getName().getString())
                + " · trusted " + access.trusted().size() + " · you: " + role
                + (node.network() == null ? "" : " · access follows the network");
    }

    private static String summary(DictValue values, String... keys) {
        var parts = new ArrayList<String>();
        for (String key : keys) {
            Value value = values.get(key);
            String text = switch (value) {
                case Num number -> number.value() == Math.rint(number.value()) ? Long.toString((long) number.value())
                        : Double.toString(number.value());
                case Bool bool -> bool.value() ? "yes" : "no";
                case Text string -> string.value();
                case null, default -> "?";
            };
            parts.add(key + " " + text);
        }
        return String.join(" · ", parts);
    }
}
