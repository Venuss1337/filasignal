package dev.wiktorfila.filasignal.fabric.editor;

import java.util.ArrayList;

/** Small bounded snapshot history layered over the vanilla text widget. */
final class DraftHistory {
    private static final int MAX_STATES = 48;
    private final ArrayList<String> states = new ArrayList<>();
    private int cursor;

    DraftHistory(String initial) { reset(initial); }

    void reset(String initial) {
        states.clear();
        states.add(initial);
        cursor = 0;
    }

    void record(String text) {
        if (states.get(cursor).equals(text)) {
            return;
        }
        states.subList(cursor + 1, states.size()).clear();
        states.add(text);
        if (states.size() > MAX_STATES) {
            states.removeFirst();
        }
        cursor = states.size() - 1;
    }

    String undo() { return states.get(cursor = Math.max(0, cursor - 1)); }
    String redo() { return states.get(cursor = Math.min(states.size() - 1, cursor + 1)); }
}
