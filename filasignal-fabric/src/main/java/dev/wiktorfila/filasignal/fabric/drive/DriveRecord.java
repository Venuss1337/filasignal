package dev.wiktorfila.filasignal.fabric.drive;

import dev.wiktorfila.filasignal.core.runtime.persistence.NamedStorage;
import dev.wiktorfila.filasignal.core.runtime.persistence.StateException;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.util.UUID;

/**
 * The single authoritative copy of a drive's portable contents: program source and revision, persistent globals
 * and named storage. It never holds device identity, network membership, ownership or permissions. Mutations mark
 * the owning shard dirty and reach disk with the next world save. Server-thread only.
 */
public final class DriveRecord {
    public static final int VERSION = 1;
    public static final int MAX_SOURCE = 16_384;
    public static final int MAX_GLOBALS = 65_536;

    private final UUID id;
    private final Runnable changed;
    private String source = "";
    private UUID revision = UUID.randomUUID();
    private byte[] globals;
    private NamedStorage storage = new NamedStorage();
    private long lastSeen;
    private String damage;
    private CompoundTag raw;

    DriveRecord(UUID id, Runnable changed) {
        this.id = id;
        this.changed = changed;
    }

    public UUID id() { return id; }
    public String source() { return source; }
    public UUID revision() { return revision; }
    public byte[] globals() { return globals == null ? null : globals.clone(); }
    public long lastSeen() { return lastSeen; }

    /** Non-null when the saved record could not be decoded. A damaged record is preserved but cannot be used. */
    public String damage() { return damage; }

    /** Live storage. Callers mutate it only through methods that then call {@link #touch(long)}. */
    public NamedStorage storage() {
        requireUsable();
        return storage;
    }

    /** Replaces the program and optionally its globals, rotating the revision. */
    public void program(String next, boolean resetGlobals) {
        requireUsable();
        if (next.length() > MAX_SOURCE) {
            throw new IllegalArgumentException("Program exceeds " + MAX_SOURCE + " characters");
        }
        source = next;
        revision = UUID.randomUUID();
        if (resetGlobals) {
            globals = null;
        }
        changed.run();
    }

    public void globals(byte[] snapshot) throws IOException {
        requireUsable();
        if (snapshot.length > MAX_GLOBALS) {
            throw new IOException("Drive globals exceed " + MAX_GLOBALS + " bytes");
        }
        globals = snapshot.clone();
        changed.run();
    }

    /** Records use and storage changes so pruning and saving see them. */
    public void touch(long gameTime) {
        lastSeen = gameTime;
        changed.run();
    }

    public void clearStorage() {
        requireUsable();
        storage.clear();
        changed.run();
    }

    private void requireUsable() {
        if (damage != null) {
            throw new IllegalStateException("The drive record is damaged: " + damage);
        }
    }

    CompoundTag write() {
        if (raw != null) {
            return raw.copy();
        }
        var tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putString("id", id.toString());
        tag.putString("source", source);
        tag.putString("revision", revision.toString());
        if (globals != null) {
            tag.putByteArray("globals", globals);
        }
        tag.putByteArray("storage", storage.save());
        tag.putLong("last_seen", lastSeen);
        return tag;
    }

    /** Decodes a record. A damaged record keeps its raw tag so it is written back unchanged. Null without an ID. */
    static DriveRecord read(CompoundTag tag, Runnable changed) {
        UUID id;
        try {
            id = UUID.fromString(tag.getStringOr("id", ""));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        var record = new DriveRecord(id, changed);
        try {
            if (tag.getIntOr("version", -1) != VERSION) {
                throw new IOException("Unsupported drive format " + tag.getIntOr("version", -1));
            }
            String source = tag.getStringOr("source", "");
            if (source.length() > MAX_SOURCE) {
                throw new IOException("Drive program is too long");
            }
            record.source = source;
            record.revision = UUID.fromString(tag.getStringOr("revision", UUID.randomUUID().toString()));
            byte[] globals = tag.getByteArray("globals").orElse(null);
            if (globals != null && globals.length > MAX_GLOBALS) {
                throw new IOException("Drive globals are too large");
            }
            record.globals = globals;
            record.storage = NamedStorage.load(tag.getByteArray("storage").orElse(new NamedStorage().save()));
            record.lastSeen = tag.getLongOr("last_seen", 0);
        } catch (IOException | IllegalArgumentException error) {
            record.damage = error instanceof StateException state ? state.reason() + ": " + state.getMessage() : error.getMessage();
            record.raw = tag.copy();
        }
        return record;
    }
}
