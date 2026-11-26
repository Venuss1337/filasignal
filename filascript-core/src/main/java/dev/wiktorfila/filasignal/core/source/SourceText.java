package dev.wiktorfila.filasignal.core.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable source with UTF-16 offsets and one-based Unicode code point positions. */
public final class SourceText {
    private final String id;
    private final String filename;
    private final String text;
    private final List<Integer> lineStarts = new ArrayList<>();
    private final List<Integer> lowSurrogates = new ArrayList<>();

    public SourceText(String id, String filename, String text) {
        this.id = Objects.requireNonNull(id);
        this.filename = Objects.requireNonNull(filename);
        this.text = text.startsWith("\uFEFF") ? text.substring(1) : text;

        lineStarts.add(0);
        for (int i = 0; i < this.text.length(); i++) {
            char character = this.text.charAt(i);
            if (character == '\n') {
                lineStarts.add(i + 1);
            } else if (Character.isHighSurrogate(character)) {
                if (i + 1 == this.text.length() || !Character.isLowSurrogate(this.text.charAt(i + 1))) {
                    throw new IllegalArgumentException("Unpaired surrogate at UTF-16 offset " + i);
                }
                lowSurrogates.add(++i);
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Unpaired surrogate at UTF-16 offset " + i);
            }
        }
    }

    public static SourceText read(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return fromUtf8(absolute.toUri().toString(), absolute.getFileName().toString(), Files.readAllBytes(absolute));
    }

    public static SourceText fromUtf8(String id, String filename, byte[] bytes) throws InvalidUtf8Exception {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        try {
            return new SourceText(id, filename, StandardCharsets.UTF_8.newDecoder().decode(input).toString());
        } catch (CharacterCodingException cause) {
            throw new InvalidUtf8Exception(id, filename, input.position(), cause);
        }
    }

    public String id() {
        return id;
    }

    public String filename() {
        return filename;
    }

    public String text() {
        return text;
    }

    public SourcePosition positionAt(int offset) {
        requireBoundary(offset);
        int line = insertionPoint(lineStarts, offset + 1) - 1;
        int start = lineStarts.get(line);
        int supplementary = insertionPoint(lowSurrogates, offset) - insertionPoint(lowSurrogates, start);
        return new SourcePosition(line + 1, offset - start - supplementary + 1);
    }

    void requireBoundary(int offset) {
        if (offset < 0 || offset > text.length()
                || (offset < text.length() && Character.isLowSurrogate(text.charAt(offset)))) {
            throw new IllegalArgumentException("Invalid UTF-16 boundary: " + offset);
        }
    }

    private static int insertionPoint(List<Integer> offsets, int target) {
        int index = Collections.binarySearch(offsets, target);
        return index >= 0 ? index : -index - 1;
    }
}
