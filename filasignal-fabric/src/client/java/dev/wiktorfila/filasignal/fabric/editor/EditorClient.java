package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client setup: the Display Module renderer and authorized editor snapshots on the Minecraft client thread. */
public final class EditorClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Display Modules show their text on the block face.
        net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry.register(
                dev.wiktorfila.filasignal.fabric.peripheral.PeripheralBlocks.hardwareType(),
                dev.wiktorfila.filasignal.fabric.render.DisplayRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(client -> RequestGate.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RequestGate.clear());
        ClientPlayNetworking.registerGlobalReceiver(dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork.Form.TYPE,
                (form, context) -> {
                    var gui = context.client().gui;
                    if (form.opening()) {
                        RequestGate.noteServerAdmission();
                    }
                    if (form.opening() && !(gui.screen() instanceof ProgramScreen)) {
                        gui.setScreen(new DeviceScreen(form));
                    } else if (gui.screen() instanceof DeviceScreen screen) {
                        screen.accept(form);
                    }
                });
        ClientPlayNetworking.registerGlobalReceiver(EditorNetwork.Reply.TYPE, (reply, context) -> {
            var client = context.client();
            if (reply.opening()) {
                RequestGate.noteServerAdmission();
            }
            // A reply that carries a catalog for an editing target opens the editor on that target.
            if (!reply.opening() && reply.success() && !reply.target().isEmpty() && !reply.catalog().isEmpty()) {
                client.gui.setScreen(new ProgramScreen(reply));
                return;
            }
            if (reply.opening() && reply.success()) {
                if (reply.requestId().getMostSignificantBits() != 0 || reply.requestId().getLeastSignificantBits() != 0) {
                    if (client.gui.screen() instanceof ComputerScreen dashboard) { dashboard.acceptRename(reply); }
                } else if (!(client.gui.screen() instanceof ProgramScreen)) {
                    client.gui.setScreen(new ComputerScreen(reply));
                }
            } else if (client.gui.screen() instanceof ProgramScreen editor) {
                editor.accept(reply);
            } else if (!reply.success() && client.gui.screen() instanceof ComputerScreen dashboard) {
                dashboard.acceptRename(reply);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(EditorNetwork.DebugState.TYPE, (state, context) -> {
            // Views for a closed editor are ignored. Closing the editor already released the server session.
            if (context.client().gui.screen() instanceof ProgramScreen editor) {
                editor.acceptDebug(state);
            }
        });
    }
}
