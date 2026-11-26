package dev.wiktorfila.filasignal.fabric.editor.mixin;

import dev.wiktorfila.filasignal.fabric.editor.EditorPalette;
import dev.wiktorfila.filasignal.fabric.editor.StyledEditorInput;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;

/** Intercepts only the native text draw calls. Selection, cursor, composition and layout remain vanilla. */
@Mixin(MultiLineEditBox.class)
abstract class MultilineColorMixin implements StyledEditorInput {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.gui.components.MultilineTextField textField;

    @Override public net.minecraft.client.gui.components.MultilineTextField filaModel() { return textField; }

    @Unique private EditorPalette filaPalette;
    @Unique private IdentityHashMap<String, Integer> filaOffsets;

    @Override
    public void filaHighlight(EditorPalette palette) {
        filaPalette = palette;
        if (filaOffsets == null) {
            filaOffsets = new IdentityHashMap<>();
        }
    }

    @Inject(method = "extractContents", at = @At("HEAD"))
    private void filaBegin(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (filaOffsets != null) {
            filaOffsets.clear();
        }
    }

    @WrapOperation(method = "extractContents", at = @At(value = "INVOKE",
            target = "Ljava/lang/String;substring(II)Ljava/lang/String;"))
    private String filaTrackSource(String source, int start, int end, Operation<String> vanilla) {
        String text = vanilla.call(source, start, end);
        if (filaOffsets != null && !text.isEmpty()) {
            filaOffsets.put(text, start);
        }
        return text;
    }

    @WrapOperation(method = "extractContents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
    private void filaDraw(GuiGraphicsExtractor graphics, Font font, String raw, int x, int y, int color,
                          boolean shadow, Operation<Void> vanilla) {
        Integer start = filaOffsets == null ? null : filaOffsets.remove(raw);
        if (start == null) {
            vanilla.call(graphics, font, raw, x, y, color, shadow);
            return;
        }
        graphics.text(font, filaPalette.styled(start, raw), x, y, color, shadow);
    }
}
