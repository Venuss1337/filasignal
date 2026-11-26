package dev.wiktorfila.filasignal.fabric.editor;

import dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork;
import dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork.Edit;
import dev.wiktorfila.filasignal.fabric.peripheral.DeviceConfigNetwork.Form;
import io.github.cottonmc.cotton.gui.client.CottonClientScreen;
import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription;
import io.github.cottonmc.cotton.gui.widget.WButton;
import io.github.cottonmc.cotton.gui.widget.WLabel;
import io.github.cottonmc.cotton.gui.widget.WPlainPanel;
import io.github.cottonmc.cotton.gui.widget.WText;
import io.github.cottonmc.cotton.gui.widget.WTextField;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * LibGui configuration form for every peripheral kind. A shared header shows the name, network status, access and
 * live readings. Each kind adds at most two rows of its own controls. The server owns all settings and rights;
 * this screen only sends requests and shows the server's replies.
 */
public final class DeviceScreen extends CottonClientScreen {
    DeviceScreen(Form snapshot) {
        super(Component.literal(snapshot.title()), new Layout(snapshot));
    }

    void accept(Form form) {
        ((Layout) getDescription()).accept(form);
    }

    @Override public boolean isPauseScreen() { return false; }

    private static final class Layout extends LightweightGuiDescription {
        private static final int WIDTH = 300;
        private static final int ACCENT = 0xff69ded1;
        private static final int MUTED = 0xffb7c5d0;
        private final Form model;
        private WLabel status;
        private WText readings;
        private WLabel drive;
        private WLabel problem;

        Layout(Form model) {
            this.model = model;
            setTitleVisible(false);
            setUseDefaultRootBackground(false);
            var panel = new WPlainPanel();
            if (model.kind().equals("CONTROL_PANEL") && model.settings().endsWith("\tvisitor")) {
                // A visitor to a public panel only presses its buttons.
                ComputerScreen.theme(panel, WIDTH, 120);
                panel.add(new WLabel(Component.literal(model.title() + (model.alias().isEmpty() ? "" : ": " + model.alias())), ACCENT), 14, 10);
                String[] labels = model.settings().split("\t", -1);
                for (int i = 0; i < 4; i++) {
                    int number = i + 1;
                    button(panel, setting(labels, number), 14 + (i % 2) * 138, 30 + (i / 2) * 26, 134,
                            () -> send(DeviceConfigNetwork.PANEL_PRESS, "", Integer.toString(number)));
                }
                readings = new WText(Component.literal(""), MUTED);
                status = new WLabel(Component.literal(crop(model.status())), MUTED);
                panel.add(status, 14, 90);
                setRootPanel(panel);
                panel.validate(this);
                return;
            }
            boolean programmable = model.program().startsWith("1");
            ComputerScreen.theme(panel, WIDTH, programmable ? 290 : 214);
            panel.add(new WLabel(Component.literal(model.title()), ACCENT), 14, 10);
            // Help for this device opens its documentation page after Minecraft's link confirmation.
            panel.add(new WButton(Component.literal("?")).setOnClick(() -> HelpLinks.open(HelpLinks.device(model.kind()))),
                    WIDTH - 34, 4, 20, 20);
            var alias = field(panel, "Device name", model.alias(), 14, 24, 176, 32);
            button(panel, "Save alias", 194, 24, 92, () -> send(DeviceConfigNetwork.ALIAS, alias.getText(), ""));
            controls(panel, model.kind(), model.settings());
            readings = new WText(Component.literal(model.readings()), MUTED);
            panel.add(readings, 14, 108, 272, 52);
            status = new WLabel(Component.literal(crop(model.status())), MUTED);
            panel.add(status, 14, 164);
            button(panel, "Refresh", 14, 180, 70, () -> send(DeviceConfigNetwork.REFRESH, "", ""));
            var player = field(panel, "Player name", "", 88, 180, 102, 36);
            button(panel, "Trust", 194, 180, 44, () -> send(DeviceConfigNetwork.TRUST, player.getText(), "trust"));
            button(panel, "Untrust", 242, 180, 44, () -> send(DeviceConfigNetwork.TRUST, player.getText(), "untrust"));
            if (programmable) {
                program(panel);
            }
            var root = ComputerScreen.fit(panel);
            setRootPanel(root);
            root.validate(this);
        }

