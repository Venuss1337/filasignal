# Server-side permissions, lifecycle, and threading

The [computer runtime](COMPUTER-PERSISTENCE.md) uses server-thread lifecycle and tick callbacks. Minecraft supplies the placing player's UUID. Linking, commands and player breaks share ownership checks.

## Ownership and migration

Owners can link and configure devices, approve inventory faces and install programs. Use `/filasignal trust X Y Z PLAYER` and `/filasignal untrust X Y Z PLAYER` on the computer to manage up to 16 trusted players. Enter an online name or UUID. Trusted players can configure aliases, redstone ports and sensors, link authorized devices, and install a program preserving globals. Inventory grants, access lists, breaking owned devices and resetting globals require the owner or an administrator. Revoking trust stops future edits. Installed programs continue using the owner's grants.

Administrators may use `/filasignal claim X Y Z PLAYER` on ownerless legacy devices. Claim the computer first. Existing linked peripherals follow it. Claim unlinked legacy devices separately before adopting them. The claim command cannot replace an owner. Vanilla commands and non-player world changes keep their normal behavior.

Inventory grants are separate from edit permission. Each operation checks the current grant, owner, route, loaded state and [protection policy](INVENTORY.md#compatibility-and-limits). The administrator override for configuration does not bypass script inventory protection.

## Capability model

Suggested capabilities: `controller.read`, `redstone.read`, `redstone.write`, `inventory.read`, `inventory.transfer`, `blocks.read`, `players.read.basic`, `entities.read`, `world.read`, `world.write.local`, `world.write.extended`, `players.modify`, `interop.extension`, `network.send`. Capabilities are policy values, **not self-granted by script source**.

Implemented capabilities:
- devices: `devices.read`;
- redstone and inventories: `redstone.read`, `redstone.write`, `inventory.read` and `inventory.transfer`;
- sensing: `detector.read`, `weather.read` and `environment.read`;
- output: `display.read`, `display.write` and `alarm.write`;
- new hardware: `access.control` and `panel.control`;
- drives and storage: `drives.read`, `drives.control`, `storage.read`, `storage.write`, `server.read` and `server.write`;
- custom events: `events.emit`.

The Programmable Computer holds all except the drive storage capabilities. A device program holds its own kind's hardware capabilities, its drive's storage, shared storage and `events.emit`. On a device program, references to other devices expose only events, `name` and `connected`, plus storage on a Storage Server.

**Event authorization.**
- Native events reach a computer program only from loaded devices connected to its computer. They reach a device program only from devices of its own network joined to it through loaded devices.
- Names are resolved again every second and after every topology or access change.
- Custom events follow the same rule. The server fills in the sender, so sender details cannot be forged, and a custom event can never trigger a native handler.

**Program authority.** A device program uses the access of the player who started it. Every 100 ticks, and before any automatic restart, the server checks that this player is still the owner, a trusted player of the network or an administrator. If not, the program stops with a reason in its console, keeps its data and does not restart until someone with access starts it again. See [ADR 0016](../decisions/0016-native-events-and-device-references.md).

Drive programs run with device authority: only their own device's hardware under its current configuration. Installing, starting and editing a drive need the same access as editing that device's computer program. A drive never carries ownership or trust. See [ADR 0015](../decisions/0015-autonomous-devices-and-drives.md). Dedicated hardware also requires network membership, which only an authorized player can create by linking. Hardware forms use the device ACL: network members follow their computer's ACL, and approving inventory faces or changing trust requires the owner.

Controller owner, server admin config, game rules, dimension/chunk, claim/protection rules and resource budgets all participate in authorization. Permission to read a block does not imply permission to change it. Powerful actions may be disabled by default on servers.

## Thread rules

- Minecraft's server controls world state. World reads/writes from host adapters occur on the appropriate server thread.
- Never run Java threads per-controller or call client-only classes in dedicated-server code. A debugged handler is the only exception. It runs on a coroutine thread that never runs concurrently with the server thread, and its host calls execute on the server thread. See [ADR 0013](../decisions/0013-runtime-debugging.md).
- Compile on code changes, not ticks. If compiling off-thread later, only pass immutable source/inputs and safely install validated output on the server thread.
- Host operations must not accidentally block on network/IO or force-load chunks synchronously.
- Redstone updates can produce follow-up events; enqueue rather than re-enter script execution within same call stack.

## Lifecycle requirements

Controller placed → source loaded → compile/validate → initialize/restore globals → register events → execute ticks → save dirty state → unload/dispose. On source replacement, decide last-known-good versus fail-closed policy explicitly; never execute malformed new code automatically. Save serialization should be versioned and bounded. On dimension unload/disconnect, stale handles must fail safely.

## Editor access

The editor checks the player, current world, loaded computer, distance and current ACL before returning source or accepting changes. Debugger requests use the same checks, and open debug sessions recheck them while attached. Applying a program with fresh globals requires the owner or an administrator. Save and execution controls are revision-fenced. The dashboard name uses the same network alias policy as the command. See the [protocol ADR](../decisions/0012-native-editor-and-revisions.md) and the [dedicated acceptance walkthrough](../RELEASE-ACCEPTANCE.md#6-dedicated-server-with-two-players-r).

## Multiplayer security tests

- Unauthorized player cannot edit someone else's controller.
- Client cannot forge privileged world API results or send oversized/malformed packets.
- Controller cannot bypass claims or modify unloaded/protected blocks.
- Players from another dimension aren't accidentally mixed into proximity scans.
- Two controllers cannot amplify each other into unbounded same-tick redstone events.
- World effects before a handler failure are documented as non-transactional (unless an explicit transactional API is later implemented).
