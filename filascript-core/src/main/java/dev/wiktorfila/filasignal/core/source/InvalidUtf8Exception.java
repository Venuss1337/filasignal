package dev.wiktorfila.filasignal.core.source;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;

/** A decoding failure located in the original bytes, before a source span can exist. */
public final class InvalidUtf8Exception extends IOException {
    private static final long serialVersionUID = 1L;

    private final String sourceId;
    private final String filename;
    private final int byteOffset;

    InvalidUtf8Exception(String sourceId, String filename, int byteOffset, CharacterCodingException cause) {
        super("Invalid UTF-8 in " + filename + " at byte offset " + byteOffset, cause);
        this.sourceId = sourceId;
        this.filename = filename;
        this.byteOffset = byteOffset;
    }

    public String sourceId() {
        return sourceId;
    }

    public String filename() {
        return filename;
    }

    public int byteOffset() {
        return byteOffset;
    }
}
