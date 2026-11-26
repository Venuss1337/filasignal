package dev.wiktorfila.filasignal.fabric.peripheral;

import dev.wiktorfila.filasignal.core.host.HostRegistry;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Event;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Slot;
import dev.wiktorfila.filasignal.core.host.HostRegistry.Type;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Capability;
import dev.wiktorfila.filasignal.fabric.device.DeviceNetwork.Kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single catalog of native hardware events and named constants. Registries, the editor's completion and the
 * documentation all read these definitions.
 *
 * <p>A device's own program handles its events as {@code on device.entityEnter(entity)}. Any program handles another
 * device's events through a device constant: {@code const gate = detectors.named("gate")} and then
 * {@code on gate.entityEnter(entity)}. The handler's parenthesized names receive the event's values in order.
 */
final class HardwareEvents {
    /** A native event: its unqualified name, value names and a beginner-facing description. */
    record Definition(String name, List<String> values, String documentation) {
        Event event(String qualified) {
            return new Event(qualified, List.of(), values, documentation);
        }
    }

    static final Map<String, List<String>> ENUMS = enums();

    private static final Map<Kind, List<Definition>> HARDWARE = hardware();
    private static final List<Definition> CONNECTION = List.of(
            new Definition("connected", List.of(), "Runs when the device becomes reachable, for example after its area loads."),
            new Definition("disconnected", List.of(), "Runs when the device stops being reachable: unloaded, unlinked or removed."));
    private static final List<Definition> PROGRAM = List.of(
            new Definition("programStarted", List.of(), "Runs when the device's own program starts."),
            new Definition("programStopped", List.of(), "Runs when the device's own program stops."),
            new Definition("programFailed", List.of("message"), "Runs when the device's own program stops on an error."));

    private HardwareEvents() {}

    private static Map<String, List<String>> enums() {
        var enums = new LinkedHashMap<String, List<String>>();
        enums.put("Direction", List.of("NORTH", "SOUTH", "EAST", "WEST", "UP", "DOWN"));
        enums.put("Entity", List.of("ALL", "PLAYER", "HOSTILE", "PASSIVE", "ITEM", "OTHER"));
        enums.put("Weather", List.of("CLEAR", "RAIN", "THUNDER"));
        enums.put("Sound", List.of("BELL", "PLING", "CHIME", "BASS"));
        enums.put("PortMode", List.of("INPUT", "OUTPUT"));
        enums.put("Button", List.of("ONE", "TWO", "THREE", "FOUR"));
        enums.put("Access", List.of("GRANTED", "DENIED"));
        return java.util.Collections.unmodifiableMap(enums);
    }

    private static Map<Kind, List<Definition>> hardware() {
        var events = new java.util.EnumMap<Kind, List<Definition>>(Kind.class);
        events.put(Kind.ENTITY_DETECTOR, List.of(
                new Definition("entityEnter", List.of("entity"), "Runs when an entity enters the detector's range. "
                        + "entity has name, type, category, hostile, player, x, y, z and distance."),
                new Definition("entityLeave", List.of("entity"), "Runs when an entity leaves the detector's range."),
                new Definition("countChanged", List.of("count", "previous"),
                        "Runs when the number of entities in range changes.")));
        events.put(Kind.WEATHER_STATION, List.of(
                new Definition("rainStart", List.of(), "Runs when it starts raining or snowing."),
                new Definition("rainStop", List.of(), "Runs when rain or snow stops."),
                new Definition("thunderStart", List.of(), "Runs when a thunderstorm starts."),
                new Definition("thunderStop", List.of(), "Runs when a thunderstorm ends."),
                new Definition("dayStart", List.of(), "Runs at sunrise."),
                new Definition("nightStart", List.of(), "Runs at nightfall."),
                new Definition("weatherChanged", List.of("weather", "previous"),
                        "Runs when the weather changes between Weather.CLEAR, Weather.RAIN and Weather.THUNDER.")));
        events.put(Kind.REDSTONE_CONTROLLER, List.of(
                new Definition("inputChanged", List.of("face", "power", "previous"),
                        "Runs when the redstone power arriving at an input face changes. face is a Direction."),
                new Definition("signalOn", List.of("face", "power"), "Runs when an input face turns on."),
                new Definition("signalOff", List.of("face"), "Runs when an input face turns off.")));
        events.put(Kind.INVENTORY_MODULE, List.of(
                new Definition("contentsChanged", List.of("face"), "Runs when the items in an approved inventory change."),
                new Definition("itemChanged", List.of("face", "item", "count", "previous"),
                        "Runs when the total count of one item type changes, such as minecraft:iron_ingot.")));
        events.put(Kind.ENVIRONMENT_SENSOR, List.of(
                new Definition("lightChanged", List.of("light", "previous"), "Runs when the light level above the sensor changes."),
                new Definition("skyLightChanged", List.of("light", "previous"), "Runs when the sky light above the sensor changes."),
                new Definition("darkened", List.of("light"), "Runs when the light drops below the sensor's threshold."),
                new Definition("brightened", List.of("light"), "Runs when the light reaches the sensor's threshold.")));
        events.put(Kind.ALARM, List.of(
                new Definition("activated", List.of(), "Runs when the alarm starts sounding."),
                new Definition("deactivated", List.of(), "Runs when the alarm is silenced."),
                new Definition("finished", List.of(), "Runs when the alarm stops after its configured duration.")));
        events.put(Kind.ACCESS_CONTROLLER, List.of(
                new Definition("accessAttempted", List.of("player"),
                        "Runs when a player uses the controller. player has name, uuid and result (Access.GRANTED or Access.DENIED)."),
                new Definition("accessGranted", List.of("player"), "Runs when a player is let in."),
                new Definition("accessDenied", List.of("player"), "Runs when a player is refused.")));
        events.put(Kind.CONTROL_PANEL, List.of(
                new Definition("buttonPressed", List.of("button"),
                        "Runs when a player presses a button. button has id (Button.ONE to Button.FOUR), number, label and player."),
                new Definition("buttonOne", List.of("player"), "Runs when button 1 is pressed. player is the name of who pressed it."),
                new Definition("buttonTwo", List.of("player"), "Runs when button 2 is pressed."),
                new Definition("buttonThree", List.of("player"), "Runs when button 3 is pressed."),
                new Definition("buttonFour", List.of("player"), "Runs when button 4 is pressed.")));
        return java.util.Collections.unmodifiableMap(events);
    }

