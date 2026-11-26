package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Entity scans for entity detectors. A scan reads only entities already loaded in the detector's level, examines
 * at most {@value #MAX_EXAMINED} of them and keeps detached snapshots. Repeated queries in the same tick reuse the
 * detector's snapshot, and at most {@value #FRESH_SCANS_PER_TICK} fresh scans run per server tick. Server-thread only.
 */
final class EntityDetection {
    static final int MAX_EXAMINED = 256;
    static final int MAX_RESULTS = 32;
    static final int FRESH_SCANS_PER_TICK = 16;
    static final List<String> CATEGORIES = List.of("player", "hostile", "passive", "item", "other");

    /**
     * A detached observation. {@code name} is only recorded for players and visibly named entities; {@code label} is
     * always a readable name, such as "Zombie". The UUID tells consecutive scans apart and stays on the server.
     */
    record Sighting(String type, String category, String name, int x, int y, int z, double distance, UUID uuid, String label) {}

    record Scan(long tick, List<Sighting> sightings, boolean truncated) {}

    private final Map<PeripheralEntity, Scan> cache = new IdentityHashMap<>();
    private long budgetTick = -1;
    private int freshScans;

    /** Returns the detector's sightings for this tick, sorted by distance. */
    Scan scan(PeripheralEntity detector, ServerLevel level, long tick) {
        Scan cached = cache.get(detector);
        if (cached != null && cached.tick() == tick) {
            return cached;
        }
        if (budgetTick != tick) {
            budgetTick = tick;
            freshScans = 0;
            // Snapshots from earlier ticks are never reused, so drop them rather than keeping one per detector.
            cache.clear();
        }
        if (freshScans >= FRESH_SCANS_PER_TICK) {
            throw new HostFailure("detector.busy", "Too many entity scans this tick; try again next tick");
        }
        freshScans++;
        Vec3 center = Vec3.atCenterOf(detector.getBlockPos());
        double radius = detector.range();
        var found = new ArrayList<Entity>();
        level.getEntities(EntityTypeTest.forClass(Entity.class), AABB.ofSize(center, radius * 2, radius * 2, radius * 2),
                entity -> entity.isAlive() && !entity.isSpectator() && entity.distanceToSqr(center) <= radius * radius,
                found, MAX_EXAMINED + 1);
        boolean truncated = found.size() > MAX_EXAMINED;
        var sightings = new ArrayList<Sighting>(Math.min(found.size(), MAX_EXAMINED));
        for (int i = 0; i < Math.min(found.size(), MAX_EXAMINED); i++) {
            sightings.add(sighting(found.get(i), center));
        }
        sightings.sort(Comparator.comparingDouble(Sighting::distance));
        Scan scan = new Scan(tick, List.copyOf(sightings), truncated);
        cache.put(detector, scan);
        return scan;
    }

    void forget(PeripheralEntity detector) {
        cache.remove(detector);
    }

    /**
     * Parses a filter: {@code all}, a category from {@link #CATEGORIES}, or a namespaced entity type ID.
     *
     * @throws HostFailure {@code argument.invalid} for anything else
     */
    static Predicate<Sighting> filter(String filter) {
        if (filter.equals("all")) {
            return sighting -> true;
        }
        if (CATEGORIES.contains(filter)) {
            return sighting -> sighting.category().equals(filter);
        }
        Identifier id = filter.length() <= 128 && filter.contains(":") ? Identifier.tryParse(filter) : null;
        if (id == null || BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty()) {
            throw new HostFailure("argument.invalid",
                    "Use all, player, hostile, passive, item, other or an entity type such as minecraft:zombie");
        }
        String type = id.toString();
        return sighting -> sighting.type().equals(type);
    }

    static String category(Entity entity) {
        if (entity instanceof Player) {
            return "player";
        }
        if (entity instanceof Enemy || entity.getType().getCategory() == MobCategory.MONSTER) {
            return "hostile";
        }
        if (entity instanceof ItemEntity) {
            return "item";
        }
        return entity.getType().getCategory() == MobCategory.MISC ? "other" : "passive";
    }

    private static Sighting sighting(Entity entity, Vec3 center) {
        String name = entity instanceof Player || entity.hasCustomName() ? entity.getName().getString() : null;
        if (name != null && name.length() > 64) {
            name = name.substring(0, Character.isHighSurrogate(name.charAt(63)) ? 63 : 64);
        }
        var position = entity.blockPosition();
        double distance = Math.round(Math.sqrt(entity.distanceToSqr(center)) * 100) / 100.0;
        String label = entity.getName().getString();
        if (label.length() > 64) {
            label = label.substring(0, Character.isHighSurrogate(label.charAt(63)) ? 63 : 64);
        }
        return new Sighting(EntityType.getKey(entity.getType()).toString(), category(entity), name,
                position.getX(), position.getY(), position.getZ(), distance, entity.getUUID(), label);
    }
}
