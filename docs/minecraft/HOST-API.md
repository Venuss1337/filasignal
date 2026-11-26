# FilaSignal Minecraft host API contract

The Programmable Computer provides `print`, `str`, lifecycle events, timers and the live device APIs below.
- **Hardware objects.** Dedicated hardware adds typed objects through `repeaters`, `detectors`, `weather`, `environment`, `redstone.controller`, `inventory.module`, `displays`, `alarms`, `storageServers`, `accessControllers`, `controlPanels` and `bridges`, plus `devices.list()`. Each kind also has a `named(name)` reference for device constants and events.
- **Drives.** `drives.from(name)` reads the drive in a programmable device. Programs running on a device from a drive use `device.*` and `storage.*` instead.
- **Events and constants.** Native events, named constants and custom events are described in [events and automation](EVENTS-AND-AUTOMATION.md).

See the [hardware reference](HARDWARE.md), [ADR 0014](../decisions/0014-unified-hardware.md), [ADR 0015](../decisions/0015-autonomous-devices-and-drives.md) and [ADR 0016](../decisions/0016-native-events-and-device-references.md). The remaining namespaces are planned.

Common tasks use a short qualified function name and simple values. A position is a dictionary with integer `x`, `y` and `z` in the current dimension. Return bounded ordinary FilaScript values. Device references and world observations may use stable ID strings and detached dictionaries. Keep all Minecraft objects inside the adapter.

## Results and authorization

Infallible controller-local metadata returns one value directly. Logging returns zero values. Fallible queries and actions return `(value, nil)` on success and `(nil, error)` on failure. Used where one value is expected, such a call yields the value or stops the handler with its error. An ignored failure prints a console warning. An empty result list is a successful query. Expected failures include `device.unknown`, `device.unavailable`, `host.denied`, `world.unloaded` and `argument.range`, with short actionable messages. Incorrect arity or types and interpreter budget exhaustion are runtime faults.

Each Minecraft call runs on the server thread. Derive allowances from server policy, owner permissions, device ownership, protections and the active budget. Check loaded status before any world access. Charge native work before scanning or mutating. Caps on radius, result count and mutation cost belong in the API contract and its metadata.

## Resolve an object, then use it

```fsig
on sensor.detected("entrance") {
    door, err = redstone.from("door")
    if err is not nil { log.error(err); return }
    ok, err = door.set(15)
    if err is not nil { log.error(err) }
}
```

- `devices.list()` describes every network member as `{name, id, kind, capabilities, connected}`.
- Hardware factories such as `detectors.from(name)` return a typed object for one kind. A device of another kind returns `device.type`. Every device object has `id`, `name`, `kind` and `connected` properties.
- `devices.from(alias)` returns a Device with `id`, `name`, `kind`, `capabilities`, `connected` and `position` properties. A stale Device reports false connectivity and `nil` position. Position is a detached read-only dictionary with `dimension`, `x`, `y`, and `z`.
- `redstone.from(alias)` returns a RedstonePort with `read()` and `set(strength)`. Calls use the same checks as the procedural API. Renaming the same face and mode preserves a handle. Changing its mode or removing it invalidates the old handle.
- `inventory.from(alias)` returns an Inventory with [sided read and transfer methods](INVENTORY.md).
- `log.error(errorOrString)` writes through the controller's output allowance and returns zero values.

Factories return `handle, nil` or `nil, error`. Store aliases as strings in persistent globals and resolve handles when needed. See [host-object contracts](../engineering/HOST-OBJECTS.md).

## Compatible procedural device calls

Pass a device alias to `devices` or a port alias to `redstone`. Every call returns exactly two values. Use direct-call assignment such as `ok, err = redstone.set("door", 15)`. An expression statement may explicitly discard both results.

| Function | Success value | Capability |
| --- | --- | --- |
| `devices.get(alias)` | A detached dictionary containing `alias`, UUID string `id`, `kind` and block coordinates `x`, `y`, `z`. | `devices.read` |
| `devices.connected(alias)` | Boolean reachability for a known alias, including `false` for an offline or disconnected device. | `devices.read` |
| `redstone.read(alias)` | Integer strength for the configured input or current output. | `redstone.read` |
| `redstone.set(alias, strength)` | `true`; the strength must be an integer from 0 through 15 and the face must be an output. | `redstone.write` |

An unknown device or port returns `device.unknown` or `port.unknown`. Errors also distinguish unavailable devices, unloaded neighbor areas, incorrect port modes and out-of-range strengths. The typed registry handles capability denial, arity, argument types and output validation. The adapter charges a base registry cost plus a bounded lookup allowance and alias length. Only the owner network can address a device, and server-thread calls check current loadedness.

`on sensor.detected("entrance")` and `on sensor.cleared("entrance")` receive Boolean values in `event.payload`. `on redstone.changed("input")`, `on redstone.rising("input")` and `on redstone.falling("input")` receive the new numeric strength in `event.payload`. Filters are evaluated at activation. See [device setup and sampling behavior](DEVICE-NETWORK.md).

## Planned namespaces

| Namespace | Representative contract |
| --- | --- |
| `controller` | `id()` and `position()` return one direct value. |
| `sensor` | `scan("front_sensor")` returns bounded snapshots and an error. Availability and sensor capability are checked before scanning. |
| `players` | `from("Steve")` and `nearby(10)` resolve policy-filtered player objects. |
| `blocks` | `at(position)` resolves a block handle with read-only identity, position and state. |
| `world` | Device-independent `time()` and weather reads. A Weather Station already provides both. Mutation requires a separate capability and protection checks. |
| `entities` | Device-independent `nearby(radius)`. An Entity Detector already provides bounded scans. |
| `items` | `describe(id)` returns registry metadata and an error. |
| `timer` | `on timer.every(20)` registers a positive tick period at activation; it is an event declaration. |
| `log` | `info(message)` and `warn(message)` are planned alongside the implemented `error(message)`. |

`on sensor.detected("front_sensor")` and `on redstone.rising("left")` use the existing qualified event grammar. Filters are activation-time expressions and the `event` binding supplies the payload. Return pairs use direct-call assignment, for example `nearby, err = players.nearby(10)`. Namespaces resolve through registered call names; no grammar extension is needed.

## Metadata and discoverability

Use core `HostRegistry.Function` and `HostRegistry.ObjectType` as the executable source of truth for qualified names, named parameter slots, accepted types, returns, documentation, capability sets and work cost. A host-owned catalog can add typed event payloads, stable error codes, examples, version and bounds for tooling. Generate or validate completion items, hover/signature help and the FilaSignal API reference against that catalog. The in-game editor and VS Code tools consume the metadata without loading Minecraft classes. Standalone language semantics and the standard library remain in FilaScript Documentation.
