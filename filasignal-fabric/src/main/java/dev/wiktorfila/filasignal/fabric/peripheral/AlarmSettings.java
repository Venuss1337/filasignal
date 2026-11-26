package dev.wiktorfila.filasignal.fabric.peripheral;

import java.util.List;

/**
 * Bounded alarm configuration. Sounds are a fixed set of vanilla sound events, volume is a percentage of normal
 * block volume and duration is the number of seconds an activation lasts unless renewed or deactivated.
 */
record AlarmSettings(String sound, int volume, int seconds) {
    static final List<String> SOUNDS = List.of("bell", "pling", "chime", "bass");
    static final AlarmSettings DEFAULT = new AlarmSettings("bell", 60, 10);

    AlarmSettings {
        if (!SOUNDS.contains(sound) || volume < 10 || volume > 100 || seconds < 1 || seconds > 60) {
            throw new HostFailure("argument.range",
                    "Alarm sound must be bell, pling, chime or bass, volume 10–100 and duration 1–60 seconds");
        }
    }

    /** Lenient decoding for saved data. Invalid saved values fall back to the defaults. */
    static AlarmSettings parse(String sound, int volume, int seconds) {
        try {
            return new AlarmSettings(sound, volume, seconds);
        } catch (HostFailure invalid) {
            return DEFAULT;
        }
    }
}