    /** Events a device of this kind raises about its own hardware. */
    static List<Definition> hardware(Kind kind) {
        return HARDWARE.getOrDefault(kind, List.of());
    }

    /** Every event another program can handle through a device constant of this kind. */
    static List<Definition> remote(Kind kind) {
        var all = new ArrayList<>(hardware(kind));
        all.addAll(CONNECTION);
        if (kind.has(Capability.EXECUTION) && kind != Kind.COMPUTER) {
            all.addAll(PROGRAM);
        }
        return all;
    }

    /** Events for a device constant's host type, keyed by name. */
    static Map<String, Event> objectEvents(Kind kind) {
        var events = new LinkedHashMap<String, Event>();
        for (Definition definition : remote(kind)) {
            events.put(definition.name(), definition.event(definition.name()));
        }
        return events;
    }

    /** A device program's own events, qualified as {@code device.<name>}. */
    static List<Event> localEvents(Kind kind) {
        var events = new ArrayList<Event>();
        for (Definition definition : hardware(kind)) {
            events.add(definition.event("device." + definition.name()));
        }
        if (kind == Kind.REDSTONE_CONTROLLER) {
            for (String edge : List.of("changed", "rising", "falling")) {
                events.add(new Event("redstone." + edge, List.of(new Slot("face", Set.of(Type.STRING))), List.of("strength"),
                        "Earlier form of device.inputChanged for one face name, such as \"north\"."));
            }
        }
        events.add(custom());
        return events;
    }

    /** A computer program's network events: named sensors, interface ports and controller faces, and custom events. */
    static List<Event> computerEvents() {
        var alias = List.of(new Slot("name", Set.of(Type.STRING)));
        return List.of(
                new Event("sensor.detected", alias, List.of("detected"), "Runs when a named Sensor first sees a player."),
                new Event("sensor.cleared", alias, List.of("detected"), "Runs when a named Sensor sees no player any more."),
                new Event("redstone.changed", alias, List.of("strength"),
                        "Runs when a named input port, or a controller face such as \"gate:north\", changes."),
                new Event("redstone.rising", alias, List.of("strength"), "Runs when a named input turns on."),
                new Event("redstone.falling", alias, List.of("strength"), "Runs when a named input turns off."),
                custom());
    }

    /** Advanced: messages other programs send with events.emit or events.send. */
    static Event custom() {
        return new Event("events.custom", List.of(new Slot("name", Set.of(Type.STRING))), List.of("value", "sender"),
                "Runs when another program on the network sends this custom event. sender has name, kind and id.");
    }

    /** Builds a registry from functions, object types, the shared enums and these events. */
    static HostRegistry registry(List<HostRegistry.Function> functions, List<HostRegistry.ObjectType> types,
                                 List<Event> events) {
        return new HostRegistry(functions, types, ENUMS, events);
    }
}
