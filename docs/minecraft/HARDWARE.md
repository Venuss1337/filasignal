# Hardware reference

Dedicated hardware blocks join a Programmable Computer's network and expose their own FilaScript objects. They are in the **FilaSignal** creative tab and every block can be crafted in survival. The public [hardware pages](https://venuss1337.github.io/filasignal/minecraft/hardware/) describe each device for players, with recipes and pictures; this page is the engineering summary.

## Set up any device

1. Place the computer and a **Network Repeater** (or an existing Network Interface) within 32 blocks.
2. With the Linking Tool, click the computer and then the repeater. Next click the repeater and then the device. Every device needs a relay between it and the computer. A repeater or interface accepts up to 16 links, and other devices accept one.
3. Right-click the device with an empty hand to open its form. Give it a name, which is the alias scripts use. Configure its settings and check **Refresh** for live readings. The owner can also trust or untrust players here. For a linked device this changes the whole network's access list.
4. In a program, name the device with a device constant, then call its methods or handle its events. See [events and automation](EVENTS-AND-AUTOMATION.md).

```fsig
const gate = detectors.named("gate")

on gate.entityEnter(entity) {
    print("Hostiles nearby: " + str(gate.count(Entity.HOSTILE)))
}
```

Every method returns `value, err`. A call used as a single value, as above, stops the handler with the device's explanation if it fails. Write `count, err = gate.count(Entity.HOSTILE)` to handle the error yourself. `detectors.from(name)` and the other `from` factories still resolve a device immediately and return `handle, err`.

Devices only answer the computer whose network they belong to. Other computers get `device.unknown` even if they know the name or ID. A device that is unloaded or cut off from the computer returns `device.unavailable`. Using the wrong factory returns `device.type`.

## Common API

| Call | Result |
| --- | --- |
| `devices.list()` | List of `{name, id, kind, capabilities, connected}` for every network member, including offline ones. `name` is `nil` for unnamed devices. |
| `devices.from(name)` | A generic `Device` for any kind. |
| `x.id`, `x.name`, `x.kind`, `x.connected` | Properties of every device object. `kind` is `repeater`, `entity_detector`, `weather_station`, `redstone_controller`, `inventory_module`, `environment_sensor`, `display`, `alarm`, `storage_server`, `access_controller`, `control_panel`, `wireless_bridge`, `interface` or `sensor`. |
| `<kind>.named(name)` | A device constant for `on NAME.event(...)`: `detectors`, `weather`, `environment`, `redstoneControllers`, `inventoryModules`, `displays`, `alarms`, `repeaters`, `storageServers`, `accessControllers`, `controlPanels` or `bridges`. |
| `device.capabilities` | List of `network`, `relay`, `hardware` and `execution`. |

## Devices

### Network Repeater

Relays links like the Network Interface, without redstone or inventory faces. Factory `repeaters.from(name)`.

- `links()` returns linked devices, including the computer, as `{name, id, kind, capabilities, connected}`.
- `status()` returns `{links, connectedLinks, network}`.

### Entity Detector

Detects loaded entities within a configurable radius of 1–16 blocks (default 8) from the block's center. Set the radius, and which entities its events report, in the form. Factory `detectors.from(name)`. Events: `entityEnter`, `entityLeave` and `countChanged`.

- `scan(filter)` returns up to 32 matches, nearest first. Each is `{type, category, x, y, z, distance}` plus `name` for players and entities with a visible custom name.
- `count(filter)` returns the number of matches.
- `nearest(filter)` returns one match, or `nil` when nothing matches.
- `radius()` returns the configured radius.

A filter is an `Entity` constant (`Entity.ALL`, `Entity.PLAYER`, `Entity.HOSTILE`, `Entity.PASSIVE`, `Entity.ITEM`, `Entity.OTHER`), the same word as text, or an entity type ID such as `minecraft:zombie`. Spectators and dead entities are ignored. Coordinates are block positions and distance is in blocks. No UUIDs, health, inventories or entity NBT are exposed. A detector examines at most 256 entities, and results within one tick come from the same snapshot. If a server runs more than 16 fresh scans in a tick, extra scans return `detector.busy`. Try again on the next tick.

