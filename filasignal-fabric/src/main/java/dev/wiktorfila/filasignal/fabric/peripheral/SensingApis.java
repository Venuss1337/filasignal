package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.Context;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Type;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.EntityDetection.Sighting;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Spec;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.method;
import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.slot;

/** Entity detector, weather station and environment sensor objects. All are read-only. */
final class SensingApis {
    static final String DETECTOR = "detector.read";
    static final String WEATHER = "weather.read";
    static final String ENVIRONMENT = "environment.read";
    private static final Set<String> SCAN_ERRORS = Set.of("argument.invalid", "detector.busy");
    private static final String FILTERS = " Filters: Entity.ALL, Entity.PLAYER, Entity.HOSTILE, Entity.PASSIVE, Entity.ITEM, Entity.OTHER or an entity type ID such as \"minecraft:zombie\".";

    private SensingApis() {}

    static List<Spec> specs(HardwareHosts hosts) {
        return List.of(detector(hosts), weather(), environment());
    }

    private static Spec detector(HardwareHosts hosts) {
        return new Spec(Kind.ENTITY_DETECTOR, "EntityDetector", "detectors.from", DETECTOR,
                "Resolve an entity detector by name.", "gate, err = detectors.from(\"gate\")", List.of(
                method("scan", Type.LIST, "List up to 32 matching entities, nearest first, with type, category, "
                                + "x, y, z, distance and name for players or named entities." + FILTERS, DETECTOR, 32,
                        "hostiles, err = gate.scan(\"hostile\")", SCAN_ERRORS, (target, args, ctx) -> {
                            var matches = matches(hosts, target, args.getFirst(), ctx);
                            var listed = new ArrayList<Value>();
                            for (int i = 0; i < Math.min(matches.size(), EntityDetection.MAX_RESULTS); i++) {
                                listed.add(snapshot(matches.get(i)));
                            }
                            return new ListValue(listed);
                        }, HardwareHosts.choice("filter")),
                method("count", Type.NUMBER, "Count matching entities within the radius. At most 256 entities are "
                                + "examined; use census to learn whether a count was cut short." + FILTERS,
                        DETECTOR, 32, "zombies, err = gate.count(\"minecraft:zombie\")", SCAN_ERRORS,
                        (target, args, ctx) -> new Num(matches(hosts, target, args.getFirst(), ctx).size()),
                        HardwareHosts.choice("filter")),
                method("nearest", Type.DICT, "Describe the nearest matching entity, or nil when none is present." + FILTERS,
                        DETECTOR, 32, "closest, err = gate.nearest(\"player\")", SCAN_ERRORS, (target, args, ctx) -> {
                            var matches = matches(hosts, target, args.getFirst(), ctx);
                            return matches.isEmpty() ? Nil.INSTANCE : snapshot(matches.getFirst());
                        }, HardwareHosts.choice("filter")),
                method("census", Type.DICT, "Count matches and say whether the scan was cut short: count, truncated "
                                + "(true when more than 256 entities were in range, so count is a lower bound) and nearest "
                                + "(a match or nil)." + FILTERS, DETECTOR, 32, "seen, err = gate.census(\"hostile\")", SCAN_ERRORS,
                        (target, args, ctx) -> {
                            var predicate = EntityDetection.filter(HardwareHosts.text(args.getFirst()));
                            var scan = hosts.runtime().detection().scan(target.device(), target.level(), hosts.runtime().tickCount());
                            ctx.charge(256 + 4L * scan.sightings().size());
                            var matches = scan.sightings().stream().filter(predicate).toList();
                            var values = new LinkedHashMap<String, Value>();
                            values.put("count", new Num(matches.size()));
                            values.put("truncated", new Bool(scan.truncated()));
                            values.put("nearest", matches.isEmpty() ? Nil.INSTANCE : snapshot(matches.getFirst()));
                            return new DictValue(values);
                        }, HardwareHosts.choice("filter")),
                method("radius", Type.NUMBER, "Read the configured detection radius in blocks (1–16).", DETECTOR, 4,
                        "radius, err = gate.radius()", (target, args, ctx) -> new Num(target.device().range()))));
    }

