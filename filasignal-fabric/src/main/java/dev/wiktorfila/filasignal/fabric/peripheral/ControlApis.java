package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry.Type;
import dev.wiktorfila.filasignal.core.runtime.value.Value;
import dev.wiktorfila.filasignal.core.runtime.value.Value.*;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Spec;
import dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.Target;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.method;
import static dev.wiktorfila.filasignal.fabric.peripheral.HardwareHosts.slot;

/** Redstone controller, display module and alarm module objects. These change the world or device state. */
final class ControlApis {
    static final String DISPLAY_READ = "display.read";
    static final String DISPLAY_WRITE = "display.write";
    static final String ALARM = "alarm.write";
    private static final String READ = "redstone.read";
    private static final String WRITE = "redstone.write";

    private ControlApis() {}

    static List<Spec> specs(HardwareHosts hosts) {
        return List.of(controller(), display(), alarm(hosts));
    }

    private static Spec controller() {
        var face = HardwareHosts.choice("face");
        return new Spec(Kind.REDSTONE_CONTROLLER, "RedstoneController", "redstone.controller", READ,
                "Resolve a redstone controller by name.", "gate, err = redstone.controller(\"gate\")", List.of(
                method("input", Type.NUMBER, "Read the redstone strength (0–15) arriving at a face.", READ, 12,
                        "power, err = gate.input(\"north\")", Set.of("argument.invalid", "world.unloaded"),
                        (target, args, ctx) -> new Num(input(target, HardwareHosts.face(args.getFirst()))), face),
                method("output", Type.NUMBER, "Read the strength this controller emits from a face.", READ, 8,
                        "power, err = gate.output(\"south\")", Set.of("argument.invalid"),
                        (target, args, ctx) -> new Num(target.device().output(HardwareHosts.face(args.getFirst()))), face),
                method("set", Type.BOOL, "Emit an integer strength from 0 through 15 on an output face.", WRITE, 30,
                        "ok, err = gate.set(\"south\", 15)", Set.of("argument.invalid", "argument.range", "port.mode", "world.unloaded"),
                        (target, args, ctx) -> {
                            Direction side = HardwareHosts.face(args.getFirst());
                            int strength = HardwareHosts.integer(args.get(1), 0, 15, "Redstone strength must be an integer from 0 through 15");
                            if (!target.device().outputFaces().contains(side)) {
                                throw new HostFailure("port.mode", "Configure the " + side.getSerializedName() + " face as an output first");
                            }
                            requireLoaded(target, side);
                            target.device().output(target.level(), side, strength);
                            return new Bool(true);
                        }, face, slot("strength", Type.NUMBER)),
                method("mode", Type.STRING, "Read whether a face is an \"input\" or an \"output\".", READ, 4,
                        "mode, err = gate.mode(\"south\")", Set.of("argument.invalid"), (target, args, ctx) ->
                                new Text(target.device().outputFaces().contains(HardwareHosts.face(args.getFirst())) ? "output" : "input"), face),
                method("setMode", Type.BOOL, "Make a face an \"input\" or an \"output\". Leaving output mode turns the face off.",
                        WRITE, 30, "ok, err = gate.setMode(\"south\", \"output\")", Set.of("argument.invalid"), (target, args, ctx) -> {
                            Direction side = HardwareHosts.face(args.getFirst());
                            String mode = HardwareHosts.text(args.get(1));
                            if (!mode.equals("input") && !mode.equals("output")) {
                                throw new HostFailure("argument.invalid", "Mode must be \"input\" or \"output\"");
                            }
                            target.device().faceMode(target.level(), side, mode.equals("output"));
                            return new Bool(true);
                        }, face, HardwareHosts.choice("mode")),
                method("inputs", Type.DICT, "Read every input face's strength, keyed by face.", READ, 24,
                        "levels, err = gate.inputs()", Set.of("world.unloaded"), (target, args, ctx) -> {
                            var values = new LinkedHashMap<String, Value>();
                            for (Direction side : Direction.values()) {
                                if (!target.device().outputFaces().contains(side)) {
                                    values.put(side.getSerializedName(), new Num(input(target, side)));
                                }
                            }
                            return new DictValue(values);
                        }),
                method("outputs", Type.DICT, "Read every output face's strength, keyed by face.", READ, 12,
                        "levels, err = gate.outputs()", (target, args, ctx) -> {
                            var values = new LinkedHashMap<String, Value>();
                            for (Direction side : target.device().outputFaces()) {
                                values.put(side.getSerializedName(), new Num(target.device().output(side)));
                            }
                            return new DictValue(values);
                        })));
    }

    private static int input(Target target, Direction face) {
        requireLoaded(target, face);
        return target.level().getSignal(target.device().getBlockPos().relative(face), face);
    }

    private static void requireLoaded(Target target, Direction face) {
        if (!LoadedChunks.signalArea(target.level(), target.device().getBlockPos().relative(face))) {
            throw new HostFailure("world.unloaded", "The chunks around that face are unloaded");
        }
    }

