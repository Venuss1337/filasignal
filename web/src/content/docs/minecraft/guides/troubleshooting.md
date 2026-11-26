---
title: Troubleshooting
description: Common FilaSignal problems, what usually causes them and how to fix them, with the matching error codes.
sidebar:
  order: 7
---

Start with the device's form and the computer's dashboard: open them with an empty hand. The dashboard lists offline
devices and failed programs first. In the editor, **Debug ▸** shows the program's output and last error.

## A device cannot be found

**Error:** `device.unknown` — *No device has that alias in this network*.

- The name in the program must match the device's form exactly, including upper and lower case.
- The device must be linked into **this** computer's network. A device linked to another computer is invisible.
- `detectors.named("gate")` itself never fails; the name is checked when the program uses it. The dashboard shows names
  that do not resolve.

## A device is disconnected

**Error:** `device.unavailable` — the device is unloaded or cut off. The form says *Computer unreachable*.

- Load its area by going near it. FilaSignal never loads chunks.
- Check every relay between the device and the computer: a broken or unloaded repeater cuts the path.
- Use `on gate.connected` and `on gate.disconnected` to react when this happens.

## The wrong kind of device

**Error:** `device.type`. The name belongs to another kind of device: you asked `alarms.named("gate")` for a detector.
Use the factory that matches the device, listed on its [hardware page](/minecraft/hardware/).

## An event does not run

- The program must be running: the computer's screen is green and the dashboard says *Running*.
- **Detector:** entities already in range when the program starts are not "new". Leave the radius and come back. Check
  the radius and the **Events report** filter in its form.
- **Sampled devices** are checked every 10 to 20 ticks; a very quick change can be missed.
- **Drive programs** only hear devices connected to them through **loaded** devices.
- **Typos are caught early:** an unknown event name is an error when you save, and the editor's completion lists the
  real ones.

## A program cannot start

The dashboard and editor show why, with a line number.

- A syntax or name error: fix it and select **Save & Apply**.
- *Saved globals do not fit this program*: you removed or changed a `global`. Confirm **Apply with fresh globals** (owner
  only) to discard the old values.
- A drive program: the drive is empty, written for another device, or its starter lost access. Open **Edit program**,
  then **Start**.

## A handler stops with an error

A runtime error stops only that handler; the program keeps running and the screen turns red. Read the message in the
debug panel and select it to jump to the line.

- `HOST_FAILURE` means a device call failed while its result was used as a value, for example
  `count = gate.count(Entity.HOSTILE)` while the detector is unloaded. The message includes the device error. Write
  `count, err = gate.count(Entity.HOSTILE)` to handle failures yourself.
- `TYPE_MISMATCH` often means joining text and a number: use `"Count: " + str(count)`.

## The program uses too much work

**Error:** `EXECUTION_BUDGET` — *Execution operation budget exhausted*. Each handler may do 10,000 operations.

- Look for a `while` loop that never ends.
- Do less per handler: process a few items per `timer.every` tick instead of everything at once.
- Avoid scanning with the detector in a tight loop; use events such as `entityEnter` instead.

`OUTPUT_LIMIT` means a handler printed more than 8 lines; print less, or show text on a Display Module.

## A drive is incompatible

The device form says *Incompatible drive: written for …*. Use the drive in the device type it names, or put it in a
computer and change its **Type** before editing.

## Storage is full

**Error:** `storage.full`. A drive holds 64 KiB in 256 keys; a Storage Server 256 KiB in 1,024 keys, with each value at
most 64 KiB. Keep only the newest entries, as in `while history.length > 50 { history.remove(0) }`, or delete old keys.
`storage.key` means an invalid key name, and `storage.value` or `storage.type` a value that cannot be stored, such as a
device or a class object.

## A wireless bridge is out of range

The bridge's form lists only **your own**, **loaded**, **unpaired** bridges within **256 blocks** in the same
dimension. Load both areas, make sure both bridges are yours and unpaired, and move them closer if needed.

## A player lacks permission

*Only the owner, a trusted player or an administrator can configure this device*: ask the owner to trust you in a
device form. Some actions, such as approving chests, changing Access Controller lists and breaking devices, are for the
owner only. `host.denied` in a program means the program may not do that right now, for example a drive program
writing to a Storage Server that only lets device programs read, or an inventory that is locked or protected.

## A chunk is unloaded

**Error:** `world.unloaded`. A redstone or inventory operation needs the neighboring block's area loaded too. Stay near
the build, or keep the whole build inside loaded chunks.

## Client and server versions do not match

If you are disconnected when joining, or blocks look wrong, the server and your client run different FilaSignal builds.
Install the same build as the server. Fabric API and Minecraft must also be the supported versions.

## Error code reference

See [error codes](/minecraft/api/errors/) for every device error, and [diagnostic codes](/language/reference/diagnostics/)
for language errors.
