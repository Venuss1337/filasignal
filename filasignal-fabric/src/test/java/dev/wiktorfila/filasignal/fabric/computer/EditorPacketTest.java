package dev.wiktorfila.filasignal.fabric.computer;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EditorPacketTest {
    @Test
    void roundTripsMaximumThreeByteSourceAndSupplementaryCharacters() {
        var source = "🚀" + "\u0800".repeat(ProgramEditing.MAX_SOURCE - 2);
        var request = new EditorNetwork.Request(BlockPos.ZERO, UUID.randomUUID(),
                EditorNetwork.Request.APPLY, UUID.randomUUID(), source);
        var buffer = Unpooled.buffer();
        try {
            EditorNetwork.Request.CODEC.encode(buffer, request);
            assertTrue(buffer.readableBytes() < 52_000);
            assertEquals(request, EditorNetwork.Request.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void openingReplyFitsMaximumSourceAndCatalog() {
        var lines = java.util.Collections.nCopies(2_000, "Device.example(argument)	Documentation ࠀ");
        String catalog = ProgramEditing.bounded(lines);
        assertTrue(catalog.length() <= ProgramEditing.MAX_CATALOG && catalog.endsWith("ࠀ"));
        var reply = new EditorNetwork.Reply(BlockPos.ZERO, UUID.randomUUID(), UUID.randomUUID(), true, true, true,
                "s".repeat(500), "ࠀ".repeat(ProgramEditing.MAX_SOURCE), catalog, "d".repeat(1024), "r".repeat(160));
        var buffer = Unpooled.buffer();
        try {
            EditorNetwork.Reply.CODEC.encode(buffer, reply);
            // Registered maximum for the clientbound reply payload.
            assertTrue(buffer.readableBytes() < 112_000, "Reply needs " + buffer.readableBytes() + " bytes");
            assertEquals(reply, EditorNetwork.Reply.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsMalformedUtf8AndOverlongDecodedSource() {
        assertInvalid(new byte[]{(byte) 0xc0, (byte) 0xaf});
        assertInvalid(new byte[ProgramEditing.MAX_SOURCE + 1]);
    }

    private static void assertInvalid(byte[] bytes) {
        var buffer = Unpooled.buffer();
        try {
            BlockPos.STREAM_CODEC.encode(buffer, BlockPos.ZERO);
            UUIDUtil.STREAM_CODEC.encode(buffer, UUID.randomUUID());
            ByteBufCodecs.VAR_INT.encode(buffer, EditorNetwork.Request.APPLY);
            UUIDUtil.STREAM_CODEC.encode(buffer, UUID.randomUUID());
            ByteBufCodecs.byteArray(ProgramEditing.MAX_SOURCE * 3).encode(buffer, bytes);
            assertThrows(DecoderException.class, () -> EditorNetwork.Request.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
