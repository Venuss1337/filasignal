---
title: Automatic door
description: Open an iron door when a player walks up and close it when they leave, with an Entity Detector and a Redstone Controller.
sidebar:
  order: 5
---

**You need:** a computer, a Network Repeater, an Entity Detector named `porch`, a Redstone Controller named `door` and
an iron door.

## Build

1. Place the iron door. Place the Redstone Controller so the door is directly **south** of it, touching it.
2. Place the detector beside the doorway, and set its **Radius** to 3 in its form so it only notices people at the door.
3. In the detector's form, set **Events report** to `player`, so animals and items do not open the door.
4. Link both devices to the repeater and name them `porch` and `door`.

## Program

```fsig
const porch = detectors.named("porch")
const door = redstoneControllers.named("door")

on startup {
    door.setMode(Direction.SOUTH, PortMode.OUTPUT)
}

on porch.countChanged(count, previous) {
    if count > 0 {
        door.set(Direction.SOUTH, 15)
    } else {
        door.set(Direction.SOUTH, 0)
    }
}
```

## How it works

`countChanged` runs whenever the number of players near the detector changes. With at least one player, the south
side sends power and the door opens. When the last player leaves, the power stops and the door closes.

The door may stay open up to half a second longer, because the detector looks every 10 ticks.

## A door without a program

If you only want the door to open for certain players, an [Access Controller](/minecraft/hardware/access-controller/)
does it without any program: players use it to ask to come in, and it opens the door for those on its list.
