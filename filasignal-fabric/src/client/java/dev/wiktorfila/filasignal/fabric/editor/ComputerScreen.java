package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.fabric.computer.EditorNetwork;
import io.github.cottonmc.cotton.gui.client.CottonClientScreen;
import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WLabel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** LibGui entry screen for an authorized controller. */
public final class ComputerScreen extends CottonClientScreen {
    private static final int ACCENT = 0xff69ded1;
    private static final int MUTED = 0xffaab7c2;

    private java.util.UUID renameRequest;

    void acceptRename(EditorNetwork.Reply reply) {
        if (!reply.requestId().equals(renameRequest)) { return; }
        renameRequest = null;
        if (reply.success()) {
            Minecraft.getInstance().gui.setScreen(new ComputerScreen(reply));
        } else {
            showError(reply.status());
        }
    }

    private void showError(String error) {
        ((Dashboard) getDescription()).status
                .setText(Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(error, 248)));
    }

    ComputerScreen(EditorNetwork.Reply snapshot) {
        super(Component.literal("Programmable Computer"), description(snapshot));
    }

    /** The dashboard layout, keeping the status line so replies can update it. */
    private static final class Dashboard extends LightweightGuiDescription {
        private WLabel status;
    }

    private static LightweightGuiDescription description(EditorNetwork.Reply snapshot) {
        var description = new Dashboard();
        description.setTitleVisible(false);
        description.setUseDefaultRootBackground(false);
        var panel = new WPlainPanel();
        theme(panel, 276, 302);
        String name = snapshot.dashboard().lines().findFirst().orElse("");
        panel.add(new WLabel(Component.literal(name.isEmpty() ? "Programmable Computer" : name), ACCENT), 14, 12);
        panel.add(new WButton(Component.literal("?")).setOnClick(() -> HelpLinks.open(HelpLinks.FIRST_PROJECT)), 242, 8, 20, 20);
        String state = snapshot.state().isEmpty() ? (snapshot.running() ? "Running" : "Not running") : snapshot.state();
        panel.add(new WLabel(Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(state, 248)),
                snapshot.running() ? ACCENT : MUTED), 14, 35);
        panel.add(new WLabel(Component.literal("computer.fsig  ·  " + snapshot.pos().toShortString()), MUTED), 14, 48);
        int y = 63;
        for (String line : snapshot.dashboard().lines().skip(1).filter(line -> !line.startsWith("Programmable: "))
                .limit(7).toList()) {
            String clipped = Minecraft.getInstance().font.plainSubstrByWidth(line, 248);
            // Offline devices and failed programs stand out; diagnostics stay quiet.
            int color = line.contains("offline") || line.contains("failed") ? 0xffe6b36a : MUTED;
            panel.add(new WLabel(Component.literal(clipped), line.startsWith("Diagnostics") ? 0xff7c8994 : color), 14, y);
            y += 11;
        }
        description.status = new WLabel(Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(snapshot.status(), 248)),
                MUTED);
        panel.add(description.status, 14, 144);
        panel.add(new WButton(Component.literal("Open Editor")).setOnClick(() ->
                Minecraft.getInstance().gui.setScreen(new ProgramScreen(snapshot))), 14, 160, 248, 20);
        var rename = new io.github.cottonmc.cotton.gui.widget.WTextField(Component.literal("Computer name"));
        rename.setMaxLength(32);
        rename.setText(name);
        panel.add(rename, 14, 184, 156, 20);
        panel.add(new WButton(Component.literal("Save name")).setOnClick(() -> {
            if (Minecraft.getInstance().gui.screen() instanceof ComputerScreen active) {
                active.renameRequest = java.util.UUID.randomUUID();
                RequestGate.send(new EditorNetwork.Request(
                        snapshot.pos(), snapshot.revision(), EditorNetwork.Request.RENAME,
                        active.renameRequest, rename.getText()));
            }
        }), 176, 184, 86, 20);
        drive(panel, snapshot);
        var root = fit(panel);
        description.setRootPanel(root);
        root.validate(description);
        return description;
    }

    /**
     * The drive slot and remote program controls. A drive in the slot is programmed here and run elsewhere. A remote
     * program is the drive installed in a named device on this computer's network.
     */
    private static void drive(WPlainPanel panel, EditorNetwork.Reply snapshot) {
        String[] slot = snapshot.drive().split("\t", -1);
        boolean present = !snapshot.drive().isEmpty();
        String target = slot.length > 1 ? slot[1] : "";
        String summary;
        if (!present) {
            summary = "Drive slot empty: use a data drive on the computer";
        } else if (slot[0].equals("blank")) {
            summary = "Drive: blank";
        } else {
            summary = "Drive " + (slot[0].isEmpty() ? "(no label)" : slot[0]) + " · " + (target.isEmpty() ? "no type" : target)
                    + " · " + (slot.length > 2 ? slot[2] : "") + " · " + (slot.length > 3 ? slot[3] : "0") + " B stored"
                    + (slot.length > 4 && !slot[4].isEmpty() ? " · " + slot[4] : "");
        }
        panel.add(new WLabel(Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(summary, 248)), MUTED), 14, 210);
        if (present) {
            panel.add(new WButton(Component.literal("Edit drive")).setOnClick(() -> send(snapshot, EditorNetwork.Request.OPEN,
                    "", "drive")), 14, 222, 80, 20);
            panel.add(new WButton(Component.literal("Eject")).setOnClick(() -> send(snapshot, EditorNetwork.Request.DRIVE_EJECT,
                    "", "")), 98, 222, 56, 20);
            var kinds = dev.wiktorfila.filasignal.fabric.peripheral.PeripheralLifecycle.programmableKinds();
            String next = kinds.get((kinds.indexOf(target) + 1) % kinds.size());
            panel.add(new WButton(Component.literal("Type: " + (target.isEmpty() ? "choose" : target))).setOnClick(() ->
                    send(snapshot, EditorNetwork.Request.DRIVE_TARGET, next, "")), 158, 222, 104, 20);
            var label = new io.github.cottonmc.cotton.gui.widget.WTextField(Component.literal("Drive label"));
            label.setMaxLength(32);
            label.setText(slot[0].equals("blank") ? "" : slot[0]);
            panel.add(label, 14, 246, 156, 20);
            panel.add(new WButton(Component.literal("Save label")).setOnClick(() ->
                    send(snapshot, EditorNetwork.Request.DRIVE_LABEL, label.getText(), "")), 176, 246, 86, 20);
        }
        var device = new io.github.cottonmc.cotton.gui.widget.WTextField(Component.literal("Device name"));
        device.setMaxLength(32);
        // Programmable devices on the network, so a player can pick one instead of typing its name.
        var names = snapshot.dashboard().lines().filter(line -> line.startsWith("Programmable: "))
                .map(line -> line.substring("Programmable: ".length())).filter(line -> !line.isEmpty())
                .flatMap(line -> java.util.Arrays.stream(line.split(","))).toList();
        if (!names.isEmpty()) {
            device.setText(names.getFirst());
            var index = new int[]{0};
            panel.add(new WButton(Component.literal("▶")).setOnClick(() -> {
                index[0] = (index[0] + 1) % names.size();
                device.setText(names.get(index[0]));
            }), 150, 272, 20, 20);
        }
        panel.add(device, 14, 272, names.isEmpty() ? 156 : 132, 20);
        panel.add(new WButton(Component.literal("Edit remote")).setOnClick(() ->
                send(snapshot, EditorNetwork.Request.OPEN, "", "remote:" + device.getText())), 176, 272, 86, 20);
    }

    private static void send(EditorNetwork.Reply snapshot, int action, String value, String target) {
        if (Minecraft.getInstance().gui.screen() instanceof ComputerScreen active) {
            active.renameRequest = java.util.UUID.randomUUID();
            RequestGate.send(new EditorNetwork.Request(snapshot.pos(), snapshot.revision(), action, active.renameRequest,
                    value, target));
        }
    }

    /**
     * The panel itself when it fits the window, otherwise a scrolling view of it. Tall screens, such as a device form
     * with a drive section, then stay usable at large GUI scales and in small windows. Measured when the screen opens.
     */
    static io.github.cottonmc.cotton.gui.widget.WPanel fit(WPlainPanel panel) {
        int available = Minecraft.getInstance().getWindow().getGuiScaledHeight() - 16;
        if (panel.getHeight() <= available) {
            return panel;
        }
        var scroll = new io.github.cottonmc.cotton.gui.widget.WScrollPanel(panel)
                .setScrollingHorizontally(net.fabricmc.fabric.api.util.TriState.FALSE);
        var root = new WPlainPanel();
        int width = panel.getWidth() + 10;
        theme(root, width, available);
        root.add(scroll, 0, 0, width, available);
        return root;
    }

    static void theme(WPlainPanel panel, int width, int height) {
        panel.setSize(width, height);
        panel.setBackgroundPainter((graphics, x, y, widget) -> {
            graphics.fill(x, y, x + widget.getWidth(), y + widget.getHeight(), 0xff20262d);
            graphics.fill(x, y, x + widget.getWidth(), y + 1, ACCENT);
        });
    }

    @Override public boolean isPauseScreen() { return false; }
}
