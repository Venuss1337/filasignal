package dev.wiktorfila.filasignal.fabric.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.wiktorfila.filasignal.fabric.peripheral.PeripheralEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a Display Module's text on its screen. The text is laid out once per change and scaled to fit the screen,
 * so a short message is large and readable while eight long lines still fit. Other hardware is skipped before any
 * work is done.
 */
public final class DisplayRenderer implements BlockEntityRenderer<PeripheralEntity, DisplayRenderer.State> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int TEXT_COLOR = 0xFF78E6A6;
    // The screen on the texture spans pixels 2–13 across and 2–12 down. Units are blocks.
    private static final float SCREEN_WIDTH = 12 / 16f - 0.06f;
    private static final float SCREEN_HEIGHT = 11 / 16f - 0.06f;
    private static final float SCREEN_CENTER_Y = 0.5f / 16f;
    // A short message is drawn no larger than sign text.
    private static final float MAX_SCALE = 1 / 80f;

    private final Font font;

    public DisplayRenderer(BlockEntityRendererProvider.Context context) {
        font = context.font();
    }

    /** The text and placement for one frame. */
    public static final class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        Layout layout;
    }

    /** Lines prepared for drawing, cached on the block entity until its text changes. */
    private record Layout(List<String> source, List<FormattedCharSequence> lines, float width, float scale) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public boolean shouldRender(PeripheralEntity entity, Vec3 camera) {
        return !entity.displayLines().isEmpty() && BlockEntityRenderer.super.shouldRender(entity, camera);
    }

    @Override
    public void extractRenderState(PeripheralEntity entity, State state, float partialTick, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, crumbling);
        var blockState = entity.getBlockState();
        state.facing = blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? blockState.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        state.layout = layout(entity);
    }

    private Layout layout(PeripheralEntity entity) {
        List<String> text = entity.displayLines();
        if (entity.renderCache() instanceof Layout cached && cached.source() == text) {
            return cached;
        }
        var lines = new ArrayList<FormattedCharSequence>(text.size());
        int widest = 1;
        for (String line : text) {
            FormattedCharSequence sequence = FormattedCharSequence.forward(line, Style.EMPTY);
            lines.add(sequence);
            widest = Math.max(widest, font.width(sequence));
        }
        float height = Math.max(1, lines.size()) * font.lineHeight;
        float scale = Math.min(MAX_SCALE, Math.min(SCREEN_WIDTH / widest, SCREEN_HEIGHT / height));
        Layout layout = new Layout(text, List.copyOf(lines), widest, scale);
        entity.renderCache(layout);
        return layout;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Layout layout = state.layout;
        if (layout == null || layout.lines().isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5f, 0.5f, 0.5f);
        // Text faces +Z; turn it to the block's front and move it just in front of the screen.
        pose.rotateDegrees(Axis.YP, -state.facing.toYRot());
        pose.translate(0f, SCREEN_CENTER_Y, 0.5f + 1 / 512f);
        pose.scale(layout.scale(), -layout.scale(), layout.scale());
        float left = -layout.width() / 2f;
        float top = -(layout.lines().size() * font.lineHeight) / 2f;
        for (int i = 0; i < layout.lines().size(); i++) {
            collector.submitText(pose, left, top + i * font.lineHeight, layout.lines().get(i), false,
                    Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, TEXT_COLOR, 0, 0);
        }
        pose.popPose();
    }
}
