package dev.wiktorfila.filasignal.core.runtime.debug;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded history of program output, runtime errors and lifecycle messages for one program.
 * Single-owner and not thread-safe. Old entries are discarded first and long lines are shortened.
 */
public final class ProgramConsole {
    public static final int CAPACITY = 48;
    public static final int MAX_LINE = 200;

    public enum Kind { OUTPUT, ERROR, LIFECYCLE }

    public record Entry(long sequence, Kind kind, String text) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long sequence;

    public void add(Kind kind, String text) {
        Objects.requireNonNull(kind);
        String line = text.replace('\n', ' ').replace('\r', ' ');
        if (line.length() > MAX_LINE) {
            int end = MAX_LINE - 1;
            if (Character.isHighSurrogate(line.charAt(end - 1))) {
                end--;
            }
            line = line.substring(0, end) + "…";
        }
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(++sequence, kind, line));
    }

    /** Oldest first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Increases whenever an entry is added. Useful for change detection. */
    public long sequence() {
        return sequence;
    }

    public String latest(Kind kind) {
        var iterator = entries.descendingIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.kind() == kind) {
                return entry.text();
            }
        }
        return "";
    }
}
