package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.DebugState;
import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.Request;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collapsible runtime and debugger panel beside the native editor. It renders the latest server view and turns
 * clicks into debugger requests. The server owns all execution state. Rows are rebuilt only when the view or the
 * layout changes, so rendering a frame does not allocate text.
 */
final class DebugPanel {
    interface Host {
        void debugRequest(int action, String argument);

        void reveal(int line, String reason);

        void clearBreakpoints();
    }

    private static final int ROW = 10;
    private static final int TEXT = 0xffe2e9ef;
    private static final int MUTED = 0xff93a5b2;
    private static final int HEADER = 0xff69ded1;
    private static final int ERROR = 0xffffa0a8;
    private static final int PAUSED = 0xffffdb84;
    private static final int GLOBAL = 0xff8fd3ff;

    private record Row(String text, int color, int line) {
    }

    private final Host host;
    private final Font font;
    private final List<Row> rows = new ArrayList<>();
    private DebugState state;
    private String notice = "Connecting to the debugger…";
    private boolean draftDiffers;
    private int x;
    private int y;
    private int width;
    private int height;
    private int scroll;
    private Button pause;
    private Button resume;
    private Button step;

    DebugPanel(Host host, Font font) {
        this.host = host;
        this.font = font;
    }

    DebugState state() {
        return state;
    }

