package dev.wiktorfila.filasignal.fabric;

import dev.wiktorfila.filasignal.fabric.computer.ComputerBlocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Exercises native server placement, linking and removal in a local integrated world. */
public final class IntegratedWorldTest implements FabricClientGameTest {
    /** A widget and everything inside it, so lookups also find widgets in a scrolling screen. */
    static java.util.stream.Stream<io.github.cottonmc.cotton.gui.widget.WWidget> all(
            io.github.cottonmc.cotton.gui.widget.WWidget widget) {
        return widget instanceof io.github.cottonmc.cotton.gui.widget.WPanel panel
                ? java.util.stream.Stream.concat(java.util.stream.Stream.of(widget),
                        panel.streamChildren().flatMap(IntegratedWorldTest::all))
                : java.util.stream.Stream.of(widget);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            var playerId = context.computeOnClient(client -> client.player.getUUID());
            BlockPos root = context.computeOnClient(client -> BlockPos.containing(client.player.position()).east(2));
            BlockPos router = root.south();
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                use(player, "programmable_computer", root.below());
                use(player, "network_interface", router.below());
                use(player, "linking_tool", root);
                use(player, "linking_tool", router);
                int configured = server.getCommands().getDispatcher().execute(
                        "filasignal alias %d %d %d integrated_router".formatted(router.getX(), router.getY(), router.getZ()),
                        player.createCommandSourceStack());
                if (configured != 1) {
                    throw new AssertionError("Integrated player could not configure the interface");
                }
            });
            context.waitFor(client -> client.level.getBlockState(root).is(ComputerBlocks.block()));
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var hit = new BlockHitResult(Vec3.atCenterOf(root), Direction.UP, root, false);
                player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            });
            context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ComputerScreen);
            for (int i = 0; i < 6; i++) { context.waitTick(); }
            context.runOnClient(client -> {
                var dashboard = (dev.wiktorfila.filasignal.fabric.editor.ComputerScreen) client.gui.screen();
                var controls = dashboard.getDescription().getRootPanel();
                var name = (io.github.cottonmc.cotton.gui.widget.WTextField) controls.streamChildren().flatMap(IntegratedWorldTest::all)
                        .filter(io.github.cottonmc.cotton.gui.widget.WTextField.class::isInstance).findFirst().orElseThrow();
                name.setText("Main_Controller");
                controls.streamChildren().flatMap(IntegratedWorldTest::all).filter(child -> child instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                        && button.getLabel().getString().equals("Save name"))
                        .map(io.github.cottonmc.cotton.gui.widget.WButton.class::cast).findFirst().orElseThrow().getOnClick().run();
            });
            context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ComputerScreen dashboard
                    && dashboard.getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all).anyMatch(child ->
                        child instanceof io.github.cottonmc.cotton.gui.widget.WLabel label
                            && label.getText().getString().equals("Main_Controller")));
            context.runOnClient(client -> {
                var dashboard = (dev.wiktorfila.filasignal.fabric.editor.ComputerScreen) client.gui.screen();
                ((io.github.cottonmc.cotton.gui.widget.WButton) dashboard.getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all)
                        .filter(child -> child instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                                && button.getLabel().getString().equals("Open Editor")).findFirst().orElseThrow())
                        .getOnClick().run();
            });
            context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ProgramScreen);
            for (int i = 0; i < 6; i++) {
                context.waitTick();
            }
            context.runOnClient(client -> {
                var editor = client.gui.screen();
                var area = (net.minecraft.client.gui.components.MultiLineEditBox) editor.children().stream()
                        .filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).findFirst().orElseThrow();
                String original = area.getValue();
                // A starter program replaces an unchanged draft, and undo brings the draft back.
                var starters = (net.minecraft.client.gui.components.Button) editor.children().stream()
                        .filter(child -> child instanceof net.minecraft.client.gui.components.Button button
                                && button.getMessage().getString().equals("Starters")).findFirst().orElseThrow();
                starters.onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0));
                if (!area.getValue().startsWith("// Starter: ")) {
                    throw new AssertionError("The Starters button did not load a starter program");
                }
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Z,
                        org.lwjgl.sdl.SDLKeycode.SDLK_Z, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                if (!area.getValue().equals(original)) {
                    throw new AssertionError("Undo did not restore the draft replaced by a starter");
                }
                // Typing on NAME. after a device constant offers its events and inserts a whole handler.
                area.setValue(original + "\nconst gate = detectors.named(\"gate\")\non gate.entityE");
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_SPACE,
                        org.lwjgl.sdl.SDLKeycode.SDLK_SPACE, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RETURN,
                        org.lwjgl.sdl.SDLKeycode.SDLK_RETURN, 0));
                if (!area.getValue().endsWith("on gate.entityEnter(entity) {\n    \n}")) {
                    throw new AssertionError("Event completion did not insert a handler: " + area.getValue());
                }
                area.setValue(original + "\non startup { print(Direction.SO");
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_SPACE,
                        org.lwjgl.sdl.SDLKeycode.SDLK_SPACE, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RETURN,
                        org.lwjgl.sdl.SDLKeycode.SDLK_RETURN, 0));
                if (!area.getValue().endsWith("Direction.SOUTH")) {
                    throw new AssertionError("Constant completion failed: " + area.getValue());
                }
                area.setValue(original + "\non startup { json.str");
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_SPACE,
                        org.lwjgl.sdl.SDLKeycode.SDLK_SPACE, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RETURN,
                        org.lwjgl.sdl.SDLKeycode.SDLK_RETURN, 0));
                if (!area.getValue().endsWith("json.stringify")) {
                    throw new AssertionError("Completion did not replace the partial identifier: " + area.getValue());
                }
                area.setValue(original + "\non startup { storage.ge");
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_SPACE,
                        org.lwjgl.sdl.SDLKeycode.SDLK_SPACE, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RETURN,
                        org.lwjgl.sdl.SDLKeycode.SDLK_RETURN, 0));
                if (!area.getValue().endsWith("storage.getSlot")) {
                    throw new AssertionError("Dynamic completion did not preserve the receiver");
                }
                area.setValue(original + "\n// from editor ");
                if (!editor.charTyped(new net.minecraft.client.input.CharacterEvent(0x1f680))
                        || !area.getValue().endsWith("🚀")) {
                    throw new AssertionError("Focused native editor did not accept supplementary Unicode");
                }
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Z,
                        org.lwjgl.sdl.SDLKeycode.SDLK_Z, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                if (area.getValue().endsWith("🚀")) { throw new AssertionError("Undo did not restore the previous draft"); }
                editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_Y,
                        org.lwjgl.sdl.SDLKeycode.SDLK_Y, org.lwjgl.sdl.SDLKeycode.SDL_KMOD_CTRL));
                if (!area.getValue().endsWith("🚀")) { throw new AssertionError("Redo did not restore the emoji"); }
                var apply = (net.minecraft.client.gui.components.Button) editor.children().stream()
                        .filter(child -> child instanceof net.minecraft.client.gui.components.Button button
                                && button.getMessage().getString().equals("Save & Apply")).findFirst().orElseThrow();
                apply.onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0));
            });
            singleplayer.getServer().waitFor(server -> {
                try {
                    return dev.wiktorfila.filasignal.fabric.computer.ProgramEditing
                            .inspect(server.getPlayerList().getPlayer(playerId), root).source().endsWith("🚀");
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                }
            });
            for (int i = 0; i < 6; i++) { context.waitTick(); }
            context.runOnClient(client -> {
                var editor = client.gui.screen();
                var area = (net.minecraft.client.gui.components.MultiLineEditBox) editor.children().stream()
                        .filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).findFirst().orElseThrow();
                var base = area.getValue() + "\n// ";
                int limit = dev.wiktorfila.filasignal.fabric.computer.ProgramEditing.MAX_SOURCE;
                area.setValue(base);
                String paste = "\u0800".repeat(limit - base.length());
                long started = System.nanoTime();
                ((dev.wiktorfila.filasignal.fabric.editor.StyledEditorInput) area).filaModel().insertText(paste);
                long inserted = System.nanoTime();
                editor.resize(editor.width - 10, editor.height - 10);
                org.slf4j.LoggerFactory.getLogger("filasignal/editor-test").info(
                        "Maximum draft: {} ns native insertion, {} ns resize (single sample)",
                        inserted - started, System.nanoTime() - inserted);
                var apply = (net.minecraft.client.gui.components.Button) editor.children().stream()
                        .filter(child -> child instanceof net.minecraft.client.gui.components.Button button
                                && button.getMessage().getString().equals("Save & Apply")).findFirst().orElseThrow();
                apply.onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0));
                measureDrafts();
            });
            singleplayer.getServer().waitFor(server -> {
                try {
                    var source = dev.wiktorfila.filasignal.fabric.computer.ProgramEditing
                            .inspect(server.getPlayerList().getPlayer(playerId), root).source();
                    return source.length() == 16_384 && source.endsWith("\u0800");
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                }
            });
            for (int i = 0; i < 6; i++) { context.waitTick(); }
            singleplayer.getServer().runOnServer(server -> {
                var owner = server.getPlayerList().getPlayer(playerId);
                var program = dev.wiktorfila.filasignal.fabric.computer.ProgramEditing.inspect(owner, root);
                dev.wiktorfila.filasignal.fabric.computer.ProgramEditing.submit(owner, root, program.revision(),
                        program.source().substring(0, program.source().length() - 1) + "b", true);
            });
            context.runOnClient(client -> {
                var editor = client.gui.screen();
                var apply = (net.minecraft.client.gui.components.Button) editor.children().stream()
                        .filter(child -> child instanceof net.minecraft.client.gui.components.Button button
                                && button.getMessage().getString().equals("Save & Apply")).findFirst().orElseThrow();
                apply.onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0));
            });
            context.waitFor(client -> client.gui.screen().getNarrationMessage().getString().contains("Program changed on the server"));
            context.runOnClient(client -> {
                var area = (net.minecraft.client.gui.components.MultiLineEditBox) client.gui.screen().children().stream()
                        .filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).findFirst().orElseThrow();
                if (!area.getValue().endsWith("\u0800")) {
                    throw new AssertionError("Stale reply replaced the local draft");
                }
                client.gui.setScreen(null);
            });
            for (int i = 0; i < 6; i++) { context.waitTick(); }
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                var hit = new BlockHitResult(Vec3.atCenterOf(router), Direction.UP, router, false);
                player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
            });
            context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.DeviceScreen);
            for (int i = 0; i < 6; i++) { context.waitTick(); }
            context.runOnClient(client -> {
                var config = (dev.wiktorfila.filasignal.fabric.editor.DeviceScreen) client.gui.screen();
                var panel = config.getDescription().getRootPanel();
                var field = (io.github.cottonmc.cotton.gui.widget.WTextField) panel.streamChildren().flatMap(IntegratedWorldTest::all)
                        .filter(io.github.cottonmc.cotton.gui.widget.WTextField.class::isInstance).findFirst().orElseThrow();
                field.setText("screen_router");
                panel.streamChildren().flatMap(IntegratedWorldTest::all).filter(child -> child instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                        && button.getLabel().getString().equals("Save alias")).map(io.github.cottonmc.cotton.gui.widget.WButton.class::cast)
                        .findFirst().orElseThrow().getOnClick().run();
            });
            context.waitFor(client -> {
                var config = (dev.wiktorfila.filasignal.fabric.editor.DeviceScreen) client.gui.screen();
                return config.getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all).anyMatch(child ->
                        child instanceof io.github.cottonmc.cotton.gui.widget.WLabel label
                        && label.getText().getString().startsWith("Settings updated"));
            });
            context.runOnClient(client -> client.gui.setScreen(null));
            singleplayer.getServer().runOnServer(server -> {
                if (!server.getPlayerList().getPlayer(playerId).gameMode.destroyBlock(router)) {
                    throw new AssertionError("Integrated owner could not break the interface");
                }
            });
            context.waitFor(client -> client.level.getBlockState(router).isAir());
            verifyBookImport(context, singleplayer, playerId, root);
            verifyDebugger(context, singleplayer, playerId, root);
            verifyDisplayForm(context, singleplayer, playerId, root.south(2));
            verifyDriveEditing(context, singleplayer, playerId, root);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | java.io.IOException error) {
            throw new AssertionError("Integrated player command failed", error);
        }
    }

    private static void verifyBookImport(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
            java.util.UUID playerId, BlockPos root) throws java.io.IOException {
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        String source = "global starts = 0\non startup { starts = starts + 1 }\n// imported from book";
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayer(playerId);
            var book = book(source);
            player.setItemInHand(InteractionHand.MAIN_HAND, book);
            var hit = new BlockHitResult(Vec3.atCenterOf(root), Direction.UP, root, false);
            player.gameMode.useItemOn(player, player.level(), book, InteractionHand.MAIN_HAND, hit);
        });
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ComputerScreen);
        context.runOnClient(client -> {
            var dashboard = (dev.wiktorfila.filasignal.fabric.editor.ComputerScreen) client.gui.screen();
            dashboard.getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all)
                    .filter(widget -> widget instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                            && button.getLabel().getString().equals("Open Editor"))
                    .map(io.github.cottonmc.cotton.gui.widget.WButton.class::cast).findFirst().orElseThrow().getOnClick().run();
        });
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        pressImport(context);
        context.waitFor(client -> client.gui.screen().getNarrationMessage().getString().contains("Program saved"));
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (!dev.wiktorfila.filasignal.fabric.computer.ProgramEditing.inspect(player, root).source().equals(source + "\n")) {
                throw new AssertionError("Book source was not installed");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, book("not valid"));
        });
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        pressImport(context);
        context.waitFor(client -> client.gui.screen().getNarrationMessage().getString().contains("1:1"));
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (!dev.wiktorfila.filasignal.fabric.computer.ProgramEditing.inspect(player, root).source().equals(source + "\n")) {
                throw new AssertionError("Invalid book overwrote running source");
            }
        });
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    /** Drives the debug panel through the real screen: breakpoint, restart, pause, resume and release on close. */
    private static void verifyDebugger(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
            java.util.UUID playerId, BlockPos root) {
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayer(playerId);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            var hit = new BlockHitResult(Vec3.atCenterOf(root), Direction.UP, root, false);
            player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        });
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ComputerScreen);
        context.runOnClient(client -> ((dev.wiktorfila.filasignal.fabric.editor.ComputerScreen) client.gui.screen())
                .getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all)
                .filter(widget -> widget instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                        && button.getLabel().getString().equals("Open Editor"))
                .map(io.github.cottonmc.cotton.gui.widget.WButton.class::cast).findFirst().orElseThrow().getOnClick().run());
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ProgramScreen);
        press(context, "Debug ▸");
        for (int i = 0; i < 10; i++) { context.waitTick(); }
        context.runOnClient(client -> {
            var editor = client.gui.screen();
            var area = (net.minecraft.client.gui.components.MultiLineEditBox) editor.children().stream()
                    .filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).findFirst().orElseThrow();
            int second = area.getValue().indexOf('\n') + 1;
            ((dev.wiktorfila.filasignal.fabric.editor.StyledEditorInput) area).filaModel()
                    .seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, second);
            editor.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_F9,
                    org.lwjgl.sdl.SDLKeycode.SDLK_F9, 0));
            if (!editor.getNarrationMessage().getString().contains("Breakpoint on line 2")) {
                throw new AssertionError("F9 did not set a breakpoint: " + editor.getNarrationMessage().getString());
            }
        });
        for (int i = 0; i < 10; i++) { context.waitTick(); }
        press(context, "Start / Restart");
        context.waitFor(client -> client.gui.screen().getNarrationMessage().getString().contains("Paused at line 2"));
        world.getServer().runOnServer(server -> {
            if (!state(server, playerId, root).startsWith("Paused at line 2")) {
                throw new AssertionError("Server did not report the pause");
            }
        });
        context.runOnClient(client -> client.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(
                org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_F5, org.lwjgl.sdl.SDLKeycode.SDLK_F5, 0)));
        // Client ticks must run so the paced request leaves the client.
        for (int i = 0; i < 12; i++) { context.waitTick(); }
        world.getServer().runOnServer(server -> {
            if (!state(server, playerId, root).startsWith("Running")) {
                throw new AssertionError("F5 did not resume: " + state(server, playerId, root));
            }
        });
        // Closing the editor releases the session, so the breakpoint no longer pauses a restart.
        context.runOnClient(client -> client.gui.screen().onClose());
        context.waitFor(client -> client.gui.screen() == null);
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        world.getServer().runOnServer(server -> {
            try {
                var player = server.getPlayerList().getPlayer(playerId);
                var program = dev.wiktorfila.filasignal.fabric.computer.ProgramEditing.inspect(player, root);
                dev.wiktorfila.filasignal.fabric.computer.ComputerLifecycle.program(player.level(), root, program.source(), false);
            } catch (java.io.IOException error) {
                throw new AssertionError(error);
            }
        });
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        world.getServer().runOnServer(server -> {
            if (!state(server, playerId, root).startsWith("Running")) {
                throw new AssertionError("A detached debugger still paused the program: " + state(server, playerId, root));
            }
        });
    }

    /** Places a Display Module, edits it through its LibGui form and checks the server-side text. */
    private static void verifyDisplayForm(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
            java.util.UUID playerId, BlockPos display) {
        world.getServer().runOnServer(server -> use(server.getPlayerList().getPlayer(playerId), "display_module", display.below()));
        context.waitFor(client -> client.level.getBlockState(display).getBlock() != net.minecraft.world.level.block.Blocks.AIR);
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            var hit = new BlockHitResult(Vec3.atCenterOf(display), Direction.UP, display, false);
            player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        });
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.DeviceScreen);
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        context.runOnClient(client -> {
            var panel = ((dev.wiktorfila.filasignal.fabric.editor.DeviceScreen) client.gui.screen()).getDescription().getRootPanel();
            var fields = panel.streamChildren().flatMap(IntegratedWorldTest::all).filter(io.github.cottonmc.cotton.gui.widget.WTextField.class::isInstance)
                    .map(io.github.cottonmc.cotton.gui.widget.WTextField.class::cast).toList();
            // Name, display text and player name, in layout order.
            fields.get(1).setText("Hello|from the form");
            panel.streamChildren().flatMap(IntegratedWorldTest::all).filter(child -> child instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                    && button.getLabel().getString().equals("Show")).map(io.github.cottonmc.cotton.gui.widget.WButton.class::cast)
                    .findFirst().orElseThrow().getOnClick().run();
        });
        context.waitFor(client -> ((dev.wiktorfila.filasignal.fabric.editor.DeviceScreen) client.gui.screen()).getDescription()
                .getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all).anyMatch(child -> child instanceof io.github.cottonmc.cotton.gui.widget.WLabel label
                        && label.getText().getString().startsWith("Settings updated")));
        world.getServer().runOnServer(server -> {
            var text = dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle.displayText(server.overworld(), display);
            if (!text.equals(java.util.List.of("Hello", "from the form"))) {
                throw new AssertionError("Display form did not update the device: " + text);
            }
        });
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    /** Inserts a blank drive, chooses its hardware type and programs it through the dashboard and the editor. */
    private static void verifyDriveEditing(ClientGameTestContext context,
            net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext world,
            java.util.UUID playerId, BlockPos root) {
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        world.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            var drive = new ItemStack(dev.wiktorfila.filasignal.fabric.drive.Drives.DRIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, drive);
            var hit = new BlockHitResult(Vec3.atCenterOf(root), Direction.UP, root, false);
            player.gameMode.useItemOn(player, player.level(), drive, InteractionHand.MAIN_HAND, hit);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.gameMode.useItemOn(player, player.level(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
        });
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ComputerScreen);
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        clickDashboard(context, "Type: choose");
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ComputerScreen dashboard
                && dashboard.getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all).anyMatch(child ->
                    child instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                        && button.getLabel().getString().equals("Type: entity_detector")));
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        clickDashboard(context, "Edit drive");
        context.waitFor(client -> client.gui.screen() instanceof dev.wiktorfila.filasignal.fabric.editor.ProgramScreen editor
                && editor.getNarrationMessage().getString().contains("in computer"));
        for (int i = 0; i < 6; i++) { context.waitTick(); }
        String program = "global seen = 0\non timer.every(20) { count, err = device.count(\"all\") }\n";
        context.runOnClient(client -> {
            var editor = client.gui.screen();
            var area = (net.minecraft.client.gui.components.MultiLineEditBox) editor.children().stream()
                    .filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).findFirst().orElseThrow();
            area.setValue(program);
        });
        press(context, "Save & Apply");
        context.waitFor(client -> client.gui.screen().getNarrationMessage().getString().contains("Program saved"));
        world.getServer().runOnServer(server -> {
            var tag = dev.wiktorfila.filasignal.fabric.drive.Drives.tag(driveOf(server.overworld(), root));
            var record = dev.wiktorfila.filasignal.fabric.drive.DriveStore.of(server).find(tag.id());
            if (record == null || !record.source().equals(program) || !tag.target().equals("entity_detector")) {
                throw new AssertionError("The drive program was not written through the editor");
            }
        });
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    private static ItemStack driveOf(net.minecraft.server.level.ServerLevel level, BlockPos computer) {
        var tag = level.getBlockEntity(computer).saveWithoutMetadata(level.registryAccess());
        return ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                tag.get("filasignal_drive")).getOrThrow();
    }

    private static void clickDashboard(ClientGameTestContext context, String label) {
        context.runOnClient(client -> ((dev.wiktorfila.filasignal.fabric.editor.ComputerScreen) client.gui.screen())
                .getDescription().getRootPanel().streamChildren().flatMap(IntegratedWorldTest::all)
                .filter(widget -> widget instanceof io.github.cottonmc.cotton.gui.widget.WButton button
                        && button.getLabel().getString().equals(label))
                .map(io.github.cottonmc.cotton.gui.widget.WButton.class::cast).findFirst().orElseThrow().getOnClick().run());
    }

    private static String state(net.minecraft.server.MinecraftServer server, java.util.UUID playerId, BlockPos root) {
        try {
            return dev.wiktorfila.filasignal.fabric.computer.ProgramEditing
                    .inspect(server.getPlayerList().getPlayer(playerId), root).state();
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void press(ClientGameTestContext context, String label) {
        context.runOnClient(client -> client.gui.screen().children().stream()
                .filter(widget -> widget instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getString().equals(label))
                .map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow()
                .onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0)));
    }

    private static void pressImport(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.screen().children().stream()
                .filter(widget -> widget instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getString().equals("From Book"))
                .map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow()
                .onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0)));
    }

    private static ItemStack book(String source) {
        var book = new ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
                new net.minecraft.world.item.component.WritableBookContent(java.util.List.of(
                        net.minecraft.server.network.Filterable.passThrough(source))));
        return book;
    }

    private static void measureDrafts() {
        String sample = "fn main() {} // [comment]";
        var palette = new dev.wiktorfila.filasignal.fabric.editor.EditorPalette(sample);
        int bracket = sample.indexOf('(');
        int normal = palette.at(bracket);
        palette.caret(bracket);
        if (palette.at(bracket) == normal || palette.at(bracket) != palette.at(bracket + 1)) {
            throw new AssertionError("Lexer bracket pair was not highlighted");
        }
        int comment = sample.indexOf('[');
        int commentColor = palette.at(comment);
        palette.caret(comment);
        if (palette.at(comment) != commentColor) {
            throw new AssertionError("Bracket inside comment became a matching delimiter");
        }
        String line = "on timer.every(20) { print(15) }\n";
        for (int size : new int[]{512, 4096, 16_384}) {
            String draft = line.repeat(size / line.length());
            for (int i = 0; i < 3; i++) {
                new dev.wiktorfila.filasignal.fabric.editor.EditorPalette(draft);
            }
            long start = System.nanoTime();
            for (int i = 0; i < 8; i++) {
                new dev.wiktorfila.filasignal.fabric.editor.EditorPalette(draft);
            }
            org.slf4j.LoggerFactory.getLogger("filasignal/editor-test").info(
                    "Draft analysis {} UTF-16 units: {} ns average (8 runs)", draft.length(), (System.nanoTime() - start) / 8);
        }
    }

    private static void use(ServerPlayer player, String itemName, BlockPos hitPos) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("filasignal", itemName));
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(hitPos).add(0, 0.5, 0), Direction.UP, hitPos, false);
        var result = player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            throw new AssertionError("Integrated player could not use " + itemName + " at " + hitPos);
        }
    }
}
