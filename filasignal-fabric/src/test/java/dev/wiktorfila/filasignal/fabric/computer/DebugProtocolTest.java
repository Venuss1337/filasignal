package dev.wiktorfila.filasignal.fabric.computer;

import dev.wiktorfila.filasignal.core.runtime.debug.ProgramConsole;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DebugProtocolTest {
    @Test
    void roundTripsAMaximalDebugViewWithinTheClientboundLimit() {
        var frames = Collections.nCopies(EditorNetwork.MAX_FRAMES, new EditorNetwork.Frame("ŝ".repeat(EditorNetwork.Frame.NAME), 1_000_000));
        var variables = Collections.nCopies(EditorNetwork.MAX_VARIABLES, new EditorNetwork.Variable("global",
                "n".repeat(EditorNetwork.Variable.NAME), "ࠀ".repeat(EditorNetwork.Variable.VALUE)));
        var console = Collections.nCopies(EditorNetwork.MAX_CONSOLE, new EditorNetwork.ConsoleLine(1, "🚀".repeat(ProgramConsole.MAX_LINE / 2)));
        var breakpoints = new ArrayList<Integer>();
        for (int line = 1; line <= EditorNetwork.MAX_BREAKPOINTS; line++) {
            breakpoints.add(line * 1000);
        }
        var state = new EditorNetwork.DebugState(BlockPos.ZERO, UUID.randomUUID(), true, "m".repeat(512),
                new EditorNetwork.Status(EditorNetwork.DebugState.PAUSED, "h".repeat(EditorNetwork.STATE), "f".repeat(512), 9, 4,
                        frames, "s".repeat(512)),
                new EditorNetwork.Pause(9, 4, "breakpoint", frames, variables), breakpoints, console);
        var buffer = Unpooled.buffer();
        try {
            EditorNetwork.DebugState.CODEC.encode(buffer, state);
            assertTrue(buffer.readableBytes() < 1_048_576, "Debug view exceeds the clientbound payload limit");
            assertEquals(state, EditorNetwork.DebugState.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedListsWhenDecoding() {
        var buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).encode(buffer, Collections.nCopies(EditorNetwork.MAX_BREAKPOINTS + 1, 1));
            assertThrows(DecoderException.class,
                    () -> ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(EditorNetwork.MAX_BREAKPOINTS)).decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void parsesOnlyBoundedBreakpointLineLists() {
        assertEquals(List.of(), ProgramDebugging.breakpoints(""));
        assertEquals(List.of(3, 12, 3), ProgramDebugging.breakpoints("3, 12,3"));
        assertThrows(IllegalArgumentException.class, () -> ProgramDebugging.breakpoints("1,-2"));
        assertThrows(IllegalArgumentException.class, () -> ProgramDebugging.breakpoints("1,,2"));
        assertThrows(IllegalArgumentException.class, () -> ProgramDebugging.breakpoints("99999999"));
        assertThrows(IllegalArgumentException.class, () -> ProgramDebugging.breakpoints("1,".repeat(70) + "1"));
    }

    @Test
    void shortensWithoutSplittingSurrogates() {
        assertEquals("abc", EditorNetwork.shorten("abc", 8));
        String shortened = EditorNetwork.shorten("a" + "🚀".repeat(10), 6);
        assertTrue(shortened.length() <= 6);
        assertFalse(Character.isHighSurrogate(shortened.charAt(shortened.length() - 2)), shortened);
    }

    @Test
    void throttlesServerLogMirroringPerWindow() {
        var status = new ComputerStatus();
        int logged = 0;
        String notice = null;
        for (int i = 0; i < 50; i++) {
            String line = status.serverLogLine(100, "line " + i);
            if (line != null && line.startsWith("line")) {
                logged++;
            } else if (line != null) {
                notice = line;
            }
        }
        assertEquals(20, logged);
        assertNotNull(notice);
        assertEquals("next", status.serverLogLine(1300, "next"));
    }
}
