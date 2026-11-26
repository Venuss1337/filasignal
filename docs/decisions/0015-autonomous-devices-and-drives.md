# ADR 0015 — Autonomous devices and programmable data drives

Status: accepted for Milestone 14.

## Context

Hardware devices should run programs from removable drives, keep data while no computer is reachable, and let computers read that data later. A drive is executable content supplied by a player, so it must not grant new authority. Program state must follow the drive between devices without letting two copies run.

## Decision

**One execution model.** `ProgramHost` is the boundary between the shared runtime and a block that owns a program. The Programmable Computer and every `EXECUTION`-capable device implement it. A host has a program and enabled state, a globals snapshot, a host registry and capabilities. `ServerComputers` still owns the single scheduler, activation queue, checkpoints, console, editor and debugger for all hosts. A device program is a normal scheduler controller. Device programs create no threads, and the debugger limits stay server-wide (ADR 0013). The scheduler now admits up to 512 programs and activates up to 8 per tick.

**Drive storage.** The drive item carries a small component: identity, label and target kind ID. A world-level record holds everything else: program source and revision, persistent globals (at most 64 KiB) and named storage (at most 64 KiB). Records are split across 16 saved-data files (`filasignal:drives_0` to `drives_f`) so a save rewrites only changed shards. The world is the single authoritative copy. Nothing large travels with inventory synchronization, and creative clones share one record instead of forking it. In exchange, a drive's data stays in the world where it was written: a drive item moved to another world arrives without its program. A blank drive creates no record until it is written. A world holds at most 4,096 records. `/filasignal drives prune DAYS` removes records that are not installed anywhere and have not been used for that long. An undecodable record is kept unchanged and marked damaged. Its drive cannot run or be edited but stays removable.

**Mounting.** A drive is claimed by the block it is installed in: a device, or a computer slot while being edited. A second block cannot install or edit a drive with the same identity while the claim exists. The claim is released when the drive is removed, the block unloads or the block is broken. When duplicated items are loaded at once, the first to claim the drive wins and the others report that it is in use.

**Authority.** Possessing a drive gives access to its portable data. Drives are not encrypted. Device ownership and trust stay with the device and never move with a drive. Installing, starting, stopping, editing and debugging require the same access as editing a computer program on that device: its owner, a trusted player of its network, or an administrator. Applying with fresh globals and clearing stored data from a form require the owner. Inserting a drive never starts it. Start records who authorized the program on that device, and removing the drive withdraws that authorization. A started program restarts automatically after chunk reloads and server restarts only while the same drive stays installed.

A running device program executes with device authority:
- `device.*` covers only that device's own hardware and is built from the same hardware specs computers use.
- `storage.*` reads and writes that drive's own data.
- An inventory module reaches only faces the owner approved on that module, including when it is unlinked from its computer.
- There is no discovery, no other device, no named factory, no world write beyond the device's own hardware and no network management.

Trusted players could already install computer programs that use the owner's grants, so device programs grant them nothing new.

**Remote access.** A computer resolves `drives.from(name)` for a reachable programmable device on its network. The handle names the drive, not the device, so a replaced drive invalidates old handles. Computers can read metadata, program text, storage keys, one value at a time, usage and status. They can request start, stop and restart. Those requests are queued and applied before the next dispatch, never during one. Remote writes and deletes are not offered. Players manage remote programs through the computer dashboard with the computer's ACL.

**Editing.** The existing editor gained a target: the computer program, the drive in the computer's slot, a device's own drive, or a remote device's drive. Each target uses its own authorization and its own completion catalog. Device catalogs list only `device.*`, `storage.*` and the built-ins. Validation uses the target kind's registry, so a device program that calls computer APIs is rejected before installation. Revisions, stale-edit detection and the fresh-globals confirmation work as for computers.

**Storage semantics.** Named storage keeps detached copies of nil, Booleans, numbers, strings, lists, tuples and string-keyed dictionaries, nested at most 16 levels and without cycles. Each write is encoded before anything changes, so a failure leaves storage untouched. Limits:
- 64 KiB of key and value bytes;
- 256 keys of up to 64 characters (letters, digits, `_ . : -`);
- the language's collection size limit, applied to lists built with `append` too.

Errors are `storage.key`, `storage.value`, `storage.full`, `storage.type` and `storage.missing`. Writes mark the record dirty and reach disk with the world save. A clean shutdown saves them, and a crash loses changes since the last save.

**Lifecycle.** Unloading cancels a paused handler, checkpoints globals into the drive record, ends debugging and releases the claim. Reloading restores the drive and restarts an authorized program with its saved globals. No call stack is serialized and no missed events or readings are replayed. Removing a drive and breaking a device follow the same path. Breaking drops the drive once through vanilla's block-entity removal hook.

## Consequences

The runtime, debugger and editor needed no second lifecycle. Device programs share the per-tick budget with computers. A busy device can delay others but cannot exceed its handler budget, and `/filasignal programs` lists the heaviest programs. World-backed records trade cross-world portability for a single authoritative copy and small items. Drive records and the device block entity save to different files. An interrupted save can leave a record one save behind its device, but each record is always internally consistent.
