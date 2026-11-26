package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Timed redstone outputs, such as an Access Controller holding a door open. Only open devices are visited. An open
 * output does not survive unloading: the device's outputs are cleared when it unloads. Server-thread only.
 */
final class Pulses {
    static final int MAX_OPEN = 256;
    private final Map<PeripheralEntity, Long> open = new LinkedHashMap<>();

    boolean open(PeripheralEntity device) {
        return open.containsKey(device);
    }

    /** Powers the device's output faces until the given tick, renewing an earlier pulse. */
    void open(PeripheralEntity device, ServerLevel level, long until) {
        if (!open.containsKey(device) && open.size() >= MAX_OPEN) {
            throw new HostFailure("access.limit", "Too many outputs are open on this server. Try again in a moment.");
        }
        open.put(device, until);
        for (Direction face : device.pulseFaces()) {
            device.output(level, face, 15);
        }
    }

    void close(PeripheralEntity device, ServerLevel level) {
        if (open.remove(device) != null) {
            device.clearOutputs(level);
        }
    }

    void forget(PeripheralEntity device) {
        open.remove(device);
    }

    void tick(long now) {
        var entries = open.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            PeripheralEntity device = entry.getKey();
            if (device.isRemoved() || !(device.getLevel() instanceof ServerLevel level)) {
                entries.remove();
            } else if (now >= entry.getValue()) {
                entries.remove();
                device.clearOutputs(level);
            }
        }
    }
}