        private void controls(WPlainPanel panel, String kind, String raw) {
            String[] settings = raw.split(",", -1);
            switch (kind) {
                case "SENSOR" -> {
                    label(panel, "Player range / poll ticks");
                    var range = field(panel, "Radius", setting(settings, 0), 14, 62, 68, 4);
                    var period = field(panel, "Period", setting(settings, 1), 88, 62, 68, 4);
                    button(panel, "Save sensor", 162, 62, 124, () -> send(DeviceConfigNetwork.SENSOR, range.getText(), period.getText()));
                }
                case "INTERFACE", "INVENTORY_MODULE" -> {
                    label(panel, "World face / port or inventory name");
                    var side = field(panel, "Face", "north", 14, 62, 62, 8);
                    var name = field(panel, "Name", "", 80, 62, 106, 32);
                    if (kind.equals("INTERFACE")) {
                        button(panel, "Input", 190, 62, 46, () -> send(DeviceConfigNetwork.PORT_INPUT, name.getText(), side.getText()));
                        button(panel, "Output", 240, 62, 46, () -> send(DeviceConfigNetwork.PORT_OUTPUT, name.getText(), side.getText()));
                    }
                    // Interfaces put the inventory action on a second row. Modules only approve inventories.
                    boolean second = kind.equals("INTERFACE");
                    button(panel, "Inventory", second ? 14 : 190, second ? 84 : 62, 96,
                            () -> send(DeviceConfigNetwork.INVENTORY, name.getText(), side.getText()));
                }
                case "ENTITY_DETECTOR" -> {
                    label(panel, "Detection radius (1–16 blocks) and which entities events report");
                    var radius = field(panel, "Radius", setting(settings, 0), 14, 62, 68, 4);
                    button(panel, "Save radius", 88, 62, 110, () -> send(DeviceConfigNetwork.RADIUS, radius.getText(), ""));
                    cycle(panel, "Events report: ", java.util.List.of("all", "player", "hostile", "passive", "item", "other"),
                            setting(settings, 1).isEmpty() ? "all" : setting(settings, 1), 14, 84, 184,
                            choice -> send(DeviceConfigNetwork.DETECT_FILTER, choice, ""));
                }
                case "ENVIRONMENT_SENSOR" -> {
                    label(panel, "Light threshold for the darkened and brightened events (1–15)");
                    var threshold = field(panel, "Threshold", setting(settings, 0), 14, 62, 68, 2);
                    button(panel, "Save threshold", 88, 62, 110, () -> send(DeviceConfigNetwork.THRESHOLD, threshold.getText(), ""));
                }
                case "STORAGE_SERVER" -> {
                    label(panel, "Shared storage for programs on this network");
                    cycle(panel, "Device programs: ", java.util.List.of("write", "read"), setting(settings, 0), 14, 62, 150,
                            choice -> send(DeviceConfigNetwork.SERVER_WRITES, choice, ""));
                    button(panel, "View data", 168, 62, 60, () -> send(DeviceConfigNetwork.STORAGE_VIEW, "", ""));
                    button(panel, "Clear data", 232, 62, 54, () -> send(DeviceConfigNetwork.STORAGE_CLEAR, "", ""));
                }
                case "ACCESS_CONTROLLER" -> {
                    label(panel, "Who may enter, open time (s) and output side (all or a face)");
                    cycle(panel, "", java.util.List.of("restricted", "public"), setting(settings, 0), 14, 62, 84,
                            choice -> send(DeviceConfigNetwork.PUBLIC_USE, choice, ""));
                    var seconds = field(panel, "Seconds", setting(settings, 1), 102, 62, 34, 2);
                    var face = field(panel, "Side", setting(settings, 2), 140, 62, 56, 5);
                    button(panel, "Save", 200, 62, 40, () -> send(DeviceConfigNetwork.ACCESS_OUTPUT, seconds.getText(), face.getText()));
                    button(panel, "Test", 244, 62, 42, () -> send(DeviceConfigNetwork.ACCESS_TEST, "", ""));
                    var player = field(panel, "Player name", "", 14, 84, 136, 16);
                    button(panel, "Allow", 154, 84, 64, () -> send(DeviceConfigNetwork.ACCESS_ALLOW, player.getText(), "add"));
                    button(panel, "Remove", 222, 84, 64, () -> send(DeviceConfigNetwork.ACCESS_ALLOW, player.getText(), "remove"));
                }
                case "CONTROL_PANEL" -> {
                    String[] labels = raw.split("\t", -1);
                    for (int i = 0; i < 4; i++) {
                        int number = i + 1;
                        button(panel, setting(labels, number), 14 + i * 68, 50, 66,
                                () -> send(DeviceConfigNetwork.PANEL_PRESS, "", Integer.toString(number)));
                    }
                    var choice = new int[]{1};
                    var text = field(panel, "Label", setting(labels, 1), 14, 74, 96, 24);
                    cycle(panel, "Button ", java.util.List.of("1", "2", "3", "4"), "1", 114, 74, 58, picked -> {
                        choice[0] = Integer.parseInt(picked);
                        text.setText(setting(labels, choice[0]));
                    });
                    button(panel, "Save label", 176, 74, 60, () -> send(DeviceConfigNetwork.PANEL_LABEL, text.getText(),
                            Integer.toString(choice[0])));
                    cycle(panel, "", java.util.List.of("restricted", "public"), setting(labels, 0), 240, 74, 46,
                            picked -> send(DeviceConfigNetwork.PUBLIC_USE, picked, ""));
                }
                case "WIRELESS_BRIDGE" -> {
                    var candidates = raw.isEmpty() ? new String[0] : raw.split("\t");
                    label(panel, candidates.length == 0 ? "No unpaired bridge of yours is loaded within 256 blocks"
                            : "Choose the bridge to pair with");
                    var ids = new java.util.ArrayList<String>();
                    var names = new java.util.ArrayList<String>();
                    for (String candidate : candidates) {
                        int split = candidate.indexOf(';');
                        ids.add(split < 0 ? candidate : candidate.substring(0, split));
                        names.add(split < 0 ? candidate : candidate.substring(split + 1));
                    }
                    var picked = new int[]{0};
                    if (!names.isEmpty()) {
                        cycle(panel, "", names, names.getFirst(), 14, 62, 172, name -> picked[0] = names.indexOf(name));
                        button(panel, "Pair", 190, 62, 46, () -> send(DeviceConfigNetwork.BRIDGE_PAIR, ids.get(picked[0]), ""));
                    }
                    button(panel, "Unpair", 240, 62, 46, () -> send(DeviceConfigNetwork.BRIDGE_UNPAIR, "", ""));
                }
                case "DISPLAY" -> {
                    label(panel, "Text (separate lines with |)");
                    var text = field(panel, "Display text", String.join(",", settings), 14, 62, 200, 512);
                    button(panel, "Show", 218, 62, 34, () -> send(DeviceConfigNetwork.DISPLAY_TEXT, text.getText(), ""));
                    button(panel, "Clear", 254, 62, 32, () -> send(DeviceConfigNetwork.DISPLAY_CLEAR, "", ""));
                }
                case "ALARM" -> {
                    label(panel, "Sound (bell, pling, chime, bass) / volume / seconds");
                    var sound = field(panel, "Sound", setting(settings, 0), 14, 62, 62, 8);
                    var volume = field(panel, "Volume", setting(settings, 1), 80, 62, 40, 3);
                    var seconds = field(panel, "Seconds", setting(settings, 2), 124, 62, 36, 2);
                    button(panel, "Save", 164, 62, 40, () -> send(DeviceConfigNetwork.ALARM_SETTINGS, sound.getText(),
                            volume.getText() + "," + seconds.getText()));
                    button(panel, "Test", 208, 62, 36, () -> send(DeviceConfigNetwork.ALARM_TEST, "on", ""));
                    button(panel, "Stop", 248, 62, 38, () -> send(DeviceConfigNetwork.ALARM_TEST, "off", ""));
                }
                case "REDSTONE_CONTROLLER" -> {
                    label(panel, "World face mode (outputs: " + (settings[0].isEmpty() ? "none" : String.join(", ", settings)) + ")");
                    var side = field(panel, "Face", "north", 14, 62, 62, 8);
                    button(panel, "Input", 80, 62, 60, () -> send(DeviceConfigNetwork.FACE_MODE, side.getText(), "input"));
                    button(panel, "Output", 144, 62, 60, () -> send(DeviceConfigNetwork.FACE_MODE, side.getText(), "output"));
                }
                default -> label(panel, "Readings refresh when you select Refresh");
            }
        }

