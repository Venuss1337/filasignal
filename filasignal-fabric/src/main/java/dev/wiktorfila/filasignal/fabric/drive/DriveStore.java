package dev.wiktorfila.filasignal.fabric.drive;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-level home of drive records, split across 16 saved-data files so a save rewrites only changed shards.
 * It also holds the runtime mount table: a drive identity can be inserted in at most one block at a time, which
 * stops duplicated items from running two copies of one program state. Server-thread only.
 */
public final class DriveStore {
    public static final int MAX_RECORDS = 4096;
    private static final int SHARDS = 16;
    private static final Map<MinecraftServer, DriveStore> STORES = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final java.util.List<SavedDataType<Shard>> TYPES = java.util.stream.IntStream.range(0, SHARDS)
            .mapToObj(i -> new SavedDataType<>(Identifier.fromNamespaceAndPath("filasignal", "drives_" + Integer.toHexString(i)),
                    Shard::new, Codec.PASSTHROUGH.xmap(Shard::read, Shard::write), null)).toList();

    private final MinecraftServer server;
    private final Map<UUID, Object> mounts = new HashMap<>();

    private DriveStore(MinecraftServer server) {
        this.server = server;
    }

    public static DriveStore of(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Drive storage requires the Minecraft server thread");
        }
        return STORES.computeIfAbsent(server, DriveStore::new);
    }

    static void forget(MinecraftServer server) {
        STORES.remove(server);
    }

    private Shard shard(int index) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPES.get(index));
    }

    private Shard shard(UUID id) {
        return shard(Math.floorMod(id.hashCode(), SHARDS));
    }

    /** The record for a drive, or null when the drive has never been written. */
    public DriveRecord find(UUID id) {
        return shard(id).records.get(id);
    }

    /**
     * Returns the existing record or creates an empty one.
     *
     * @throws IllegalArgumentException when the world already holds {@value #MAX_RECORDS} records
     */
    public DriveRecord open(UUID id) {
        Shard shard = shard(id);
        DriveRecord record = shard.records.get(id);
        if (record == null) {
            if (count() >= MAX_RECORDS) {
                throw new IllegalArgumentException("This world already holds " + MAX_RECORDS
                        + " drive records; an administrator can prune unused ones");
            }
            record = new DriveRecord(id, shard::setDirty);
            record.touch(server.overworld().getGameTime());
            shard.records.put(id, record);
        }
        return record;
    }

    /** Claims a drive for a block. Returns false while another block holds it. */
    public boolean mount(UUID id, Object holder) {
        Object current = mounts.putIfAbsent(id, holder);
        if (current == null) {
            // Installing counts as use, so a drive moved between devices is not mistaken for an abandoned one.
            DriveRecord record = find(id);
            if (record != null && record.damage() == null) {
                record.touch(server.overworld().getGameTime());
            }
        }
        return current == null || current == holder;
    }

    public void release(UUID id, Object holder) {
        mounts.remove(id, holder);
    }

    public Object holder(UUID id) {
        return mounts.get(id);
    }

    public int count() {
        int count = 0;
        for (int i = 0; i < SHARDS; i++) {
            count += shard(i).records.size();
        }
        return count;
    }

    /** Deletes unmounted, undamaged records unused for longer than the given number of ticks. */
    public int prune(long olderThanTicks) {
        return prune(olderThanTicks, true);
    }

    /**
     * Counts, and with {@code apply} deletes, records that are not installed in a loaded block and have not been used
     * for the given time. Age is not proof that a drive is gone: the item may still sit in an unloaded chest or an
     * offline player's inventory, and its program and data are lost if the record is deleted.
     */
    public int prune(long olderThanTicks, boolean apply) {
        long now = server.overworld().getGameTime();
        int matched = 0;
        for (int i = 0; i < SHARDS; i++) {
            Shard shard = shard(i);
            var iterator = shard.records.values().iterator();
            while (iterator.hasNext()) {
                DriveRecord record = iterator.next();
                if (record.damage() == null && !mounts.containsKey(record.id()) && now - record.lastSeen() > olderThanTicks) {
                    if (apply) {
                        iterator.remove();
                        shard.setDirty();
                    }
                    matched++;
                }
            }
        }
        return matched;
    }

    static final class Shard extends SavedData {
        private final Map<UUID, DriveRecord> records = new LinkedHashMap<>();

        static Shard read(Dynamic<?> raw) {
            var shard = new Shard();
            if (raw.convert(NbtOps.INSTANCE).getValue() instanceof CompoundTag tag) {
                ListTag list = tag.getList("drives").orElseGet(ListTag::new);
                for (int i = 0; i < list.size(); i++) {
                    var entry = list.getCompound(i).orElse(null);
                    DriveRecord record = entry == null ? null : DriveRecord.read(entry, shard::setDirty);
                    if (record == null) {
                        LoggerFactory.getLogger("filasignal/drives").error("Skipping a drive record without a valid ID");
                        continue;
                    }
                    if (record.damage() != null) {
                        LoggerFactory.getLogger("filasignal/drives").error("Drive {} is damaged and disabled: {}",
                                record.id(), record.damage());
                    }
                    shard.records.put(record.id(), record);
                }
            }
            return shard;
        }

        Dynamic<?> write() {
            var list = new ListTag();
            records.values().forEach(record -> list.add(record.write()));
            var tag = new CompoundTag();
            tag.putInt("version", DriveRecord.VERSION);
            tag.put("drives", list);
            return new Dynamic<>(NbtOps.INSTANCE, tag);
        }
    }
}
