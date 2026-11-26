---
title: Shared storage server
description: Several programs, on devices and on the computer, write to one Storage Server ledger, and the computer summarizes it.
sidebar:
  order: 4
---

A [Storage Server](/minecraft/hardware/storage-server/) is shared by every program on its network. Here two device
programs write into one ledger and the computer reads it.

**You need:** a computer, a repeater, a Storage Server `ledger`, an Inventory Module with an approved chest on its
north side, an Entity Detector, a Display Module `screen` and two drives.

## The warehouse (drive type inventory_module)

Every minute it writes the chest's totals:

```fsig target="inventory_module"
const ledger = storageServers.named("ledger")

on timer.every(1200) {
    chest = device.inventory(Direction.NORTH)
    ledger.write("stock", chest.items())
    ledger.write("stock.day", device.time().day)
}
```

## The gate (drive type entity_detector)

It keeps a short list of recent players:

```fsig target="entity_detector"
const ledger = storageServers.named("ledger")

on device.entityEnter(entity) {
    if not entity.player {
        return
    }
    recent, err = ledger.read("visitors")
    if err is not nil {
        recent = []
    }
    recent.append(entity.name)
    while recent.length > 20 {
        recent.remove(0)
    }
    ledger.write("visitors", recent)
}
```

## The computer

```fsig
const ledger = storageServers.named("ledger")
const screen = displays.named("screen")

on timer.every(600) {
    lines = "Ledger: " + str(ledger.keys().length) + " keys"
    stock, err = ledger.read("stock")
    if err is nil {
        total = 0
        for item in stock {
            total += stock[item]
        }
        lines += "\nItems in stock: " + str(total)
    }
    visitors, err = ledger.read("visitors")
    if err is nil and visitors.length > 0 {
        lines += "\nLast visitor: " + visitors[visitors.length - 1]
    }
    space = ledger.usage()
    lines += "\nUsed " + str(space.used) + " of " + str(space.capacity) + " bytes"
    screen.write(lines)
}
```

## How it works

- Each program gets the server with `storageServers.named("ledger")`. Device programs may read and write it unless its
  owner switches **Device programs** to `read` in its form.
- Programs never share a live list: every `read` gives a copy, and `write` stores a new copy. Two programs updating the
  same key at different times work, but the last write wins, so give each program its own keys.
- `usage()` reports the space used of the server's 256 KiB.

:::caution
Breaking the Storage Server deletes the ledger. Keep it somewhere safe.
:::