### Weather Station

Reads world weather and time. Factory `weather.from(name)`. Events: `rainStart`, `rainStop`, `thunderStart`, `thunderStop`, `dayStart`, `nightStart` and `weatherChanged`.

- `weather()` returns `Weather.CLEAR`, `Weather.RAIN` or `Weather.THUNDER` for the dimension. `read().wet` tells whether rain falls on the station itself.

- `read()` returns:
  - `dimension`
  - `hasWeather`, which is false in dimensions with a ceiling or no sky, such as the Nether and the End
  - global weather: `raining`, `thundering`, `clear`, `rainLevel` and `thunderLevel` (0–1)
  - local conditions at the station: `precipitation` (`none`, `rain` or `snow` for the biome) and `wet` (precipitation is falling on the station right now)
  - time: `dayTime` (0–23,999, where 0 is sunrise and 6,000 is noon), `day`, `gameTime`, `isDay`, `isNight` and `fixedTime`
- `raining()`, `thundering()` and `clear()` return the global weather. They are false, false and true where weather does not apply.
- `time()` returns the time fields only.

There are no forecasts. The values are Minecraft's current state.

### Redstone Controller

Six independent faces, named by world direction: `Direction.NORTH` or `"north"`, and likewise south, east, west, up and down. Each face is an input or an output. All faces start as inputs. Change modes in the form or with `setMode(face, PortMode.OUTPUT)`. Factory `redstone.controller(name)`. Events: `inputChanged`, `signalOn` and `signalOff`.

- `input(face)` returns the signal arriving at the face (0–15).
- `output(face)` returns what the face emits.
- `set(face, strength)` emits 0–15 from an output face. An input face returns `port.mode`.
- `mode(face)` returns `input` or `output`. `setMode(face, mode)` changes it, and leaving output turns the face off.
- `inputs()` and `outputs()` return dictionaries keyed by face.

If the controller has a name, input faces raise the existing events with a `name:face` argument, such as `on redstone.rising("gate:north")`. Output faces are never sampled as inputs. Outputs turn off when the controller unloads or loses its network connection. Unchanged values cause no block updates.

### Inventory Module

The owner approves adjacent inventories in the form. Enter a face and a name, then select **Inventory**. Leave the name empty to remove a face. Approved faces are also available through `inventory.from(name)`. Factory `inventory.module(name)`. Events: `contentsChanged` and `itemChanged`, for approved faces only.

- `inventories()` returns approved faces as `{name, face, available}`.
- `inventory(face)` returns the approved inventory on a face as an `Inventory` object.
- `adjacent()` returns all six neighbors as `{face, block, container, approved}`. This finds inventories to approve. It does not grant access.

`Inventory` objects support `size()`, `getSlot(index)`, `count(item)`, `contains(item)`, `occupied()`, `items()` (totals by item ID), `fullness()`, `isEmpty()` and `moveTo(destination, item, amount)`. See [inventory automation](INVENTORY.md) for slot, stack and protection rules. Transfers move up to 256 items, respect sided slots and stack limits, report partial moves and never duplicate or delete items.

### Environment Sensor

Reads local conditions in the space directly above the block. Factory `environment.from(name)`. Events: `lightChanged`, `skyLightChanged`, `darkened` and `brightened`. The form sets the light threshold for the last two (1–15, default 8).

- `read()` returns:
  - light: `blockLight`, `skyLight` and `light` (each 0–15). `skyLight` is the raw sky light, not adjusted for time of day.
  - `skyVisible`
  - biome: `biome` (ID), `temperature` and `precipitation`. `temperature` is Minecraft's unitless biome value, from about -0.7 to 2.0, not degrees.
  - position: `x`, `y`, `z`, `aboveSeaLevel` and `dimension`
