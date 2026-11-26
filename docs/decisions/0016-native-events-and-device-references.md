# ADR 0016 — Native events, device references and approachable host results

Status: accepted for Milestone 15.

## Context

Until Milestone 14, reacting to hardware meant polling with timers, checking `(value, err)` after every call and converting values by hand. That is too much ceremony for a first alarm, and it wastes server work on scans nobody needs. Devices also need to cooperate without a central computer, without giving one device's program control of another device.

## Decision

### Language

These are small additions. Existing programs keep their meaning.

- **Handler values.** `on name(filters…, values…)`: when the host declares the event, its leading arguments are activation-time filters, as before. The remaining arguments must be plain names, and they receive the event's values in order. `on timer.every(20)`, `on redstone.rising("door", strength)` and `on gate.entityEnter(entity)` all use this one rule. Handlers for undeclared events keep the old meaning, where every argument is a filter, in hosts that declare no events, such as the standalone simulator.
- **Declared events.** `HostRegistry` declares events (name, filter slots, value names, documentation), and object types declare their own events. A registry that declares events rejects handlers for unknown events with a diagnostic that points to completion.
- **Device constants.** A host function marked `reference` only builds a value from its arguments and cannot fail. File constants may call it, as in `const gate = detectors.named("gate")`. `on gate.entityEnter(entity)` resolves statically: the constant's declared handle type must declare the event. The activated subscription carries the handle, and the host delivers by subscription index. A reference handle carries the device name, not a device identity, so it resolves on every use and the program does not care what is loaded at start-up.
- **Host enums.** A registry may declare enums, such as `Direction`, `Entity` and `Weather`. They are builtins, so a program's own enum of the same name shadows them. Their members compare by value, persist in globals and work as annotation types.
- **Structured event values.** Events may carry lists, tuples and dictionaries of scalars, bounded to 512 values, 8 levels and 16,384 characters. Each delivery is copied, so receivers never share a mutable collection. Device handles, functions and instances never travel in events. Filters stay immutable scalars.
- **Host results.**
  - A `(value, err)` host call used where one value is expected yields the value. If it fails, it stops the handler with the host's message and code.
  - A failing host call whose results are discarded writes one warning per call site and invocation to the program console.
  - Explicit `value, err = …` is unchanged.
- **Output.** `print` shows whole numbers without `.0` and collections as bounded summaries. The new builtin `str(value)` returns that text.

### Hardware events

- **One catalog.** `HardwareEvents` is the only definition of native events and constants. Registries, editor completion and the documentation read it.
- **Local and remote.** A device program handles its own hardware as `device.<event>`. Any program handles another device through a device constant.
- **The hub.** `EventHub`, one per server, indexes each running program's handlers when it activates.
  - It resolves device names every 20 ticks and whenever the topology or access lists change.
  - It routes each device's events to the listening subscriptions.
  - It removes everything when a program detaches for any reason: stop, replacement, drive removal, unload, block destruction or server shutdown.
  - Delivery only queues work for the program's next turn on the shared scheduler, so no handler runs synchronously and no thread is created.
- **Observation only on demand.** Producers ask the hub before observing.
  - Detectors, environment sensors and inventory modules are sampled only while listened to: every 10 ticks for detectors and 20 for the others, at most 32 devices per tick.
  - Weather is sampled once per level and shared by every listening station.
  - Redstone controllers, alarms, access controllers and panels publish from the change itself.
  - A truncated detector scan never reports departures or counts.
- **Reach.**
  - A computer reaches loaded devices connected to it.
  - A device program reaches loaded devices of its own network joined to it through loaded devices (`DeviceNetwork.connected`), even while the computer is unloaded. This keeps cooperation local and needs no new trust relationship, because one network shares one access list.
  - On a device program, device constants expose only `name`, `connected` and events. Storage servers also expose their storage, and the server's owner can make it read-only for device programs.
  - `connected` and `disconnected` are raised by the listener itself when resolution changes.
- **Custom events.**
  - `events.emit` and `events.send` deliver to programs on the same connected network that declare `on events.custom("name", …)`.
  - The server adds `{name, kind, id}` of the sender.
  - They are bounded: 64-character names, 4 KiB values, 16 sends per program per tick and 64 receivers.
  - They use the program's normal 32-event queue, and overflow is counted as dropped.
  - Custom events are a separate namespace: they can never trigger a native handler.

### Authority

A device program uses the access of the player who started it. Start now records that player's UUID. Every 100 ticks, and whenever the program would restart after loading, the server checks that the player is still the owner, a trusted player or an administrator. If not, the program is stopped with a reason in its console and does not restart. Its drive data is kept. A remote start from a computer uses the network owner's authority. Programs started before Milestone 15 have no recorded starter and use the owner's authority.

### Scheduler admission

Activation now admits up to 16 programs and 64K source characters per tick, and always at least one program. Previously it admitted 8 programs regardless of size. The topology attaches up to 32 loaded devices per tick instead of 8, because attaching is cheap.

## Consequences

- Beginners write a working alarm in four lines with no topics, subscriptions or error plumbing. Experienced programmers keep explicit error handling and gain structured custom events.
- A typo in an event name is now an error before the program runs.
- Device constants do not detect a misspelled device name until the handler runs. The program status lists unresolved names.
- Events are best effort: a full queue drops events and counts them.
- The editor catalog is larger, so its packet bound rose to 48 KiB, and editor packets moved to `editor_reply_v4`.
