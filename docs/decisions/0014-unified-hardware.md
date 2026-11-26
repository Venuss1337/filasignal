# ADR 0014 — Unified hardware architecture

Status: accepted for Milestones 12 and 13.

## Context

FilaSignal had one programmable computer, a multi-purpose Network Interface (relay, redstone ports and inventory faces) and a player Sensor. The next phase adds many specialized devices, and Milestone 14 will let some of them run programs from removable drives. The architecture needs to support both without a framework or a class hierarchy per device.

## Decision

**Capabilities, not categories.** `DeviceNetwork.Kind` is the single device-type registry. Each kind declares a set of independent capabilities: `NETWORK` (joins a computer's network), `RELAY` (forwards links), `HARDWARE` (exposes device operations) and `EXECUTION` (runs FilaScript). Only the computer has `EXECUTION` today. Link rules depend on capabilities: every link needs a relay endpoint, as before. Computers and relays accept 16 links, and every other device is a leaf with one uplink. The existing rules for computers, interfaces and sensors are unchanged.

**One block, one block entity.** Every peripheral uses `PeripheralBlock` and `PeripheralEntity`, selected by kind. The interface and sensor keep their block entity types for save compatibility. The eight new blocks share one `filasignal:hardware` type. The block entity owns the stable UUID and physical configuration: detector radius, controller output faces, display text and alarm settings. The topology record still owns membership, names, links, ACLs and inventory grants. The alarm is the only block with an extra state (`lit`).

**Identity and persistence.** Identity, lifecycle, loading, unloading, removal and duplicate handling reuse the existing peripheral path unchanged. The topology record moves to version 3 because it may contain the new kind names. Versions 1 and 2 load unchanged. An older mod disables a version 3 topology and preserves it rather than guessing. New block entity keys are optional, and invalid values fall back to defaults. Redstone outputs and alarm activations are transient: they stop on unload or disconnection, and an alarm saved while lit is switched off when it loads.

**One API mechanism.** `HardwareHosts` turns a declarative `Spec` per kind into a factory, such as `detectors.from(name)`, and a typed host object. Its methods share one resolution path. The device must be a member of the calling computer's network, have the expected kind, be reachable through loaded relays and be loaded itself. Otherwise the call returns `device.unknown`, `device.type` or `device.unavailable`. Handles are scoped to the issuing computer, so a known UUID or a handle from another computer is useless. Every object shares the read-only properties `id`, `name`, `kind` and `connected`. All methods return `(value, err)`. `devices.list()` and the generic `Device` object expose kinds and capabilities. Inventory modules reuse the existing inventory grants and `Inventory` object.

**Authorization.** No new permission system was added. Configuration uses the same proximity check and ACL as other device forms, with network members following their computer's ACL. Approving an inventory face and changing trust require the owner. Membership already requires authorized linking of both endpoints. Script access requires membership plus the controller's capability grants. Inventory operations keep their owner grants, world-border, spawn-protection and protection-hook checks.

**Cost and safety.** Every hardware call charges the registry cost plus a lookup allowance. Entity scans examine at most 256 loaded entities inside a radius of at most 16 blocks and return at most 32 snapshots. A detector's snapshot is reused for the rest of the tick and charged on every query. At most 16 fresh scans run per server tick, after which a scan returns `detector.busy`. Sensing is on demand: no per-tick scanning was added. Display text is limited to 8 × 48 characters. Alarms only visit active alarms, sound at most once a second, last at most 60 seconds and are capped at 64 per server. Redstone writes skip unchanged outputs. Controller output faces are never sampled as inputs, which prevents self-feedback. All host calls run on the server thread. A debugged handler still routes them to the server thread, as described in [ADR 0013](0013-runtime-debugging.md).

## Milestone 14 boundary

An autonomous device would add `EXECUTION` to its kind and run a `Simulation` controller on the shared scheduler, with a `Debugger` and `ProgramConsole` as the computer does. Its host registry would come from the same `Spec` operations, resolved against the device itself instead of a computer's network, so it receives only its own hardware. No drive, storage or self-scoped registry exists yet. Today `HardwareHosts` is scoped to a computer network, and `Target` resolution is the seam a device-scoped variant would replace.

## Consequences

Adding a device needs a kind, a block registration, a `Spec`, readings for its form and tests. The Network Interface remains for existing worlds and combines relay, port and inventory roles. New builds can use the repeater, redstone controller and inventory module instead. Placeholder vanilla models, no recipes and no world-space display rendering are deliberate. Later milestones own visuals and crafting balance.
