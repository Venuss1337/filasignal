package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.EntityDetection.Sighting;
import dev.wiktorfila.filasignal.fabric.peripheral.NetworkDirectory.Node;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic observations behind native events for hardware that has no world callback: entity detectors, weather
 * stations, environment sensors and inventory modules. A device is observed only while some program listens to one
 * of its events, at most {@value #VISITS_PER_TICK} devices per tick, and each kind on a fixed interval. An event is
 * an observed change between two samples, not a guarantee that every movement was seen. Server-thread only.
 */
final class DeviceObservers {
    static final int DETECTOR_INTERVAL = 10;
    static final int SLOW_INTERVAL = 20;
    static final int VISITS_PER_TICK = 32;
    static final int MAX_ITEM_EVENTS = 8;
    static final Set<String> DETECTOR_EVENTS = Set.of("entityEnter", "entityLeave", "countChanged");
    static final Set<String> WEATHER_EVENTS = Set.of("rainStart", "rainStop", "thunderStart", "thunderStop", "dayStart",
            "nightStart", "weatherChanged");
    static final Set<String> ENVIRONMENT_EVENTS = Set.of("lightChanged", "skyLightChanged", "darkened", "brightened");
    static final Set<String> INVENTORY_EVENTS = Set.of("contentsChanged", "itemChanged");

    private final PeripheralRuntime runtime;
    private final EventHub hub;
    private final Map<UUID, Long> visited = new HashMap<>();
    private final Map<UUID, Map<UUID, DictValue>> present = new HashMap<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private final Map<UUID, int[]> light = new HashMap<>();
    private final Map<UUID, Map<String, Map<String, Integer>>> inventories = new HashMap<>();
    private final Map<ServerLevel, WeatherState> weather = new java.util.IdentityHashMap<>();
    private int cursor;

    private record WeatherState(long tick, boolean raining, boolean thundering, boolean day) {
        Value kind() {
            return new EnumMember("Weather", thundering ? "THUNDER" : raining ? "RAIN" : "CLEAR");
        }
    }

    DeviceObservers(PeripheralRuntime runtime, EventHub hub) {
        this.runtime = runtime;
        this.hub = hub;
    }

    /** Drops a device's baselines, for example when it unloads, so a later sample cannot report stale changes. */
    void forget(UUID device) {
        visited.remove(device);
        present.remove(device);
        counts.remove(device);
        light.remove(device);
        inventories.remove(device);
    }

    void tick(long now) {
        var watched = new ArrayList<>(hub.watched());
        // Baselines of devices nobody listens to any more are discarded.
        visited.keySet().retainAll(watched);
        present.keySet().retainAll(watched);
        counts.keySet().retainAll(watched);
        light.keySet().retainAll(watched);
        inventories.keySet().retainAll(watched);
        weather.keySet().removeIf(level -> now - weather.get(level).tick() > 200);
        if (watched.isEmpty()) {
            return;
        }
        for (int i = 0; i < Math.min(VISITS_PER_TICK, watched.size()); i++) {
            cursor = Math.floorMod(cursor, watched.size());
            UUID id = watched.get(cursor++);
            if (!(runtime.live(id) instanceof PeripheralEntity device) || !(device.getLevel() instanceof ServerLevel level)) {
                forget(id);
                continue;
            }
            Kind kind = device.kind();
            int interval = kind == Kind.ENTITY_DETECTOR ? DETECTOR_INTERVAL : SLOW_INTERVAL;
            Long last = visited.get(id);
            if (last != null && now - last < interval) {
                continue;
            }
            visited.put(id, now);
            switch (kind) {
                case ENTITY_DETECTOR -> {
                    if (hub.wantsAny(id, DETECTOR_EVENTS)) {
                        detector(id, device, level, now);
                    }
                }
                case WEATHER_STATION -> {
                    if (hub.wantsAny(id, WEATHER_EVENTS)) {
                        weather(id, level, now);
                    }
                }
                case ENVIRONMENT_SENSOR -> {
                    if (hub.wantsAny(id, ENVIRONMENT_EVENTS)) {
                        environment(id, device, level);
                    }
                }
                case INVENTORY_MODULE -> {
                    if (hub.wantsAny(id, INVENTORY_EVENTS)) {
                        inventory(id);
                    }
                }
                default -> { }
            }
        }
    }

    // Entity detector

    private void detector(UUID id, PeripheralEntity device, ServerLevel level, long now) {
        EntityDetection.Scan scan;
        try {
            scan = runtime.detection().scan(device, level, now);
        } catch (HostFailure busy) {
            // Out of scan budget this tick; try again soon without losing the baseline.
            visited.remove(id);
            return;
        }
        var filter = EntityDetection.filter(device.detectFilter());
        var current = new LinkedHashMap<UUID, DictValue>();
        for (Sighting sighting : scan.sightings()) {
            if (filter.test(sighting)) {
                current.put(sighting.uuid(), entity(sighting));
            }
        }
        Map<UUID, DictValue> previous = present.get(id);
        if (previous == null) {
            // The first sample only sets the baseline.
            present.put(id, current);
            if (!scan.truncated()) {
                counts.put(id, current.size());
            }
            return;
        }
        int entered = 0;
        for (var entry : current.entrySet()) {
            if (!previous.containsKey(entry.getKey()) && entered++ < 16) {
                hub.publish(id, "entityEnter", List.of(entry.getValue()));
            }
        }
        if (scan.truncated()) {
            // Entities missing from an incomplete scan may still be there, so nobody is reported as leaving and
            // the count is not reported. Known entities stay known until a complete scan.
            var merged = new LinkedHashMap<>(previous);
            merged.putAll(current);
            present.put(id, merged.size() > EntityDetection.MAX_EXAMINED ? current : merged);
            return;
        }
        int left = 0;
        for (var entry : previous.entrySet()) {
            if (!current.containsKey(entry.getKey()) && left++ < 16) {
                hub.publish(id, "entityLeave", List.of(entry.getValue()));
            }
        }
        present.put(id, current);
        Integer before = counts.put(id, current.size());
        if (before != null && before != current.size()) {
            hub.publish(id, "countChanged", List.of(new Num(current.size()), new Num(before)));
        }
    }

    /** The beginner-facing entity description delivered with detector events. */
    static DictValue entity(Sighting sighting) {
        var values = new LinkedHashMap<String, Value>();
        values.put("name", new Text(sighting.label()));
        values.put("type", new Text(sighting.type()));
        values.put("category", new EnumMember("Entity", sighting.category().toUpperCase(java.util.Locale.ROOT)));
        values.put("hostile", new Bool(sighting.category().equals("hostile")));
        values.put("player", new Bool(sighting.category().equals("player")));
        values.put("x", new Num(sighting.x()));
        values.put("y", new Num(sighting.y()));
        values.put("z", new Num(sighting.z()));
        values.put("distance", new Num(sighting.distance()));
        if (sighting.category().equals("player")) {
            values.put("uuid", new Text(sighting.uuid().toString()));
        }
        return new DictValue(values);
    }

    // Weather station: one observation per level is shared by every station in it.

    private void weather(UUID id, ServerLevel level, long now) {
        WeatherState prior = weather.get(level);
        WeatherState state = prior != null && prior.tick() == now ? prior : sample(level, now);
        weather.put(level, state);
        if (prior == null || prior.tick() == now) {
            return;
        }
        // Each level's transition is published once to every interested station in it.
        for (UUID station : List.copyOf(hub.watched())) {
            if (!(runtime.live(station) instanceof PeripheralEntity device) || device.kind() != Kind.WEATHER_STATION
                    || device.getLevel() != level) {
                continue;
            }
            visited.put(station, now);
            if (state.raining() != prior.raining()) {
                hub.publish(station, state.raining() ? "rainStart" : "rainStop", List.of());
            }
            if (state.thundering() != prior.thundering()) {
                hub.publish(station, state.thundering() ? "thunderStart" : "thunderStop", List.of());
            }
            if (state.day() != prior.day()) {
                hub.publish(station, state.day() ? "dayStart" : "nightStart", List.of());
            }
            if (!state.kind().equals(prior.kind())) {
                hub.publish(station, "weatherChanged", List.of(state.kind(), prior.kind()));
            }
        }
    }

    private static WeatherState sample(ServerLevel level, long now) {
        boolean weather = WorldReadings.hasWeather(level);
        var time = WorldReadings.time(level);
        return new WeatherState(now, weather && level.isRaining(), weather && level.isThundering(),
                time.get("isDay") instanceof Bool day && day.value());
    }

    /** The current weather as a Weather constant. */
    static Value weather(ServerLevel level) {
        return sample(level, 0).kind();
    }

    // Environment sensor

    private void environment(UUID id, PeripheralEntity device, ServerLevel level) {
        var above = device.getBlockPos().above();
        if (!LoadedChunks.contains(level, above)) {
            return;
        }
        int current = level.getMaxLocalRawBrightness(above);
        int sky = level.getBrightness(LightLayer.SKY, above);
        int[] prior = light.put(id, new int[]{current, sky});
        if (prior == null) {
            return;
        }
        int threshold = device.threshold();
        if (current != prior[0]) {
            hub.publish(id, "lightChanged", List.of(new Num(current), new Num(prior[0])));
            if (current < threshold && prior[0] >= threshold) {
                hub.publish(id, "darkened", List.of(new Num(current)));
            } else if (current >= threshold && prior[0] < threshold) {
                hub.publish(id, "brightened", List.of(new Num(current)));
            }
        }
        if (sky != prior[1]) {
            hub.publish(id, "skyLightChanged", List.of(new Num(sky), new Num(prior[1])));
        }
    }

    // Inventory module: totals per approved face, compared item by item.

    private void inventory(UUID id) {
        Node node = runtime.directory().node(id);
        if (node == null || node.network() == null) {
            return;
        }
        var service = new InventoryService(runtime);
        var faces = new LinkedHashMap<String, Map<String, Integer>>();
        for (var binding : runtime.directory().inventories().all()) {
            if (!binding.interfaceId().equals(id)) {
                continue;
            }
            try {
                var open = service.open(node.network(), binding, false, false);
                var totals = new LinkedHashMap<String, Integer>();
                for (int slot = 0; slot < open.slots(); slot++) {
                    var storage = open.storage().getSlot(slot);
                    if (!storage.isResourceBlank() && storage.getAmount() > 0) {
                        String item = BuiltInRegistries.ITEM.getKey(storage.getResource().getItem()).toString();
                        totals.merge(item, (int) Math.min(Integer.MAX_VALUE, storage.getAmount()), Integer::sum);
                    }
                }
                faces.put(binding.face().getSerializedName(), totals);
            } catch (HostFailure unavailable) {
                // An unreadable face keeps its last totals until it can be read again.
                var known = inventories.get(id);
                if (known != null && known.containsKey(binding.face().getSerializedName())) {
                    faces.put(binding.face().getSerializedName(), known.get(binding.face().getSerializedName()));
                }
            }
        }
        var prior = inventories.put(id, faces);
        if (prior == null) {
            return;
        }
        int itemEvents = 0;
        for (var face : faces.entrySet()) {
            var before = prior.get(face.getKey());
            if (before == null || before.equals(face.getValue())) {
                continue;
            }
            Value direction = new EnumMember("Direction", face.getKey().toUpperCase(java.util.Locale.ROOT));
            hub.publish(id, "contentsChanged", List.of(direction));
            var items = new java.util.TreeSet<>(before.keySet());
            items.addAll(face.getValue().keySet());
            for (String item : items) {
                int was = before.getOrDefault(item, 0);
                int now = face.getValue().getOrDefault(item, 0);
                if (was != now && itemEvents++ < MAX_ITEM_EVENTS) {
                    hub.publish(id, "itemChanged", List.of(direction, new Text(item), new Num(now), new Num(was)));
                }
            }
        }
    }
}
