package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.DebugState;
import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.Reply;
import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork.Request;
import dev.wiktorfila.filasignal.fabric.computer.ProgramEditing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.TreeSet;
import java.util.UUID;

/** Native text input and scrolling, with a local draft, explicit server actions and an optional debug panel. */
public final class ProgramScreen extends Screen implements DebugPanel.Host {
    private static final int GUTTER_LEFT = 12;
    private static final int GUTTER_RIGHT = 38;
    private static final int ROW = 9;

    private final BlockPos pos;
    // What is being edited: the computer program, its drive slot, or a device drive. Sent with every request.
    private final String target;
    private final String label;
    private UUID revision;
    private String saved;
    private String draft;
    private String message = "Drafts stay on this client until applied";
    private MultiLineEditBox input;
    private net.minecraft.client.gui.components.EditBox find;
    private final java.util.List<String> catalog;
    private EditorPalette palette;
    private int nextDiagnostic;
    private String query = "";
    private int pending = -1;
    private int timeout;
    private String sent;
    private UUID requestId;
    private final DraftHistory history;
    private int analysisDelay;
    private boolean restoring;
    private CompletionMenu completion;
    private int completionCaret;
    private int editorRight;
    // Debugger state survives init() so resizing keeps the panel, breakpoints and scroll position.
    private DebugPanel debug;
    private final TreeSet<Integer> breakpoints = new TreeSet<>();
    private UUID breakpointRequest;
    private int breakpointTimeout;
    private boolean confirming;
    private int attachRetry;
    private int attachAttempts;

    ProgramScreen(Reply snapshot) {
        super(Component.literal("FilaScript Editor"));
        pos = snapshot.pos();
        target = snapshot.target();
        label = snapshot.label().isEmpty() ? "Computer program" : snapshot.label();
        message = snapshot.status();
        revision = snapshot.revision();
        saved = draft = snapshot.source();
        history = new DraftHistory(draft);
        var entries = new java.util.ArrayList<>(snapshot.catalog().lines().toList());
        entries.addAll(dev.wiktorfila.filasignal.core.lexer.Lexer.keywords());
        catalog = entries.stream().distinct().sorted().toList();
    }

    String target() {
        return target;
    }

    BlockPos pos() {
        return pos;
    }