- `light()`, `biome()` and `skyVisible()` return single values.

### Display Module

Holds up to 8 lines of up to 48 characters. The text is saved with the block. Factory `displays.from(name)`.

- `write(text)` and `setText(text)` replace the text, using newlines to separate lines.
- `setLine(index, text)` replaces line 0–7, adding empty lines before it as needed.
- `text()`, `lines()` and `clear()`.

Text that is too long returns `argument.range`. Control characters become spaces. The form shows the text and can set it, using `|` between lines. The text is drawn on the block's front face for nearby players; clients receive only the text, and only when it changes.

### Alarm Module

Plays a vanilla sound once a second while active and glows. Factory `alarms.from(name)`. Events: `activated`, `deactivated` and `finished`.

- `activate()` sounds the alarm for its configured duration and renews it if already active.
- `deactivate()` silences it.
- `active()` returns whether it is sounding.
- `configure(sound, volume, seconds)` sets the sound (`Sound.BELL`, `Sound.PLING`, `Sound.CHIME` or `Sound.BASS`, or the same word as text), volume (10–100) and duration (1–60 seconds).
- `settings()` returns `{sound, volume, seconds, active, remaining}`.

The form can save settings and test or stop the alarm. An alarm stops when its duration ends, when it unloads or when it is broken. At most 64 alarms can sound at once on a server.

### Storage Server

