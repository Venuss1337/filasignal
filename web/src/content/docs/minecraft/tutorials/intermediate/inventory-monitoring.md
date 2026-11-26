---
title: Inventory monitoring
description: Show what a chest holds on a screen and automatically move overflow items into a second chest.
sidebar:
  order: 5
---

**You need:** a computer, a Network Repeater, an Inventory Module named `chests`, a Display Module named `screen` and
two single chests.

## Build

1. Place one chest **north** of the Inventory Module and another **east** of it.
2. Approve both in the module's form (owner only): face `north`, name `storage`, **Inventory**; then face `east`, name
   `overflow`, **Inventory**.
3. Link the module and the display to the repeater and name them.

## Program

```fsig
const chests = inventoryModules.named("chests")
const screen = displays.named("screen")

fn report() {
    storage = chests.inventory(Direction.NORTH)
    items = storage.items()
    text = "Storage: " + str(storage.occupied()) + " of " + str(storage.size()) + " slots"
    shown = 0
    for item in items {
        // A display has 8 lines: the title and 7 items.
        if shown is 7 {
            break
        }
        text += "\n" + item + ": " + str(items[item])
        shown++
    }
    screen.write(text)
}

on startup {
    report()
}

on chests.contentsChanged(face) {
    report()
}

on timer.every(200) {
    storage = chests.inventory(Direction.NORTH)
    overflow = chests.inventory(Direction.EAST)
    moved = storage.moveTo(overflow, "minecraft:cobblestone", 256)
    if moved > 0 {
        print("Moved " + str(moved) + " cobblestone")
    }
}
```

## How it works

- `chests.inventory(Direction.NORTH)` opens the approved chest on the north side.
- `items()` gives a dictionary from item ID to count, such as `{"minecraft:dirt": 30}`. The loop writes at most seven of
  them, because the screen has eight lines.
- `contentsChanged` runs when the items in an approved chest change, so the screen stays current.
- Every ten seconds `moveTo` moves up to 256 cobblestone into the overflow chest. It moves as much as fits and reports
  how many items moved; nothing is ever lost or duplicated.

Hoppers, barrels and furnaces work too. Double chests are not supported: use single chests or barrels.