    private static List<Sighting> matches(HardwareHosts hosts, Target target, Value filter, Context context) {
        var predicate = EntityDetection.filter(HardwareHosts.text(filter));
        var scan = hosts.runtime().detection().scan(target.device(), target.level(), hosts.runtime().tickCount());
        // Charged whether or not the snapshot was cached, so a script cannot amplify scans by repeating queries.
        context.charge(256 + 4L * scan.sightings().size());
        return scan.sightings().stream().filter(predicate).toList();
    }

    private static DictValue snapshot(Sighting sighting) {
        var values = new LinkedHashMap<String, Value>();
        values.put("type", new Text(sighting.type()));
        values.put("category", new Text(sighting.category()));
        values.put("x", new Num(sighting.x()));
        values.put("y", new Num(sighting.y()));
        values.put("z", new Num(sighting.z()));
        values.put("distance", new Num(sighting.distance()));
        if (sighting.name() != null) {
            values.put("name", new Text(sighting.name()));
        }
        return new DictValue(values);
    }

    private static Spec weather() {
        return new Spec(Kind.WEATHER_STATION, "WeatherStation", "weather.from", WEATHER,
                "Resolve a weather station by name.", "station, err = weather.from(\"roof\")", List.of(
                method("read", Type.DICT, "Read global weather (raining, thundering, clear, rain and thunder levels), "
                                + "local precipitation and wet at the station, and time of day.", WEATHER, 32,
                        "now, err = station.read()", (target, args, ctx) -> WorldReadings.weather(target.level(), target.device().getBlockPos())),
                method("weather", Type.ENUM, "Read the weather in this dimension: Weather.CLEAR, Weather.RAIN or Weather.THUNDER. "
                                + "Use read() for rain at the station itself.", WEATHER, 8,
                        "if station.weather() is Weather.RAIN { print(\"Rain!\") }",
                        (target, args, ctx) -> DeviceObservers.weather(target.level())),
                method("raining", Type.BOOL, "True while it rains or snows in this dimension.", WEATHER, 8,
                        "wet, err = station.raining()", (target, args, ctx) ->
                                new Bool(WorldReadings.hasWeather(target.level()) && target.level().isRaining())),
                method("thundering", Type.BOOL, "True during a thunderstorm in this dimension.", WEATHER, 8,
                        "storm, err = station.thundering()", (target, args, ctx) ->
                                new Bool(WorldReadings.hasWeather(target.level()) && target.level().isThundering())),
                method("clear", Type.BOOL, "True when it is not raining in this dimension.", WEATHER, 8,
                        "sunny, err = station.clear()", (target, args, ctx) ->
                                new Bool(!WorldReadings.hasWeather(target.level()) || !target.level().isRaining())),
                method("time", Type.DICT, "Read dayTime (0–23999, 0 is sunrise), day, gameTime, isDay, isNight and fixedTime.",
                        WEATHER, 16, "clock, err = station.time()", (target, args, ctx) -> WorldReadings.time(target.level()))));
    }

    private static Spec environment() {
        return new Spec(Kind.ENVIRONMENT_SENSOR, "EnvironmentSensor", "environment.from", ENVIRONMENT,
                "Resolve an environment sensor by name.", "probe, err = environment.from(\"greenhouse\")", List.of(
                method("read", Type.DICT, "Read conditions in the space above the sensor: blockLight, skyLight, light, "
                                + "skyVisible, biome, temperature (unitless biome value), precipitation, position and "
                                + "aboveSeaLevel.", ENVIRONMENT, 32, "here, err = probe.read()",
                        (target, args, ctx) -> WorldReadings.environment(target.level(), target.device().getBlockPos())),
                method("light", Type.NUMBER, "Read the combined light level (0–15) above the sensor.", ENVIRONMENT, 8,
                        "light, err = probe.light()", (target, args, ctx) ->
                                new Num(target.level().getMaxLocalRawBrightness(target.device().getBlockPos().above()))),
                method("biome", Type.STRING, "Read the biome ID at the sensor.", ENVIRONMENT, 8, "biome, err = probe.biome()",
                        (target, args, ctx) -> new Text(WorldReadings.biomeId(target.level().getBiome(target.device().getBlockPos())))),
                method("skyVisible", Type.BOOL, "True when the space above the sensor can see the sky.", ENVIRONMENT, 8,
                        "open, err = probe.skyVisible()", (target, args, ctx) ->
                                new Bool(target.level().canSeeSky(target.device().getBlockPos().above())))));
    }
}