Shared storage for every program on its network: 256 KiB in up to 1,024 keys, with each value at most 64 KiB. It uses the same values and errors as [drive storage](#program-api-on-a-device). Factory `storageServers.from(name)`, or `storageServers.named(name)`.

- `read(key)`, `write(key, value)`, `append(key, value)`, `delete(key)`, `has(key)`, `keys()`, `usage()` and, from a computer only, `clear()`.
- **Device programs** on the same network may read. They may also write unless the owner sets the form to "Device programs: read".
- **Form.** **View data** and owner-only **Clear data**.
- **Data stays with the block.** Breaking the server deletes its data.

```fsig
const warehouse = storageServers.named("warehouse")
on startup {
    warehouse.write("diamonds", 64)
    print(warehouse.read("diamonds"))
}
```

### Access Controller

Opens a door for the right players with no program at all:

1. Place the controller and put a door or lamp next to it.
2. Sneak and use it with an empty hand to open its form.
3. Add players by name with **Allow**, or switch it to **public**.
4. Set how many seconds it stays open (1–30, default 3) and the side it powers (`all` or a face).
5. Other players use it with an empty hand.

Owners, trusted players and listed players are let in. Everyone else hears a low note and sees "Access denied". The form shows the last 10 attempts. It starts restricted.

Factory `accessControllers.from(name)`. Methods:
- `open()`, `openFor(seconds)`, `close()` and `isOpen()`;
- `allowed()`, `isPublic()` and `recent()`.

Programs can react to attempts and open the door, but cannot change the list or the policy. Events: `accessAttempted`, `accessGranted` and `accessDenied`. Each carries `player`: `{name, uuid, result, granted}`.

### Control Panel

Four labeled buttons. Use the panel to open its buttons, and press one. The owner and trusted players can rename buttons (1–24 characters) and make the panel public, so anyone can press. Visitors see only the buttons. Factory `controlPanels.from(name)`. Methods: `labels()`, `label(button)` and `setLabel(button, text)`, where `button` is `Button.ONE`–`Button.FOUR` or 1–4. Events: `buttonPressed(button)` and `buttonOne(player)`–`buttonFour(player)`.

### Wireless Network Bridge

Joins two parts of one network up to 256 blocks apart in the same dimension.

1. Place a bridge near the computer and link it like any relay. Place another bridge at the far site.
2. Open the first bridge's form. It lists your own unpaired, loaded bridges in range, nearest first. Choose one and select **Pair**.
3. Link the far devices to the far bridge.

The form shows the partner, the distance and whether it is connected. **Unpair** separates them, even when the partner is unloaded. Each bridge has one partner, and both must be yours. Pairing never joins two computers' networks, and bridges never load chunks. Factory `bridges.from(name)`: `status()` returns `{paired, peer, connected, distance}`.

## Programmable data drives and autonomous devices

The Entity Detector, Weather Station, Redstone Controller, Inventory Module, Environment Sensor, Display Module and Alarm Module can run their own program from a **Programmable Data Drive**. Without a drive they work exactly as described above. The Network Repeater, Network Interface and Sensor cannot run programs.

### Write a program onto a drive

1. Use a drive on a computer to put it in the computer's slot. The dashboard shows the drive.
2. Select **Type** until it names the hardware the program is for, and optionally save a label.
3. Select **Edit drive**. The editor shows `Editing: Drive … in computer` and offers only that hardware's API. Write the program and use **Save & Apply**. A drive in a computer is never run there.
4. Select **Eject** to take the drive back.

### Install and run it

1. Use the drive on a compatible device. Installing never starts the program. A blank drive is assigned to that device's kind. A drive written for another kind stays removable but cannot run.
2. Open the device form. Its program section shows the drive, state, stored bytes and last error.
   - **Start**, **Stop**, **Edit program** (the same editor, including debugging), **Storage** (stored keys and recent console lines) and **Eject**.
   - **Clear data** (owner only).
3. A started program keeps running without a computer while its chunk is loaded. After a chunk reload or server restart it starts again automatically, with its saved globals, as long as the same drive is still installed. Removing the drive stops it, saves its globals and stored data to the drive, and ends any debugging.

You can also edit a device's drive from its computer. On the dashboard, type the device's name and select **Edit remote**. The device must be a reachable member of that computer's network.

### Program API on a device

A device program sees only its own hardware, its drive's storage and the language built-ins (`print`, `json`, `log.error`):

| Call | Result |
| --- | --- |
| `device.<method>(…)` | That device's hardware methods from the sections above, such as `device.census("hostile")`, `device.read()` on a weather station or `device.set("south", 15)` on a redstone controller. |
| `device.info()` | `{id, name, kind, drive, driveLabel}`. |
| `device.time()` | `{dayTime, day, gameTime, isDay, isNight, fixedTime}`. A Weather Station's own `time()` provides it. |
| `storage.read(key)` | A copy of the stored value. A missing key returns `storage.missing`. |
| `storage.write(key, value)` / `storage.append(key, value)` | Store a copy, or append to a stored list, creating it when absent. |
| `storage.has(key)`, `storage.delete(key)`, `storage.keys()`, `storage.clear()` | Usual key operations. |
| `storage.usage()` | `{used, capacity, entries, maxEntries}`. |

A Redstone Controller's program receives its own input faces as `on redstone.rising("north")`, `redstone.falling` and `redstone.changed`. An Inventory Module's program opens only faces the owner approved in its form, with `device.inventory(face)`. Programs cannot discover or use other devices.

Storable values are nil, Booleans, numbers, strings, lists, tuples and dictionaries, nested at most 16 levels, with no cycles. Device handles, functions, enums and class instances cannot be stored. Limits per drive:
- 64 KiB of stored keys and values in up to 256 keys;
- keys of 1–64 letters, digits or `_ . : -`;
- 16,384 characters of program source;
- 64 KiB of saved globals.

A failed write changes nothing and returns `storage.full`, `storage.value`, `storage.type` or `storage.key`.

The entity detector's `census(filter)` returns `{count, truncated, nearest}`. `truncated` is true when more than 256 entities were in range, so `count` is only a lower bound.

### Read drives from a computer

`drives.from(name)` returns the drive installed in a named programmable device on the computer's network. It offers these methods:

| Method | Result |
| --- | --- |
| `info()` | `{id, label, target, device, kind, revision, state, running, used, entries}` |
| `program()` | The program source. |
| `keys()`, `has(key)`, `read(key)` | Stored data, one value at a time. A missing key returns `storage.missing`. |
| `usage()` | `{used, capacity, entries, maxEntries}` |
| `status()` | `{state, running, error, statistics, startedBy}` |
| `start()`, `stop()`, `restart()` | Requests applied before the next scheduler tick. |

A handle stops working with `drive.removed` once its drive is removed, even if another drive is inserted. Remote writes and deletes are not available.

### Ownership and authority

- **Data:** whoever holds a drive item can read its data in any computer or device they have access to. Drives are not encrypted.
- **Device rights:** device ownership and trust never move with the drive.
- **Who can act:** installing, starting, stopping, editing and debugging need the same access as editing that device's computer program. Applying with fresh globals and clearing data need the owner.
- **What a running program can do:** use its own device's hardware under that device's current configuration, and nothing else.
- **Duplicates:** a drive can be installed in only one block at a time. A duplicated drive item shares the original's data and cannot run at the same time as it.

Operators can count drive records with `/filasignal drives`. `/filasignal drives prune DAYS` only reports how many records are not installed anywhere and unused for that many days. Add `confirm` to delete them. Deleting also erases drives that are sitting in chests or offline players' inventories, so check first. `/filasignal programs` lists the busiest running programs. See [ADR 0015](../decisions/0015-autonomous-devices-and-drives.md).

## Capabilities and costs

Calls require the controller capabilities:
- `devices.read`, `detector.read`, `weather.read` and `environment.read`;
- `redstone.read`, `redstone.write`, `inventory.read` and `inventory.transfer`;
- `display.read`, `display.write`, `alarm.write`, `access.control` and `panel.control`;
- `drives.read`, `drives.control`, `server.read`, `server.write` and `events.emit`.

The Programmable Computer holds all of them. A device program holds only its kind's hardware capabilities, its drive storage, shared storage and `events.emit`.

Each call charges its base cost plus a lookup allowance of 256 operations against the handler's 10,000-operation budget. Scans add 4 per examined entity, display writes add their length, and list results add per item.

## Examples

Starter and event examples are listed in [events and automation](EVENTS-AND-AUTOMATION.md#examples).

Computer programs: [hostile-detector.fsig](../../examples/hostile-detector.fsig), [security-alarm.fsig](../../examples/security-alarm.fsig), [weather-lights.fsig](../../examples/weather-lights.fsig), [stock-check.fsig](../../examples/stock-check.fsig), [status-board.fsig](../../examples/status-board.fsig), [device-discovery.fsig](../../examples/device-discovery.fsig) and [central-monitor.fsig](../../examples/central-monitor.fsig).

Drive programs:
- [autonomous-detector.fsig](../../examples/autonomous-detector.fsig), Entity Detector
- [weather-history.fsig](../../examples/weather-history.fsig), Weather Station
- [inventory-watch.fsig](../../examples/inventory-watch.fsig), Inventory Module
- [portable-counter.fsig](../../examples/portable-counter.fsig), Entity Detector
- [local-redstone.fsig](../../examples/local-redstone.fsig), Redstone Controller

A GameTest resolves every example against the registry it runs with. The portable counter, latch and detector examples also run on real devices.

## Limitations

- Display text is scaled to fit the screen, so eight long lines are small. Visual block states (computer screen, alarm light, Access Controller light) are only visual.
- Drive data stays in the world where it was written. Moving a drive item to another world leaves its program behind.
- An alarm activated by a device program stops when the chunk unloads, like any other alarm.
- Detector, weather, environment and inventory events are observed changes between samples, not every movement. See [events and automation](EVENTS-AND-AUTOMATION.md#what-observed-means).
- Storage server data is deleted with its block.
- Access lists accept online players by name only. Offline players can be removed but not added.
- Alarms do not resume after a restart or chunk reload.