    @Override
    protected void init() {
        completion = null;
        int margin = 12;
        int usable = width - margin * 2;
        int panelWidth = debug == null ? 0 : Math.clamp(width * 2L / 5, 150, 240);
        editorRight = width - margin - (panelWidth == 0 ? 0 : panelWidth + 6);
        int buttonWidth = (usable - 12) / 4;
        button("Validate", margin, buttonWidth, () -> send(Request.VALIDATE));
        button("Save & Apply", margin + buttonWidth + 4, buttonWidth, () -> send(Request.APPLY));
        button("Reload", margin + 2 * (buttonWidth + 4), buttonWidth, () -> confirm(() -> send(Request.RELOAD)));
        button("From Book", margin + 3 * (buttonWidth + 4), buttonWidth, () -> confirm(() -> send(Request.IMPORT_BOOK)));
        addRenderableWidget(Button.builder(Component.literal("Start / Restart"), ignored -> send(Request.START_OR_RESTART))
                .bounds(margin, 50, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), ignored -> send(Request.STOP))
                .bounds(margin + 94, 50, 46, 20).build());
        addRenderableWidget(Button.builder(Component.literal(debug == null ? "Debug ▸" : "Debug ◂"), ignored -> toggleDebug())
                .bounds(margin + 144, 50, 56, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "Runtime status, output, breakpoints and stepping. F9 breakpoint, F5 pause/resume, F10 step.")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Starters"), ignored -> {
                    // Stepping through unedited starters needs no confirmation; replacing your own draft does.
                    var starters = starters();
                    if (starter >= 0 && starter < starters.size() && draft.equals(starters.get(starter).source())) {
                        nextStarter();
                    } else {
                        confirm(this::nextStarter);
                    }
                })
                .bounds(margin + 204, 50, 60, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "Load a short example program to edit. Press again for the next one; Ctrl+Z brings your draft back.")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("?"), ignored -> HelpLinks.open(HelpLinks.EDITOR))
                .bounds(margin + 268, 50, 20, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "Open the editor guide. Minecraft asks before opening your browser.")))
                .build());
        find = new net.minecraft.client.gui.components.EditBox(font, margin + 292, 50, Math.max(30, usable - 292), 20,
                Component.literal("Find in program"));
        find.setMaxLength(128);
        find.setValue(query);
        find.setResponder(value -> query = value);
        addRenderableWidget(find);
        input = MultiLineEditBox.builder().setX(margin + 29).setY(77).setShowBackground(false)
                .setShowDecorations(false).setTextShadow(false).setTextColor(0xffe2e9ef).setCursorColor(0xff69ded1)
                .build(font, editorRight - margin - 29, Math.max(18, height - 104), Component.literal("FilaScript source"));
        input.setCharacterLimit(ProgramEditing.MAX_SOURCE);
        input.setValue(draft);
        input.setValueListener(this::changed);
        addRenderableWidget(input);
        if (debug != null) {
            debug.layout(editorRight + 8, 77, panelWidth, height - 104, this::addRenderableWidget);
        }
        setInitialFocus(input);
        analyze();
    }

    private void button(String name, int x, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(name), ignored -> action.run()).bounds(x, 26, width, 20).build());
    }

    private void changed(String value) {
        draft = value;
        completion = null;
        if (!restoring) {
            history.record(draft);
        }
        analysisDelay = 3;
    }

    private int starter = -1;

    /** Starters that can run on this editing target: computer programs, or programs for this drive's device. */
    private java.util.List<StarterPrograms.Starter> starters() {
        return StarterPrograms.forTarget(target.isEmpty(), label);
    }

    /** Replaces the draft with the next bundled starter program. The replaced draft stays in undo history. */
    private void nextStarter() {
        var starters = starters();
        if (starters.isEmpty()) {
            message = "No starter programs are available";
            return;
        }
        starter = (starter + 1) % starters.size();
        var chosen = starters.get(starter);
        input.setValue(chosen.source());
        message = "Starter " + (starter + 1) + " of " + starters.size() + ": " + chosen.title()
                + ". Edit device names, then Save & Apply.";
    }

    private void confirm(Runnable action) {
        if (pending != -1) {
            message = "Wait for the current server reply before replacing this draft.";
            return;
        }
        if (draft.equals(saved)) {
            action.run();
            return;
        }
        ask(action);
    }

    // The confirmation screen temporarily replaces this one. The debugger stays attached across that switch.
    private void ask(Runnable action) {
        confirming = true;
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.gui.setScreen(this);
            confirming = false;
            if (confirmed) {
                action.run();
            }
        }, Component.literal("Discard local draft?"), Component.literal("Your unsaved changes will be replaced.")));
    }

    private void send(int action) {
        if (pending != -1) {
            return;
        }
        boolean carriesDraft = action == Request.APPLY || action == Request.VALIDATE || action == Request.APPLY_RESET;
        if (carriesDraft
                && draft.codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff)) {
            message = "The draft contains an incomplete Unicode character";
            return;
        }
        pending = action;
        timeout = 100;
        sent = draft;
        message = "Waiting for server…";
        requestId = UUID.randomUUID();
        if (!RequestGate.send(new Request(pos, revision, action, requestId, carriesDraft ? draft : "", target))) {
            pending = -1;
            message = "Too many queued requests. Try again.";
        }
    }

    @Override
    public Component getNarrationMessage() {
        return Component.literal(getTitle().getString() + ". " + label + ". " + message);
    }

    void accept(Reply reply) {
        if (!pos.equals(reply.pos()) || pending == -1 || !reply.requestId().equals(requestId)) {
            return;
        }
        int action = pending;
        pending = -1;
        message = reply.status();
        if (!reply.success() && action == Request.APPLY && reply.status().startsWith(ProgramEditing.INCOMPATIBLE_GLOBALS)) {
            offerFreshGlobals(reply.status());
            return;
        }
        if (reply.success() && action != Request.VALIDATE) {
            revision = reply.revision();
            saved = reply.source();
            if (action != Request.STOP && action != Request.START_OR_RESTART && draft.equals(sent)) {
                changeValue(saved);
                history.reset(saved);
            }
        }
    }

    // Removing or retyping a saved global needs fresh globals. Only the owner or an administrator may discard them.
    private void offerFreshGlobals(String reason) {
        confirming = true;
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.gui.setScreen(this);
            confirming = false;
            if (confirmed) {
                send(Request.APPLY_RESET);
            }
        }, Component.literal("Apply with fresh globals?"), Component.literal(reason
                + " Applying anyway discards every saved global value. Only the owner or an administrator can do this.")));
    }

    private void changeValue(String value) {
        restoring = true;
        input.setValue(value);
        restoring = false;
        draft = value;
    }

    // Debugger

    private void toggleDebug() {
        if (debug == null) {
            debug = new DebugPanel(this, font);
            attachAttempts = 0;
            attach();
        } else {
            detachDebugger();
        }
        rebuildWidgets();
    }

    private void attach() {
        attachAttempts++;
        attachRetry = 40;
        RequestGate.send(new Request(pos, revision, Request.DEBUG_ATTACH, UUID.randomUUID(), "", target));
        if (!breakpoints.isEmpty()) {
            sendBreakpoints();
        }
    }

    private void detachDebugger() {
        if (debug != null) {
            debug = null;
            breakpointRequest = null;
            RequestGate.sendUnpaced(new Request(pos, revision, Request.DEBUG_DETACH, UUID.randomUUID(), "", target));
        }
    }

    @Override
    public void debugRequest(int action, String argument) {
        if (debug != null && !RequestGate.send(new Request(pos, revision, action, UUID.randomUUID(), argument, target))) {
            message = "Too many queued requests. Try again.";
        }
    }

    @Override
    public void clearBreakpoints() {
        breakpoints.clear();
        sendBreakpoints();
        message = "Breakpoints cleared";
    }

    private void sendBreakpoints() {
        if (debug == null) {
            return;
        }
        breakpointRequest = UUID.randomUUID();
        breakpointTimeout = 100;
        RequestGate.send(new Request(pos, revision, Request.DEBUG_BREAKPOINTS, breakpointRequest,
                String.join(",", breakpoints.stream().map(String::valueOf).toList()), target));
    }

    private void toggleBreakpoint(int line) {
        analyzeIfPending();
        if (breakpoints.remove(line)) {
            message = "Breakpoint removed from line " + line;
        } else if (!palette.statementAt(line)) {
            message = "No statement starts on line " + line;
            return;
        } else if (breakpoints.size() >= dev.wiktorfila.filasignal.core.runtime.debug.Debugger.MAX_BREAKPOINTS) {
            message = "Breakpoint limit reached";
            return;
        } else {
            breakpoints.add(line);
            message = debug == null ? "Breakpoint on line " + line + ". Open Debug to activate breakpoints."
                    : "Breakpoint on line " + line;
        }
        sendBreakpoints();
    }

    void acceptDebug(DebugState state) {
        if (!pos.equals(state.pos()) || debug == null) {
            return;
        }
        if (!state.success() && state.pushed()) {
            // The server ended the session, for example after moving out of range or losing access.
            debug = null;
            message = state.message();
            rebuildWidgets();
            return;
        }
        if (!state.success()) {
            debug.notice(state.message());
            return;
        }
        // Server breakpoint lists are authoritative unless this client still has an unacknowledged change.
        if (breakpointRequest == null || breakpointRequest.equals(state.requestId())) {
            breakpointRequest = null;
            breakpoints.clear();
            breakpoints.addAll(state.breakpoints());
        }
        debug.draftDiffers(!draft.equals(saved));
        debug.accept(state);
    }

    @Override
    public void reveal(int line, String reason) {
        analyzeIfPending();
        int start = palette.lineStart(line);
        int end = draft.indexOf('\n', start);
        select(start, end < 0 ? draft.length() : end);
        message = reason;
    }

    @Override
    public void tick() {
        if (pending != -1 && --timeout == 0) {
            pending = -1;
            message = "No reply. Reload to check the current revision before retrying.";
        }
        if (breakpointRequest != null && --breakpointTimeout == 0) {
            breakpointRequest = null;
        }
        if (analysisDelay > 0 && --analysisDelay == 0) {
            analyze();
        }
        if (debug != null) {
            debug.draftDiffers(!draft.equals(saved));
            // The server can drop a request under load. Retry attaching until a view arrives.
            if (debug.state() == null && attachAttempts < 3 && --attachRetry <= 0) {
                attach();
            }
        }
    }

    private void analyze() {
        palette = new EditorPalette(draft);
        ((StyledEditorInput) input).filaHighlight(palette);
        nextDiagnostic = 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (completion != null && handleCompletion(event.shortcutKey())) {
            return true;
        }
        if (event.hasControlDownWithQuirk()) {
            switch (event.shortcutKey()) {
                case org.lwjgl.sdl.SDLKeycode.SDLK_F -> { setInitialFocus(find); return true; }
                case org.lwjgl.sdl.SDLKeycode.SDLK_SPACE -> { complete(); return true; }
                case org.lwjgl.sdl.SDLKeycode.SDLK_S -> { send(Request.APPLY); return true; }
                case org.lwjgl.sdl.SDLKeycode.SDLK_Z -> {
                    changeValue(event.hasShiftDown() ? history.redo() : history.undo());
                    return true;
                }
                case org.lwjgl.sdl.SDLKeycode.SDLK_Y -> { changeValue(history.redo()); return true; }
                default -> { }
            }
        }
        int key = event.shortcutKey();
        if (find.isFocused() && key == org.lwjgl.sdl.SDLKeycode.SDLK_RETURN) {
            findNext();
            return true;
        }
        switch (key) {
            case org.lwjgl.sdl.SDLKeycode.SDLK_F8 -> { showDiagnostic(); return true; }
            case org.lwjgl.sdl.SDLKeycode.SDLK_F9 -> {
                toggleBreakpoint(palette.lineAt(nativeModel().cursor()));
                return true;
            }
            case org.lwjgl.sdl.SDLKeycode.SDLK_F5 -> {
                if (debug != null) {
                    debugRequest(debug.paused() ? Request.DEBUG_RESUME : Request.DEBUG_PAUSE, "");
                }
                return true;
            }
            case org.lwjgl.sdl.SDLKeycode.SDLK_F10 -> {
                if (debug != null) {
                    debugRequest(Request.DEBUG_STEP, "");
                }
                return true;
            }
            default -> { }
        }
        if (input.isFocused() && key == org.lwjgl.sdl.SDLKeycode.SDLK_TAB) {
            indent(event.hasShiftDown());
            return true;
        }
        if (input.isFocused() && key == org.lwjgl.sdl.SDLKeycode.SDLK_RETURN) {
            int start = lineStart();
            int end = start;
            while (end < nativeModel().cursor() && draft.charAt(end) == ' ') {
                end++;
            }
            nativeModel().insertText("\n" + draft.substring(start, end));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int line = gutterLine(event.x(), event.y());
            if (line > 0) {
                toggleBreakpoint(line);
                return true;
            }
            if (debug != null && debug.click(event.x(), event.y())) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (debug != null && debug.scroll(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The source line of a gutter row under the pointer, or 0. */
    private int gutterLine(double mouseX, double mouseY) {
        if (mouseX < GUTTER_LEFT - 2 || mouseX >= GUTTER_RIGHT || mouseY < input.getY() || mouseY >= input.getY() + input.getHeight()) {
            return 0;
        }
        int scroll = (int) input.scrollAmount();
        int row = (int) Math.floor((mouseY - input.getY() - 4 + scroll) / ROW);
        var model = nativeModel();
        if (row < 0 || row >= model.getLineCount()) {
            return 0;
        }
        return palette.lineAt(model.getLineView(row).beginIndex());
    }

    private net.minecraft.client.gui.components.MultilineTextField nativeModel() {
        return ((StyledEditorInput) input).filaModel();
    }

    private int lineStart() {
        return draft.lastIndexOf('\n', nativeModel().cursor() - 1) + 1;
    }

    private void indent(boolean reverse) {
        if (!reverse) {
            nativeModel().insertText("    ");
            return;
        }
        int start = lineStart();
        int end = start;
        while (end < draft.length() && end < start + 4 && draft.charAt(end) == ' ') {
            end++;
        }
        if (end > start) {
            select(start, end);
            nativeModel().insertText("");
        }
    }

    private void select(int from, int to) {
        var model = nativeModel();
        model.setSelecting(false);
        model.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, from);
        model.setSelecting(true);
        model.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, to);
        setInitialFocus(input);
    }

    private void findNext() {
        if (query.isEmpty()) { return; }
        int at = draft.indexOf(query, nativeModel().cursor());
        if (at == -1) { at = draft.indexOf(query); }
        if (at == -1) {
            message = "No matching text";
        } else {
            select(at, at + query.length());
            message = "Match found";
        }
    }

    private void showDiagnostic() {
        analyzeIfPending();
        var issues = palette.diagnostics();
        if (issues.isEmpty()) {
            message = "No local syntax errors. Validate to check server bindings.";
            return;
        }
        var issue = issues.get(nextDiagnostic++ % issues.size());
        var span = issue.span();
        int shift = draft.length() - span.source().text().length();
        select(span.startOffset() + shift, span.endOffset() + shift);
        message = span.start().line() + ":" + span.start().column() + " " + issue.message();
    }

    private void analyzeIfPending() {
        if (analysisDelay > 0) {
            analysisDelay = 0;
            analyze();
        }
    }

    private void complete() {
        if (!input.isFocused()) { return; }
        completionCaret = nativeModel().cursor();
        completion = CompletionMenu.open(draft, completionCaret, catalog);
        if (completion.empty()) {
            completion = null;
            message = "No matching suggestions";
        }
    }

    private boolean handleCompletion(int key) {
        if (!input.isFocused() || nativeModel().cursor() != completionCaret) {
            completion = null;
            return false;
        }
        switch (key) {
            case org.lwjgl.sdl.SDLKeycode.SDLK_UP -> completion.move(-1);
            case org.lwjgl.sdl.SDLKeycode.SDLK_DOWN -> completion.move(1);
            case org.lwjgl.sdl.SDLKeycode.SDLK_ESCAPE -> completion = null;
            case org.lwjgl.sdl.SDLKeycode.SDLK_RETURN, org.lwjgl.sdl.SDLKeycode.SDLK_TAB -> {
                var choice = completion;
                completion = null;
                select(choice.start(), completionCaret);
                nativeModel().insertText(choice.selected().insertion());
            }
            default -> {
                completion = null;
                return false;
            }
        }
        return true;
    }

    private void drawCompletion(GuiGraphicsExtractor graphics) {
        if (completion == null) { return; }
        if (!input.isFocused() || nativeModel().cursor() != completionCaret) {
            completion = null;
            return;
        }
        var visible = completion.visible();
        int y = height - 33 - (visible.size() + 1) * 12;
        graphics.fill(42, y - 3, editorRight, height - 27, 0xff27313c);
        for (var entry : visible) {
            if (completion.selected(entry)) {
                graphics.fill(43, y - 1, editorRight - 1, y + 10, 0xff335465);
            }
            graphics.text(font, font.plainSubstrByWidth(entry.signature(), editorRight - 51), 48, y, 0xffe2e9ef);
            y += 12;
        }
        String detail = completion.selected().detail();
        graphics.text(font, font.plainSubstrByWidth(detail.isEmpty() ? "↑ ↓ select · Enter insert" : detail, editorRight - 51),
                48, y, 0xffaab7c2);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xff20262d);
        graphics.fill(10, 75, editorRight + 2, height - 25, 0xff151a20);
        palette.caret(nativeModel().cursor());
        drawPausedLine(graphics);
        if (debug != null) {
            debug.render(graphics, mouseX, mouseY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawGutter(graphics);
        drawCompletion(graphics);
        graphics.text(font, font.plainSubstrByWidth("Editing: " + label + (draft.equals(saved) ? "" : "   •   Unsaved"),
                width - 24), 12, 10, 0xff69ded1);
        graphics.text(font, font.plainSubstrByWidth(message, width - 24), 12, height - 16, 0xffb7c5d0);
    }

    /** Highlights every visual row of the line where execution is paused, behind the native text. */
    private void drawPausedLine(GuiGraphicsExtractor graphics) {
        int paused = debug == null ? 0 : debug.pausedLine();
        if (paused <= 0) {
            return;
        }
        var model = nativeModel();
        int scroll = (int) input.scrollAmount();
        int row = scroll / ROW;
        int y = input.getY() + 4 - scroll % ROW;
        graphics.enableScissor(10, input.getY(), editorRight + 2, height - 26);
        for (; row < model.getLineCount() && y < height - 26; row++, y += ROW) {
            if (palette.lineAt(model.getLineView(row).beginIndex()) == paused) {
                graphics.fill(GUTTER_LEFT - 2, y - 1, editorRight + 2, y + ROW - 1, 0xff4a4024);
            }
        }
        graphics.disableScissor();
    }

    private void drawGutter(GuiGraphicsExtractor graphics) {
        var model = nativeModel();
        int scroll = (int) input.scrollAmount();
        int row = scroll / ROW;
        int y = input.getY() + 4 - scroll % ROW;
        int fault = debug == null ? 0 : debug.faultLine();
        graphics.enableScissor(GUTTER_LEFT - 2, input.getY(), GUTTER_RIGHT, height - 26);
        for (; row < model.getLineCount() && y < height - 26; row++, y += ROW) {
            int start = model.getLineView(row).beginIndex();
            if (start == 0 || draft.charAt(start - 1) == '\n') {
                int line = palette.lineAt(start);
                if (breakpoints.contains(line)) {
                    graphics.fill(GUTTER_LEFT - 2, y, GUTTER_LEFT + 1, y + 7, debug == null ? 0xff7a4a50 : 0xffe5484d);
                }
                int color = palette.hasError(line) || line == fault ? 0xffffa0a8 : 0xff93a5b2;
                graphics.text(font, label(line), GUTTER_LEFT + 3, y, color, false);
            }
        }
        graphics.disableScissor();
    }

    // Line labels are cached so drawing the gutter does not allocate a string per visible row each frame.
    private static String[] labels = new String[0];

    private static String label(int line) {
        if (line < 0 || line > ProgramEditing.MAX_SOURCE + 1) {
            return Integer.toString(line);
        }
        if (line >= labels.length) {
            labels = java.util.Arrays.copyOf(labels, Math.max(line + 1, labels.length * 2));
        }
        String cached = labels[line];
        if (cached == null) {
            cached = Integer.toString(line);
            labels[line] = cached;
        }
        return cached;
    }

    @Override
    public void onClose() {
        if (draft.equals(saved)) {
            closeEditor();
        } else {
            ask(this::closeEditor);
        }
    }

    private void closeEditor() {
        detachDebugger();
        super.onClose();
    }

    // Any other screen change, such as a death screen, also releases the debugger so the program cannot stay paused.
    @Override
    public void removed() {
        if (!confirming) {
            detachDebugger();
        }
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }
}
