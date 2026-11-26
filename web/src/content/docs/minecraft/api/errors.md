---
title: Error codes
description: Every error code FilaSignal device functions return, what it means and what to do.
sidebar:
  order: 10
---

Device functions report expected problems as an error value with a `code` and a `message`. The code is stable, so
programs can check it: `if err.code is "device.unavailable" { … }`. Language errors such as `TYPE_MISMATCH` are listed
in [diagnostic codes](/language/reference/diagnostics/).

## Devices

| Code | Meaning | What to do |
| --- | --- | --- |
| `device.unknown` | No device has that name in this network, the handle belongs to another program, or a drive program asked for a device that is not its own. | Check the name and that the device is linked to this network. |
| `device.unavailable` | The device is unloaded or not connected. | Load its area; check the relays between it and the computer. |
| `device.type` | The name belongs to another kind of device. | Use the factory for that kind, such as `alarms.named`. |
| `host.denied` | The program may not do this: missing capability, a read-only Storage Server, a locked or protected container, or a revoked inventory approval. | See the message. |
| `world.unloaded` | A neighboring area that the operation needs is not loaded. | Stay near the build. |
| `argument.invalid` | A choice is not valid, such as an unknown face or filter. | Use a constant such as `Direction.NORTH` or `Entity.HOSTILE`. |
| `argument.range` | A number or text is out of range, such as strength 16 or a 49-character display line. | Check the limits on the device's page. |

## Hardware

| Code | Meaning |
| --- | --- |
| `detector.busy` | Too many detector scans on the server this tick. Try again next tick. |
| `alarm.limit` | 64 alarms are already sounding on the server. |
| `access.limit` | Too many Access Controllers are open on the server. |
| `port.mode` | Writing to a redstone side or port that is an input. Make it an output first. |
| `port.unknown` | The named Network Interface port no longer exists. |

## Inventories

| Code | Meaning |
| --- | --- |
| `inventory.unknown` | No container is approved on that face or under that name. |
| `inventory.missing` | No container is on that face. |
| `inventory.stale` | The container was replaced; approve the face again. |
| `inventory.unavailable` | The container must be opened normally once first (unopened loot), or the device left the network. |
| `inventory.unsupported` | Not a single-block container with item slots, such as a double chest. |
| `inventory.limit` | More than 54 slots. |
| `inventory.same` | `moveTo` from a container to itself. |
| `item.identifier` | Not a namespaced item ID such as `minecraft:iron_ingot`. |
| `item.unknown` | No item with that ID exists. |

## Storage and drives

| Code | Meaning |
| --- | --- |
| `storage.missing` | Nothing is stored under the key. |
| `storage.key` | Invalid key: 1 to 64 letters, digits or `_ . : -`. |
| `storage.value` | The value cannot be stored or nests more than 16 levels. |
| `storage.type` | `append` to a key that does not hold a list. |
| `storage.full` | Not enough space or keys left, or a value over 64 KiB. |
| `storage.damaged` | The Storage Server's data could not be read. |
| `drive.missing` | No drive is installed. |
| `drive.removed` | The drive was removed or moved; ask `drives.from` again. |
| `drive.in_use` | The drive is installed in another block. |
| `drive.damaged` | The drive's saved record could not be read. |
| `drive.busy` | Too many program start or stop requests are waiting. Try again next tick. |

## Events and data

| Code | Meaning |
| --- | --- |
| `event.invalid` | Invalid custom event name or value, or sent from a program that is not running. |
| `event.limit` | Too many sends this tick, or a value over 4,096 bytes. |
| `json.invalid` | `json.parse` or `json.stringify` could not convert the value. |
