package dev.wiktorfila.filasignal.fabric.computer;

import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Immutable outer Minecraft save record. The runtime owns the inner snapshot format. */
record ControllerData(UUID identity, String source, byte[] snapshot, boolean enabled) {
    private static final int VERSION = 1;
    private static final int MAX_SOURCE_UNITS = 16_384;
    private static final int MAX_SNAPSHOT_BYTES = 262_144;

    // A new computer's program. It declares no globals, so a player's first program applies without discarding any.
    private static final String DEFAULT_SOURCE = """
            // A new Programmable Computer. Select Starters to load a ready-made program,
            // or write your own and use Save & Apply.
            on startup {
                print("Computer ready. Select Starters in the editor to load an example.")
            }
            """;

    ControllerData(UUID identity, String source, byte[] snapshot) { this(identity, source, snapshot, true); }

    ControllerData {
        snapshot = snapshot == null ? null : snapshot.clone();
    }

    @Override
    public byte[] snapshot() {
        return snapshot == null ? null : snapshot.clone();
    }

    static ControllerData fresh() {
        return new ControllerData(UUID.randomUUID(), DEFAULT_SOURCE, null);
    }

    static ControllerData read(Dynamic<?> raw) throws IOException {
        if (!(raw.convert(NbtOps.INSTANCE).getValue() instanceof CompoundTag tag)
                || tag.getIntOr("version", -1) != VERSION) {
            throw new IOException("Unsupported or malformed controller record");
        }
        try {
            UUID identity = UUID.fromString(tag.getString("identity").orElseThrow());
            if (!identity.toString().equals(tag.getStringOr("identity", ""))) {
                throw new IOException("Noncanonical controller identity");
            }
            String source = tag.getString("source").orElseThrow();
            if (source.length() > MAX_SOURCE_UNITS || source.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) {
                throw new IOException("Controller source is excessive or contains malformed Unicode");
            }
            byte[] snapshot = null;
            if (tag.contains("snapshot")) {
                snapshot = tag.getByteArray("snapshot").orElseThrow();
                if (snapshot.length > MAX_SNAPSHOT_BYTES) {
                    throw new IOException("Controller snapshot exceeds the storage limit");
                }
            }
            return new ControllerData(identity, source, snapshot, tag.getBooleanOr("enabled", true));
        } catch (IllegalArgumentException | java.util.NoSuchElementException error) {
            throw new IOException("Malformed controller identity, source or snapshot", error);
        }
    }

    Dynamic<?> encode() {
        var tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putString("identity", identity.toString());
        tag.putString("source", source);
        tag.putBoolean("enabled", enabled);
        Optional.ofNullable(snapshot()).ifPresent(bytes -> tag.putByteArray("snapshot", bytes));
        return new Dynamic<>(NbtOps.INSTANCE, tag);
    }

    ControllerData withSource(String program, boolean reset) {
        if (program.length() > MAX_SOURCE_UNITS || program.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) {
            throw new IllegalArgumentException("Program exceeds the source limit or contains malformed Unicode");
        }
        return new ControllerData(identity, program, reset ? null : snapshot, enabled);
    }

    ControllerData withEnabled(boolean value) { return new ControllerData(identity, source, snapshot, value); }

    ControllerData withSnapshot(byte[] next) throws IOException {
        if (next.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Controller snapshot exceeds the storage limit");
        }
        return new ControllerData(identity, source, next, enabled);
    }
}
