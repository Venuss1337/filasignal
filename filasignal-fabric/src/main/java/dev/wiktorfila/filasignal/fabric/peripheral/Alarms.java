package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sounding alarms on one server. Only active alarms are visited, each plays at most one sound per
 * {@value #INTERVAL} ticks, and at most {@value #MAX_ACTIVE} alarms sound at once. Activation does not survive
 * unloading: an alarm found lit on load is switched off. Server-thread only.
 */
final class Alarms {
    static final int INTERVAL = 20;
    static final int MAX_ACTIVE = 64;

    private record Active(long until, long nextSound) {}

    private final Map<PeripheralEntity, Active> active = new LinkedHashMap<>();
    private final EventHub events;

    Alarms(EventHub events) {
        this.events = events;
    }

    private void publish(PeripheralEntity alarm, String event) {
        if (alarm.identity() != null) {
            events.publish(alarm.identity(), event, java.util.List.of());
        }
    }

    boolean active(PeripheralEntity alarm) {
        return active.containsKey(alarm);
    }

    /** Remaining ticks of an active alarm, or 0. */
    long remaining(PeripheralEntity alarm, long tick) {
        Active state = active.get(alarm);
        return state == null ? 0 : Math.max(0, state.until() - tick);
    }

    /** Starts or renews an alarm for its configured duration. The first sound plays immediately. */
    void activate(PeripheralEntity alarm, ServerLevel level, long tick) {
        Active prior = active.get(alarm);
        if (prior == null && active.size() >= MAX_ACTIVE) {
            throw new HostFailure("alarm.limit", "Too many alarms are sounding on this server");
        }
        long until = tick + alarm.alarm().seconds() * 20L;
        active.put(alarm, new Active(until, prior == null ? tick : prior.nextSound()));
        lit(alarm, level, true);
        if (prior == null) {
            publish(alarm, "activated");
        }
    }

    void deactivate(PeripheralEntity alarm, ServerLevel level) {
        if (active.remove(alarm) != null) {
            lit(alarm, level, false);
            publish(alarm, "deactivated");
        }
    }

    /** Drops an unloading alarm without touching the world. */
    void forget(PeripheralEntity alarm) {
        active.remove(alarm);
    }

    /** Turns off an alarm block that was saved while lit. */
    void loaded(PeripheralEntity alarm, ServerLevel level) {
        if (!active.containsKey(alarm)) {
            lit(alarm, level, false);
        }
    }

    void tick(long tick) {
        if (active.isEmpty()) {
            return;
        }
        var entries = active.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            PeripheralEntity alarm = entry.getKey();
            if (alarm.isRemoved() || !(alarm.getLevel() instanceof ServerLevel level)
                    || !LoadedChunks.contains(level, alarm.getBlockPos())) {
                entries.remove();
                continue;
            }
            Active state = entry.getValue();
            if (tick >= state.until()) {
                entries.remove();
                lit(alarm, level, false);
                publish(alarm, "finished");
            } else if (tick >= state.nextSound()) {
                var settings = alarm.alarm();
                level.playSound(null, alarm.getBlockPos(), sound(settings.sound()), SoundSource.BLOCKS,
                        settings.volume() / 100f, 1f);
                entry.setValue(new Active(state.until(), tick + INTERVAL));
            }
        }
    }

    private static void lit(PeripheralEntity alarm, ServerLevel level, boolean on) {
        var state = alarm.getBlockState();
        if (state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT) != on
                && LoadedChunks.contains(level, alarm.getBlockPos())) {
            level.setBlock(alarm.getBlockPos(), state.setValue(BlockStateProperties.LIT, on), Block.UPDATE_CLIENTS);
        }
    }

    private static SoundEvent sound(String name) {
        return switch (name) {
            case "pling" -> SoundEvents.NOTE_BLOCK_PLING.value();
            case "chime" -> SoundEvents.NOTE_BLOCK_CHIME.value();
            case "bass" -> SoundEvents.NOTE_BLOCK_BASS.value();
            default -> SoundEvents.BELL_BLOCK;
        };
    }
}
