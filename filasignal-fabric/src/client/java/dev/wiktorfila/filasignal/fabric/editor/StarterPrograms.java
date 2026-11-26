package dev.wiktorfila.filasignal.fabric.editor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Short, editable starter programs bundled with the mod. They are the repository's verified {@code starter-*.fsig}
 * examples. The first line of each names it and says where it runs, such as
 * {@code // Starter: Intruder alarm (computer)}. A drive program's second line names its device kind, such as
 * {@code // Device: entity_detector}, so the editor offers it only where it can run.
 */
final class StarterPrograms {
    /** A starter. {@code device} is a device kind ID for drive programs, or empty for computer programs. */
    record Starter(String title, String device, String source) {
        /** The device title from a title such as "Visitor counter (Entity Detector drive)". */
        String deviceTitle() {
            int open = title.lastIndexOf('(');
            String where = open < 0 ? "" : title.substring(open + 1).replace(")", "");
            return where.endsWith(" drive") ? where.substring(0, where.length() - " drive".length()) : where;
        }
    }

    private static final List<String> FILES = List.of("starter-intruder-alarm.fsig", "starter-night-lights.fsig",
            "starter-panel-alarm.fsig", "starter-weather-board.fsig", "starter-low-stock.fsig", "starter-door-log.fsig",
            "starter-visitor-counter.fsig", "starter-guard-alarm.fsig");
    private static List<Starter> loaded;

    private StarterPrograms() {}

    static synchronized List<Starter> all() {
        if (loaded == null) {
            var starters = new ArrayList<Starter>();
            for (String file : FILES) {
                try (InputStream in = StarterPrograms.class.getResourceAsStream("/assets/filasignal/starters/" + file)) {
                    if (in == null) {
                        continue;
                    }
                    String source = new String(in.readNBytes(65_536), StandardCharsets.UTF_8).replace("\r\n", "\n");
                    List<String> lines = source.lines().limit(2).toList();
                    String first = lines.isEmpty() ? "" : lines.getFirst();
                    String title = first.startsWith("// Starter: ") ? first.substring("// Starter: ".length()) : file;
                    String device = lines.size() > 1 && lines.get(1).startsWith("// Device: ")
                            ? lines.get(1).substring("// Device: ".length()).strip() : "";
                    starters.add(new Starter(title, device, source));
                } catch (IOException ignored) {
                    // A missing starter is simply not offered.
                }
            }
            loaded = List.copyOf(starters);
        }
        return loaded;
    }

    /**
     * Starters that can run where the editor is pointed. The computer gets computer starters. A drive gets the
     * starters for its device, recognized from the editor label, which names the device or the drive's kind ID.
     * If nothing matches, every drive starter is offered.
     */
    static List<Starter> forTarget(boolean computer, String label) {
        if (computer) {
            return all().stream().filter(starter -> starter.device().isEmpty()).toList();
        }
        var drives = all().stream().filter(starter -> !starter.device().isEmpty()).toList();
        var matching = drives.stream().filter(starter -> label.contains(starter.device())
                || (!starter.deviceTitle().isEmpty() && label.contains(starter.deviceTitle()))).toList();
        return matching.isEmpty() ? drives : matching;
    }
}
