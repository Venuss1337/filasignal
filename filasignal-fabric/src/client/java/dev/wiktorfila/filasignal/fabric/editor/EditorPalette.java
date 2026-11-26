package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.core.lexer.Lexer;
import dev.wiktorfila.filasignal.core.lexer.TokenType;
import dev.wiktorfila.filasignal.core.source.SourceText;

import java.util.Arrays;

/** A cached color per source offset, consumed only for strings vanilla is about to draw. */
public final class EditorPalette {
    static final int TEXT = 0xffe2e9ef;
    private final int[] colors;
    private final int[] partners;
    private int marked = -1;
    private final int[] lineStarts;
    private final java.util.BitSet errorLines = new java.util.BitSet();
    private final java.util.LinkedHashMap<Segment, net.minecraft.network.chat.Component> styled = new java.util.LinkedHashMap<>();
    private record Segment(int start, String text) {}
    private java.util.List<dev.wiktorfila.filasignal.core.diagnostic.Diagnostic> diagnostics = java.util.List.of();
    java.util.List<dev.wiktorfila.filasignal.core.diagnostic.Diagnostic> diagnostics() { return diagnostics; }
    private dev.wiktorfila.filasignal.core.ast.Ast.Program program;
    private java.util.BitSet statements;

    public EditorPalette(String text) {
        colors = new int[text.length()];
        partners = new int[text.length()];
        Arrays.fill(partners, -1);
        lineStarts = java.util.stream.IntStream.concat(java.util.stream.IntStream.of(0),
                java.util.stream.IntStream.range(0, text.length()).filter(index -> text.charAt(index) == '\n')
                        .map(index -> index + 1)).toArray();
        Arrays.fill(colors, TEXT);
        try {
            var source = new SourceText("draft", "draft.fsig", text);
            var lexed = Lexer.lex(source);
            var parsed = dev.wiktorfila.filasignal.core.parser.Parser.parse(lexed);
            diagnostics = parsed.diagnostics();
            program = parsed.program();
            for (var issue : diagnostics) {
                errorLines.set(issue.span().start().line());
            }
            int shift = text.length() - source.text().length();
            var brackets = new java.util.ArrayDeque<dev.wiktorfila.filasignal.core.lexer.Token>();
            for (var token : lexed.tokens()) {
                var span = token.span();
                Arrays.fill(colors, span.startOffset() + shift, span.endOffset() + shift, color(token.type()));
                match(brackets, token, shift);
            }
            for (var comment : lexed.comments()) {
                Arrays.fill(colors, comment.startOffset() + shift, comment.endOffset() + shift, 0xff8a9ba7);
            }
        } catch (IllegalArgumentException malformed) {
            // Native editing can temporarily produce an invalid UTF-16 boundary.
        }
    }

    private void match(java.util.ArrayDeque<dev.wiktorfila.filasignal.core.lexer.Token> pending,
                       dev.wiktorfila.filasignal.core.lexer.Token token, int shift) {
        TokenType opening = switch (token.type()) {
            case RIGHT_BRACE -> TokenType.LEFT_BRACE;
            case RIGHT_PAREN -> TokenType.LEFT_PAREN;
            case RIGHT_BRACKET -> TokenType.LEFT_BRACKET;
            default -> null;
        };
        if (opening != null) {
            if (!pending.isEmpty() && pending.getLast().type() == opening) {
                int start = pending.removeLast().span().startOffset() + shift;
                int end = token.span().startOffset() + shift;
                partners[start] = end;
                partners[end] = start;
            } else {
                pending.clear();
            }
        } else if (token.type() == TokenType.LEFT_BRACE || token.type() == TokenType.LEFT_PAREN
                || token.type() == TokenType.LEFT_BRACKET) {
            pending.addLast(token);
        }
    }

    /** Highlights the matched bracket pair at the caret, or just before it. */
    public void caret(int offset) {
        int next = partner(offset) >= 0 ? offset : partner(offset - 1) >= 0 ? offset - 1 : -1;
        if (next != marked && (next < 0 || partner(next) != marked)) {
            marked = next;
            styled.clear();
        }
    }

    private int partner(int offset) {
        return offset >= 0 && offset < partners.length ? partners[offset] : -1;
    }

    boolean hasError(int line) { return errorLines.get(line); }

    /** True when a statement starts on the 1-based line, or when the draft could not be analyzed. Computed lazily. */
    boolean statementAt(int line) {
        if (program == null) {
            return true;
        }
        if (statements == null) {
            statements = dev.wiktorfila.filasignal.core.runtime.debug.StatementLines.of(program);
        }
        return statements.get(line);
    }

    int lineCount() { return lineStarts.length; }

    /** Offset of the first character of a 1-based line, clamped to the draft. */
    int lineStart(int line) {
        return lineStarts[Math.clamp(line - 1, 0, lineStarts.length - 1)];
    }

    int lineAt(int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /** Reuses colored text for visible native segments until the draft changes. */
    public net.minecraft.network.chat.Component styled(int start, String raw) {
        var segment = new Segment(start, raw);
        var line = styled.get(segment);
        if (line != null) {
            return line;
        }
        var text = net.minecraft.network.chat.Component.empty();
        int from = 0;
        while (from < raw.length()) {
            int tint = at(start + from);
            int to = from + 1;
            while (to < raw.length() && at(start + to) == tint) {
                to++;
            }
            text.append(net.minecraft.network.chat.Component.literal(raw.substring(from, to)).withColor(tint & 0xffffff));
            from = to;
        }
        styled.put(segment, text);
        if (styled.size() > 256) {
            styled.pollFirstEntry();
        }
        return text;
    }

    public int at(int offset) {
        if (marked >= 0 && (offset == marked || offset == partner(marked))) {
            return 0xffffdb84;
        }
        return offset >= 0 && offset < colors.length ? colors[offset] : TEXT;
    }

    private static int color(TokenType type) {
        return switch (type) {
            case IDENTIFIER, EOF, NEWLINE -> TEXT;
            case NUMBER -> 0xffeac586;
            case STRING -> 0xffb5d29a;
            case TRUE, FALSE, NIL -> 0xffe8a4ce;
            case GLOBAL, CONST, FN, CLASS, ENUM, ON, IF, ELSE, WHILE, FOR, IN, RETURN, BREAK, CONTINUE,
                    IS, AND, OR, NOT, SELF -> 0xff77dfd0;
            case ERROR -> 0xffffa0a8;
            default -> 0xffaabbd0;
        };
    }
}
