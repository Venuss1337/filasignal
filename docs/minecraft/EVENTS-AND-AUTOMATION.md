# Events and automation

Hardware tells programs when something happens: an entity walks past a detector, the rain starts, a button is pressed. A program reacts with an `on` handler. You never subscribe to topics or send messages yourself for ordinary hardware events: the device produces them and the server delivers them.

## Your first alarm

1. Place a computer, a Network Repeater, an Entity Detector and an Alarm Module. Link the computer to the repeater, and the repeater to each device, with the Linking Tool.
2. Open each device with an empty hand and name the detector `gate` and the alarm `siren`.
3. Open the computer, select **Open Editor** and press **Starters** until you see *Intruder alarm*, or type:

```fsig
const gate = detectors.named("gate")
const siren = alarms.named("siren")

on gate.entityEnter(entity) {
    print(entity.name + " is at the gate!")
    siren.activate()
}
```

4. Select **Save & Apply**. Walk up to the detector.

The detector needs no drive and no program of its own. `detectors.named("gate")` refers to the device by name. It does not need to be loaded yet when the program starts.

## How handlers work

```fsig
on gate.entityEnter(entity) { … }       // another device's event
on device.entityEnter(entity) { … }     // a device program's own hardware
on timer.every(20) { … }                // built-in: every 20 ticks
```

- **Values.** The names in parentheses receive the event's values in order. A handler may name fewer, as in `on panel.buttonOne { … }`. The `event` binding also remains available, with `event.values`.
- **Completion.** Type `on gate.` and press Ctrl+Space: the editor lists that device's events and inserts a complete handler. On a device program, type `on device.`.
- **Device constants.** `const NAME = <kind>.named("name")` declares a device constant: `detectors`, `weather`, `environment`, `redstoneControllers`, `inventoryModules`, `displays`, `alarms`, `repeaters`, `storageServers`, `accessControllers`, `controlPanels` and `bridges`. A computer can also call methods through it, such as `siren.activate()`.
- **Errors.** A call whose result you ignore never fails silently: a failure is printed as a warning in the program console. A call used as a value, as in `count = gate.count(Entity.HOSTILE)`, stops the handler with the device's explanation if it fails. To handle a failure yourself, keep both results: `count, err = gate.count(Entity.HOSTILE)`.
- **Text.** `str(value)` turns any value into text, for example `"Count: " + str(count)`.

## Constants

Fixed choices have named constants, offered by completion. Text such as `"north"` still works where it did before.

| Constant | Members | Used by |
| --- | --- | --- |
| `Direction` | `NORTH`, `SOUTH`, `EAST`, `WEST`, `UP`, `DOWN` | Redstone faces, inventory faces, events |
| `Entity` | `ALL`, `PLAYER`, `HOSTILE`, `PASSIVE`, `ITEM`, `OTHER` | Detector filters, `entity.category` |
| `Weather` | `CLEAR`, `RAIN`, `THUNDER` | `station.weather()`, `weatherChanged` |
| `Sound` | `BELL`, `PLING`, `CHIME`, `BASS` | `alarm.configure` |
| `PortMode` | `INPUT`, `OUTPUT` | `controller.setMode` |
| `Button` | `ONE`, `TWO`, `THREE`, `FOUR` | Control Panel buttons |
| `Access` | `GRANTED`, `DENIED` | `player.result` in access events |

## Events by device

| Device | Event (values) | When |
| --- | --- | --- |
| Entity Detector | `entityEnter(entity)`, `entityLeave(entity)` | An entity entered or left the radius. |
| | `countChanged(count, previous)` | The number of matching entities changed. |
| Weather Station | `rainStart`, `rainStop`, `thunderStart`, `thunderStop` | Dimension weather changed. |
| | `dayStart`, `nightStart` | Sunrise and nightfall. |
| | `weatherChanged(weather, previous)` | `Weather` constants. |
| Redstone Controller | `inputChanged(face, power, previous)` | An input face's power changed. `face` is a `Direction`. |
| | `signalOn(face, power)`, `signalOff(face)` | An input turned on or off. |
| Inventory Module | `contentsChanged(face)` | An approved inventory's items changed. |
| | `itemChanged(face, item, count, previous)` | One item type's total changed, such as `minecraft:iron_ingot`. |
| Environment Sensor | `lightChanged(light, previous)`, `skyLightChanged(light, previous)` | Light above the sensor changed. |
| | `darkened(light)`, `brightened(light)` | Light crossed the sensor's threshold (default 8, set in its form). |
| Alarm Module | `activated`, `deactivated`, `finished` | The alarm started, was silenced or ran out. |
| Access Controller | `accessAttempted(player)`, `accessGranted(player)`, `accessDenied(player)` | A player used it. `player` has `name`, `uuid`, `result` and `granted`. |
| Control Panel | `buttonPressed(button)` | `button` has `id` (a `Button`), `number`, `label` and `player`. |
| | `buttonOne(player)` … `buttonFour(player)` | One particular button, with the presser's name. |
| Any device | `connected`, `disconnected` | The device became reachable or unreachable for this program. |
| Programmable devices | `programStarted`, `programStopped`, `programFailed(message)` | The device's own program changed state. |

