package dev.wiktorfila.filasignal.fabric.peripheral;

import java.util.ArrayList;
import java.util.List;

/** Validation for display module text: at most {@value #MAX_LINES} lines of {@value #MAX_COLUMNS} characters. */
final class DisplayText {
    static final int MAX_LINES = 8;
    static final int MAX_COLUMNS = 48;
    static final int MAX_UNITS = MAX_LINES * (MAX_COLUMNS + 1);

    private DisplayText() {}

    /**
     * Splits text into display lines. CRLF and CR become line breaks and other control characters become spaces.
     *
     * @throws HostFailure {@code argument.range} when the text has too many lines or a line is too long
     */
    static List<String> lines(String text) {
        if (text.length() > MAX_UNITS * 2) {
            throw new HostFailure("argument.range", "Display text is limited to " + MAX_LINES + " lines of "
                    + MAX_COLUMNS + " characters");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (String line : normalized.split("\n", -1)) {
            result.add(line(line));
        }
        if (result.size() > MAX_LINES) {
            throw new HostFailure("argument.range", "Display text is limited to " + MAX_LINES + " lines");
        }
        return List.copyOf(result);
    }

    /** Validates one line, replacing control characters. */
    static String line(String line) {
        if (line.codePointCount(0, line.length()) > MAX_COLUMNS) {
            throw new HostFailure("argument.range", "Display lines are limited to " + MAX_COLUMNS + " characters");
        }
        var cleaned = new StringBuilder(line.length());
        line.codePoints().forEach(cp -> cleaned.appendCodePoint(Character.isISOControl(cp) || (cp >= 0xd800 && cp <= 0xdfff) ? ' ' : cp));
        return cleaned.toString();
    }
}
