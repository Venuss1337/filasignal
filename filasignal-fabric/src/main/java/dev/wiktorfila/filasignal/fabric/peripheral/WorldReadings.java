package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detached weather and environment readings for loaded devices. All values come directly from Minecraft state.
 * Nothing is forecast or converted to physical units. Server-thread only.
 */
final class WorldReadings {
    private WorldReadings() {}

    /** True when the dimension has a sky without a ceiling, so rain and thunder can apply there. */
    static boolean hasWeather(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /**
     * World weather and time at a weather station. {@code raining} and {@code thundering} describe the dimension's
     * global weather. {@code precipitation} and {@code wet} describe the station's own position.
     */
    static DictValue weather(ServerLevel level, BlockPos station) {
        BlockPos above = above(level, station);
        boolean weather = hasWeather(level);
        boolean raining = weather && level.isRaining();
        boolean thundering = weather && level.isThundering();
        var values = new LinkedHashMap<String, Value>();
        values.put("dimension", new Text(level.dimension().identifier().toString()));
        values.put("hasWeather", new Bool(weather));
        values.put("raining", new Bool(raining));
        values.put("thundering", new Bool(thundering));
        values.put("clear", new Bool(!raining));
        values.put("rainLevel", new Num(round(weather ? level.getRainLevel(1f) : 0)));
        values.put("thunderLevel", new Num(round(weather ? level.getThunderLevel(1f) : 0)));
        values.put("precipitation", new Text(precipitation(level, above)));
        values.put("wet", new Bool(level.isRainingAt(above)));
        values.putAll(timeValues(level));
        return new DictValue(values);
    }

    /**
     * Time of day. {@code dayTime} runs from 0 to 23,999 where 0 is sunrise and 6,000 is noon. A dimension with
     * fixed time reports {@code fixedTime} true.
     */
    static Map<String, Value> timeValues(ServerLevel level) {
        long clock = level.getDefaultClockTime();
        var values = new LinkedHashMap<String, Value>();
        values.put("dayTime", new Num(Math.floorMod(clock, 24_000L)));
        values.put("day", new Num(Math.floorDiv(clock, 24_000L)));
        values.put("gameTime", new Num(level.getLevelData().getGameTime()));
        values.put("isDay", new Bool(level.isBrightOutside()));
        values.put("isNight", new Bool(level.isDarkOutside()));
        values.put("fixedTime", new Bool(level.dimensionType().hasFixedTime()));
        return values;
    }

    static DictValue time(ServerLevel level) {
        return new DictValue(timeValues(level));
    }

    /**
     * Local conditions in the open space directly above an environment sensor. Light levels are 0–15. Biome
     * temperature is Minecraft's unitless climate value (about -0.7 to 2.0), not degrees.
     */
    static DictValue environment(ServerLevel level, BlockPos sensor) {
        BlockPos above = above(level, sensor);
        var biome = level.getBiome(above);
        var values = new LinkedHashMap<String, Value>();
        values.put("blockLight", new Num(level.getBrightness(LightLayer.BLOCK, above)));
        values.put("skyLight", new Num(level.getBrightness(LightLayer.SKY, above)));
        values.put("light", new Num(level.getMaxLocalRawBrightness(above)));
        values.put("skyVisible", new Bool(level.canSeeSky(above)));
        values.put("biome", new Text(biomeId(biome)));
        values.put("temperature", new Num(round(biome.value().getBaseTemperature())));
        values.put("precipitation", new Text(precipitation(level, above)));
        values.put("x", new Num(sensor.getX()));
        values.put("y", new Num(sensor.getY()));
        values.put("z", new Num(sensor.getZ()));
        values.put("aboveSeaLevel", new Num(sensor.getY() - level.getSeaLevel()));
        values.put("dimension", new Text(level.dimension().identifier().toString()));
        return new DictValue(values);
    }

    static String biomeId(net.minecraft.core.Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
    }

    /** "none", "rain" or "snow" for the biome at a position, ignoring whether it is currently raining. */
    static String precipitation(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()).name().toLowerCase(Locale.ROOT);
    }

    private static BlockPos above(ServerLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        return level.isOutsideBuildHeight(above) ? pos : above;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