An entity is `{name, type, category, hostile, player, x, y, z, distance}`, plus `uuid` for players. The detector's form chooses which entities its events report: all, or one category.

### What "observed" means

- **Sampled devices.** Detector, weather, environment and inventory events compare one observation with the next. Detectors are sampled every 10 ticks and the others every 20, only while some program listens. Something that appears and leaves between two samples is not reported.
- **Busy detectors.** A detector examines at most 256 entities. When a scan is cut short, it reports arrivals but never departures or counts, because entities it did not examine may still be there.
- **Redstone.** Redstone, alarm, access and button events come straight from the change and are not sampled.
- **Start-up.** When a program starts, the first observation sets the baseline: entities already in range do not "enter".

## Where events reach

- **Computers.** A computer program reacts to devices on its own network while they are loaded and connected to it.
- **Device programs.** A device's own program reacts to devices on the same network that are linked to it through loaded devices, even while the computer's area is unloaded. Its device constants expose only the device's name, `connected` and its events, never its controls. A Storage Server reference also offers its storage methods.
- **Reconnection.** Handlers relink by themselves: names are looked up again every second and whenever the network changes. `disconnected` runs when a device becomes unreachable and `connected` when it comes back. An unknown or unreachable name is shown in the program's status instead of stopping the program.
- **Stopping.** Stopping, replacing or unloading a program removes its handlers. Devices nobody listens to are not observed at all.

## Advanced: custom events

Programs can define their own events to coordinate several devices:

```fsig
// Sender: every program on this network that handles "gate.open" receives it.
events.emit("gate.open", {by: "panel", seconds: 5})

// Or one named device's program only.
events.send("gate_door", "gate.open", {by: "panel", seconds: 5})

// Receiver
on events.custom("gate.open", request, sender) {
    print(sender.name + " asked to open for " + str(request.seconds) + " s")
}
```

- **Sender details.** `sender` (`{name, kind, id}`) is filled in by the server and cannot be faked.
- **Separate from hardware events.** A custom event never triggers a hardware handler such as `on device.activated`, and receiving one never operates hardware by itself.
- **Return value.** Both calls return how many programs received the event.

Delivery is best effort and happens on a later tick, never while the sender is still running. There are no durable queues or delivery guarantees.

### Limits

| Limit | Value |
| --- | --- |
| Event name | Starts with a letter; 1–64 letters, digits or `_ . : -` |
| Value | Text, numbers, true/false, nil, lists, tuples and dictionaries, nested at most 8 levels, with at most 512 values and 16,384 characters, and at most 4,096 bytes when stored. Device references cannot be sent. |
| Sends | 16 per program per tick |
| Receivers | 64 per broadcast |
| Queue | Each program queues at most 32 events. Extra events are dropped and counted in its statistics. |

A paused or busy program cannot accumulate unlimited events. Each program runs one handler per tick, so a loop of programs messaging each other advances one step per tick and cannot stall the server.

## Examples

Starter programs (also under **Starters** in the editor): [intruder alarm](../../examples/starter-intruder-alarm.fsig), [night lights](../../examples/starter-night-lights.fsig), [alarm buttons](../../examples/starter-panel-alarm.fsig), [weather board](../../examples/starter-weather-board.fsig), [low stock warning](../../examples/starter-low-stock.fsig), [door guest book](../../examples/starter-door-log.fsig), [visitor counter](../../examples/starter-visitor-counter.fsig) (Entity Detector drive) and [guard alarm](../../examples/starter-guard-alarm.fsig) (Alarm Module drive). A GameTest resolves every `starter-*.fsig` against the target its header names.

Advanced:
- An autonomous security system that runs without a computer: [alarm](../../examples/autonomous-alarm.fsig), [door](../../examples/autonomous-door.fsig), [display](../../examples/autonomous-board.fsig) and the computer's [report](../../examples/security-report.fsig) afterwards.
- A [command panel](../../examples/panel-commands.fsig) with a state machine.
- A [remote outpost](../../examples/remote-outpost.fsig) behind a wireless bridge.

See [ADR 0016](../decisions/0016-native-events-and-device-references.md) for the design.
