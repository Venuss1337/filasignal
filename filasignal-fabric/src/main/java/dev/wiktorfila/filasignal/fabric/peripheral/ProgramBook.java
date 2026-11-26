package dev.wiktorfila.filasignal.fabric.peripheral;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;

/** Reads an ordinary unsigned book as source without giving scripts any book or filesystem access. */
public final class ProgramBook {
    private static final int MAX_SOURCE_UNITS = 16_384;

    private ProgramBook() {}

    /** Reads bounded source from the authenticated player's main hand. */
    public static String read(ServerPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("A player must hold the program book");
        }
        var book = player.getMainHandItem().get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (book == null) {
            throw new IllegalArgumentException("Hold an unsigned Book and Quill containing FilaScript in your main hand");
        }
        StringBuilder code = new StringBuilder();
        for (var page : book.pages()) {
            String text = page.raw();
            if (text.length() + 1L > MAX_SOURCE_UNITS - code.length()) {
                throw new IllegalArgumentException("Program book exceeds 16,384 UTF-16 units");
            }
            code.append(text).append('\n');
        }
        return code.toString();
    }
}