        /**
         * Drive and program controls for devices that can run a program from a drive. Inserting a drive never starts it;
         * Start does, with the player's authority recorded. Edit opens the shared program editor on this drive.
         */
        private void program(WPlainPanel panel) {
            drive = new WLabel(Component.literal(""), ACCENT);
            panel.add(drive, 14, 208);
            problem = new WLabel(Component.literal(""), MUTED);
            panel.add(problem, 14, 220);
            showProgram(model.program());
            button(panel, "Edit program", 14, 234, 92, () -> send(DeviceConfigNetwork.PROGRAM_EDIT, "", ""));
            button(panel, "Start", 110, 234, 44, () -> send(DeviceConfigNetwork.PROGRAM_START, "", ""));
            button(panel, "Stop", 158, 234, 44, () -> send(DeviceConfigNetwork.PROGRAM_STOP, "", ""));
            button(panel, "Storage", 206, 234, 80, () -> send(DeviceConfigNetwork.STORAGE_VIEW, "", ""));
            String[] fields = model.program().split("\t", -1);
            var label = field(panel, "Drive label", fields.length > 1 ? fields[1] : "", 14, 258, 120, 32);
            button(panel, "Label", 138, 258, 50, () -> send(DeviceConfigNetwork.DRIVE_LABEL, label.getText(), ""));
            button(panel, "Eject", 192, 258, 44, () -> send(DeviceConfigNetwork.DRIVE_EJECT, "", ""));
            button(panel, "Clear data", 240, 258, 46, () -> send(DeviceConfigNetwork.STORAGE_CLEAR, "", ""));
        }