    void layout(int x, int y, int width, int height, Consumer<Button> add) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        int button = (width - 9) / 4;
        pause = button("Pause", 0, button, () -> host.debugRequest(Request.DEBUG_PAUSE, ""), add);
        resume = button("Resume", 1, button, () -> host.debugRequest(Request.DEBUG_RESUME, ""), add);
        step = button("Step", 2, button, () -> host.debugRequest(Request.DEBUG_STEP, ""), add);
        button("Clear", 3, button, host::clearBreakpoints, add)
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Remove all breakpoints")));
        rebuild();
    }

    private Button button(String label, int index, int size, Runnable action, Consumer<Button> add) {
        var created = Button.builder(Component.literal(label), ignored -> action.run())
                .bounds(x + index * (size + 3), y, size, 18).build();
        add.accept(created);
        return created;
    }

    void notice(String text) {
        notice = text;
        rebuild();
    }

    void draftDiffers(boolean differs) {
        if (differs != draftDiffers) {
            draftDiffers = differs;
            rebuild();
        }
    }

    void accept(DebugState next) {
        boolean newPause = next.pause().line() > 0 && (state == null || !next.pause().equals(state.pause()));
        state = next;
        notice = next.message();
        rebuild();
        if (newPause) {
            host.reveal(next.pause().line(), "Paused at line " + next.pause().line() + " (" + next.pause().reason() + ")");
        }
    }

    boolean paused() {
        return state != null && state.pause().line() > 0;
    }

    int pausedLine() {
        return state == null ? 0 : state.pause().line();
    }

    int faultLine() {
        return state == null ? 0 : state.status().faultLine();
    }

    private void rebuild() {
        rows.clear();
        if (pause == null) {
            return;
        }
        int code = state == null ? -1 : state.status().state();
        pause.active = state != null && code != DebugState.PAUSED && code != DebugState.PAUSING;
        resume.active = code == DebugState.PAUSED || code == DebugState.PAUSING;
        step.active = state != null;
        if (state == null) {
            wrap(notice, MUTED, 0);
            return;
        }
        var status = state.status();
        wrap(status.headline(), code == DebugState.PAUSED ? PAUSED : code == DebugState.REJECTED ? ERROR : HEADER, 0);
        if (!notice.isEmpty()) {
            wrap(notice, MUTED, 0);
        }
        if (draftDiffers) {
            wrap("Lines refer to the saved program. Save to keep breakpoints aligned.", MUTED, 0);
        }
        if (status.faultLine() > 0 || !status.fault().isEmpty()) {
            header(code == DebugState.REJECTED ? "Problem" : "Last runtime error");
            int line = status.faultLine();
            wrap((line > 0 ? line + ":" + status.faultColumn() + "  " : "") + status.fault(), ERROR, line);
            if (!paused()) {
                status.faultFrames().forEach(frame -> wrap("  in " + frame.function() + " · line " + frame.line(), MUTED,
                        frame.line()));
            }
        }
        if (paused()) {
            header("Call stack");
            state.pause().frames().forEach(frame -> wrap(frame.function() + " · line " + frame.line(), TEXT, frame.line()));
            header("Variables");
            if (state.pause().variables().isEmpty()) {
                wrap("No variables in scope", MUTED, 0);
            }
            for (var variable : state.pause().variables()) {
                int color = switch (variable.scope()) {
                    case "global" -> GLOBAL;
                    case "const" -> MUTED;
                    default -> TEXT;
                };
                String scope = variable.scope().equals("local") ? "" : variable.scope() + " ";
                wrap(scope + variable.name() + " = " + variable.value(), color, 0);
            }
        }
        if (!state.breakpoints().isEmpty()) {
            header("Breakpoints");
            wrap(String.join(", ", state.breakpoints().stream().map(String::valueOf).toList()), TEXT, 0);
        }
        header("Statistics");
        wrap(status.statistics(), MUTED, 0);
        header("Output");
        if (state.console().isEmpty()) {
            wrap("No output yet", MUTED, 0);
        }
        for (var line : state.console()) {
            int color = switch (line.kind()) {
                case 1 -> ERROR;
                case 2 -> MUTED;
                default -> TEXT;
            };
            wrap(line.text(), color, 0);
        }
        scroll = Math.min(scroll, maxScroll());
    }

    private void header(String text) {
        rows.add(new Row("", MUTED, 0));
        rows.add(new Row(text, HEADER, 0));
    }

    /** Word-wraps a line to the panel width. Overlong words are cut. Runs only when rows are rebuilt. */
    private void wrap(String text, int color, int line) {
        int available = Math.max(20, width - 8);
        String remaining = text;
        while (!remaining.isEmpty()) {
            String fitted = font.plainSubstrByWidth(remaining, available);
            if (fitted.length() < remaining.length()) {
                int space = fitted.lastIndexOf(' ');
                if (space > fitted.length() / 3) {
                    fitted = fitted.substring(0, space + 1);
                }
            }
            if (fitted.isEmpty()) {
                fitted = remaining.substring(0, Character.charCount(remaining.codePointAt(0)));
            }
            rows.add(new Row(fitted.stripTrailing(), color, line));
            remaining = remaining.substring(fitted.length()).stripLeading();
        }
        if (text.isEmpty()) {
            rows.add(new Row("", color, line));
        }
    }

    private int contentTop() {
        return y + 22;
    }

    private int visibleRows() {
        return Math.max(1, (height - 24) / ROW);
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - visibleRows());
    }

    void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(x - 2, y - 2, x + width, y + height, 0xff151a20);
        int top = contentTop();
        graphics.enableScissor(x, top, x + width - 2, y + height);
        int end = Math.min(rows.size(), scroll + visibleRows());
        for (int index = scroll; index < end; index++) {
            Row row = rows.get(index);
            int rowY = top + (index - scroll) * ROW;
            boolean hovered = row.line() > 0 && mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW;
            if (hovered) {
                graphics.fill(x, rowY - 1, x + width - 2, rowY + ROW - 1, 0xff27313c);
            }
            graphics.text(font, row.text(), x + 3, rowY, row.color(), false);
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int track = height - 24;
            int thumb = Math.max(8, track * visibleRows() / rows.size());
            int offset = (track - thumb) * scroll / maxScroll();
            graphics.fill(x + width - 3, top + offset, x + width - 1, top + offset + thumb, 0xff3e4c58);
        }
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x - 2 && mouseX < x + width && mouseY >= y - 2 && mouseY < y + height;
    }

    /** Navigates to the source line of a clicked error or stack row. Returns true when the click was consumed. */
    boolean click(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY) || mouseY < contentTop()) {
            return false;
        }
        int index = scroll + (int) ((mouseY - contentTop()) / ROW);
        if (index >= 0 && index < rows.size() && rows.get(index).line() > 0) {
            host.reveal(rows.get(index).line(), "Line " + rows.get(index).line());
        }
        return true;
    }

    boolean scroll(double mouseX, double mouseY, double amount) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) Math.signum(amount) * 3, 0, maxScroll());
        return true;
    }
}
