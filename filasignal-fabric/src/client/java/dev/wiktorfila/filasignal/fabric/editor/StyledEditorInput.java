package dev.wiktorfila.filasignal.fabric.editor;

/** Editor-only options carried by the native input widget. */
public interface StyledEditorInput {
    void filaHighlight(EditorPalette palette);
    net.minecraft.client.gui.components.MultilineTextField filaModel();
}