        private void showProgram(String program) {
            if (drive == null) {
                return;
            }
            String[] fields = program.split("\t", -1);
            boolean present = fields.length > 2 && !(fields[1].isEmpty() && fields[2].isEmpty());
            String summary = !present ? "No drive: use a data drive on this device"
                    : "Drive " + (fields[1].isEmpty() ? "(no label)" : fields[1]) + " · " + fields[3]
                    + (fields.length > 4 && !fields[4].isEmpty() ? " · " + fields[4] + " B stored" : "");
            drive.setText(Component.literal(crop(summary)));
            String error = fields.length > 5 ? fields[5] : "";
            String started = fields.length > 6 ? fields[6] : "";
            problem.setText(Component.literal(crop(!error.isEmpty() ? "Last error: " + error
                    : started.isEmpty() ? "" : "Started by " + started)));
        }

        /** A button that steps through fixed choices and reports each new choice. */
        private static void cycle(WPlainPanel panel, String prefix, java.util.List<String> choices, String current, int x, int y,
                                  int width, java.util.function.Consumer<String> chosen) {
            var index = new int[]{Math.max(0, choices.indexOf(current))};
            var button = new WButton(Component.literal(prefix + choices.get(index[0])));
            button.setOnClick(() -> {
                index[0] = (index[0] + 1) % choices.size();
                button.setLabel(Component.literal(prefix + choices.get(index[0])));
                chosen.accept(choices.get(index[0]));
            });
            panel.add(button, x, y, width, 20);
        }

        private static String setting(String[] settings, int index) {
            return index < settings.length ? settings[index] : "";
        }

        private static void label(WPlainPanel panel, String text) {
            panel.add(new WLabel(Component.literal(text), MUTED), 14, 50);
        }

        private static WTextField field(WPlainPanel panel, String label, String value, int x, int y, int width, int limit) {
            var field = new WTextField(Component.literal(label));
            field.setMaxLength(limit);
            field.setText(value);
            panel.add(field, x, y, width, 20);
            return field;
        }

        private static void button(WPlainPanel panel, String label, int x, int y, int width, Runnable click) {
            panel.add(new WButton(Component.literal(label)).setOnClick(click), x, y, width, 20);
        }

        void accept(Form form) {
            if (model.identity().equals(form.identity()) && model.pos().equals(form.pos())) {
                status.setText(Component.literal(crop(form.status())));
                if (!form.title().isEmpty()) {
                    readings.setText(Component.literal(form.readings()));
                    showProgram(form.program());
                }
            }
        }

        private static String crop(String line) {
            return Minecraft.getInstance().font.plainSubstrByWidth(line, WIDTH - 28);
        }

        private void send(int action, String value, String option) {
            if (!RequestGate.send(new Edit(model.pos(), model.identity(), action, value, option))) {
                status.setText(Component.literal("Too many queued requests. Try again."));
            }
        }
    }
}