    private static Spec display() {
        return new Spec(Kind.DISPLAY, "Display", "displays.from", DISPLAY_READ,
                "Resolve a display module by name.", "board, err = displays.from(\"lobby\")", List.of(
                method("text", Type.STRING, "Read the displayed text with lines separated by newlines.", DISPLAY_READ, 8,
                        "shown, err = board.text()", (target, args, ctx) -> new Text(String.join("\n", target.device().display()))),
                method("lines", Type.LIST, "Read the displayed lines.", DISPLAY_READ, 8, "rows, err = board.lines()",
                        (target, args, ctx) -> new ListValue(target.device().display().stream().<Value>map(Text::new).toList())),
                method("setText", Type.BOOL, "Replace the text: up to 8 lines of 48 characters separated by newlines.",
                        DISPLAY_WRITE, 16, "ok, err = board.setText(\"Iron: 64\")", Set.of("argument.range"), (target, args, ctx) -> {
                            String text = ((Text) args.getFirst()).value();
                            ctx.charge(text.length());
                            target.device().display(DisplayText.lines(text));
                            return new Bool(true);
                        }, slot("text", Type.STRING)),
                method("write", Type.BOOL, "Show text on the display, replacing what was there. Use \n for a new line.",
                        DISPLAY_WRITE, 16, "board.write(\"Hello!\")", Set.of("argument.range"), (target, args, ctx) -> {
                            String text = ((Text) args.getFirst()).value();
                            ctx.charge(text.length());
                            target.device().display(DisplayText.lines(text));
                            return new Bool(true);
                        }, slot("text", Type.STRING)),
                method("setLine", Type.BOOL, "Replace one line (0–7), adding empty lines before it when needed.",
                        DISPLAY_WRITE, 16, "ok, err = board.setLine(0, \"Status: OK\")", Set.of("argument.range"), (target, args, ctx) -> {
                            int index = HardwareHosts.integer(args.getFirst(), 0, DisplayText.MAX_LINES - 1,
                                    "Line index must be an integer from 0 through " + (DisplayText.MAX_LINES - 1));
                            String line = ((Text) args.get(1)).value();
                            ctx.charge(line.length());
                            var lines = new ArrayList<>(target.device().display());
                            while (lines.size() <= index) {
                                lines.add("");
                            }
                            lines.set(index, DisplayText.line(line));
                            target.device().display(lines);
                            return new Bool(true);
                        }, slot("index", Type.NUMBER), slot("text", Type.STRING)),
                method("clear", Type.BOOL, "Remove all displayed text.", DISPLAY_WRITE, 8, "ok, err = board.clear()",
                        (target, args, ctx) -> {
                            target.device().display(List.of());
                            return new Bool(true);
                        })));
    }

    private static Spec alarm(HardwareHosts hosts) {
        return new Spec(Kind.ALARM, "Alarm", "alarms.from", ALARM,
                "Resolve an alarm module by name.", "siren, err = alarms.from(\"siren\")", List.of(
                method("activate", Type.BOOL, "Sound the alarm for its configured duration, renewing it when already active.",
                        ALARM, 32, "ok, err = siren.activate()", Set.of("alarm.limit"), (target, args, ctx) -> {
                            hosts.runtime().alarms().activate(target.device(), target.level(), hosts.runtime().tickCount());
                            return new Bool(true);
                        }),
                method("deactivate", Type.BOOL, "Silence the alarm.", ALARM, 16, "ok, err = siren.deactivate()",
                        (target, args, ctx) -> {
                            hosts.runtime().alarms().deactivate(target.device(), target.level());
                            return new Bool(true);
                        }),
                method("active", Type.BOOL, "True while the alarm is sounding.", ALARM, 4, "on, err = siren.active()",
                        (target, args, ctx) -> new Bool(hosts.runtime().alarms().active(target.device()))),
                method("configure", Type.BOOL, "Set sound (bell, pling, chime or bass), volume (10–100) and duration "
                                + "in seconds (1–60). Applies from the next activation or sound.", ALARM, 16,
                        "ok, err = siren.configure(\"bell\", 80, 20)", Set.of("argument.range"), (target, args, ctx) -> {
                            target.device().alarm(new AlarmSettings(HardwareHosts.text(args.getFirst()),
                                    HardwareHosts.integer(args.get(1), 10, 100, "Volume must be an integer from 10 through 100"),
                                    HardwareHosts.integer(args.get(2), 1, 60, "Duration must be an integer from 1 through 60")));
                            return new Bool(true);
                        }, HardwareHosts.choice("sound"), slot("volume", Type.NUMBER), slot("seconds", Type.NUMBER)),
                method("settings", Type.DICT, "Read sound, volume, seconds, active and remaining seconds.", ALARM, 8,
                        "info, err = siren.settings()", (target, args, ctx) -> {
                            var settings = target.device().alarm();
                            var values = new LinkedHashMap<String, Value>();
                            values.put("sound", new Text(settings.sound()));
                            values.put("volume", new Num(settings.volume()));
                            values.put("seconds", new Num(settings.seconds()));
                            values.put("active", new Bool(hosts.runtime().alarms().active(target.device())));
                            values.put("remaining", new Num(Math.ceil(hosts.runtime().alarms()
                                    .remaining(target.device(), hosts.runtime().tickCount()) / 20.0)));
                            return new DictValue(values);
                        })));
    }
}
