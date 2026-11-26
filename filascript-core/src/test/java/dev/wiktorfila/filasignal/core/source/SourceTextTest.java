package dev.wiktorfila.filasignal.core.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceTextTest {
    @TempDir
    Path directory;

    @Test
    void readsStrictUtf8AndPreservesSourceIdentityAndLineEndings() throws Exception {
        Path path = directory.resolve("żółć.fsig");
        Files.writeString(path, "\uFEFFa😀\r\n\tz\u0301\n");
        SourceText source = SourceText.read(path);
        assertEquals(path.toUri().toString(), source.id());
        assertEquals("żółć.fsig", source.filename());
        assertEquals("a😀\r\n\tz\u0301\n", source.text());
        assertEquals(new SourcePosition(1, 1), source.positionAt(0));
        assertEquals(new SourcePosition(1, 2), source.positionAt(1));
        assertEquals(new SourcePosition(1, 3), source.positionAt(3));
        assertEquals(new SourcePosition(2, 1), source.positionAt(5));
        assertEquals(new SourcePosition(2, 4), source.positionAt(8));
        assertEquals(new SourcePosition(3, 1), source.positionAt(9));

        SourceSpan newline = new SourceSpan(source, 3, 5);
        assertEquals("\r\n", newline.text());
        assertEquals(new SourcePosition(1, 3), newline.start());
        assertEquals(new SourcePosition(2, 1), newline.end());
        assertThrows(IllegalArgumentException.class, () -> source.positionAt(2));
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(source, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(source, 5, 1));
        assertThrows(IllegalArgumentException.class, () -> source.positionAt(10));
    }

    @Test
    void reportsMalformedAndTruncatedUtf8InOriginalBytes() throws Exception {
        Path path = directory.resolve("bad.fsig");
        Files.write(path, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf,
                'x', (byte) 0xe2, (byte) 0x82});
        InvalidUtf8Exception fault = assertThrows(InvalidUtf8Exception.class, () -> SourceText.read(path));
        assertEquals(4, fault.byteOffset());
        assertEquals("bad.fsig", fault.filename());
        assertEquals(path.toUri().toString(), fault.sourceId());

        byte[][] invalidSequences = {
                {(byte) 0xc0, (byte) 0xaf}, // Overlong
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, // Surrogate
                {(byte) 0x80}, // Stray continuation
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80} // Beyond U+10FFFF
        };
        for (byte[] bytes : invalidSequences) {
            assertEquals(0, assertThrows(InvalidUtf8Exception.class,
                    () -> SourceText.fromUtf8("id", "bad.fsig", bytes)).byteOffset());
        }
    }

    @Test
    void stripsOneBomAndRejectsUnpairedUtf16Surrogates() throws Exception {
        byte[] bytes = "\uFEFF\uFEFFx".getBytes(StandardCharsets.UTF_8);
        assertEquals("\uFEFFx", SourceText.fromUtf8("id", "input.fsig", bytes).text());
        assertThrows(IllegalArgumentException.class, () -> new SourceText("id", "file", "\uD800x"));
        assertThrows(IllegalArgumentException.class, () -> new SourceText("id", "file", "x\uDC00"));
        assertEquals(new SourcePosition(1, 1), new SourceText("id", "file", "\uFEFF").positionAt(0));
    }
}
