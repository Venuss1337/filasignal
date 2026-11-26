---
title: Structured custom events
description: Design a small request and status protocol between a computer and a device program with dictionaries in custom events.
sidebar:
  order: 3
---

Custom events can carry dictionaries, so programs can exchange structured messages. This tutorial builds a small
protocol: the computer **requests** a lamp change, the lamp's controller **applies** it and **reports** its status.

**You need:** a computer, a Redstone Controller named `lamp` with a lamp on its north side and a drive, a Control Panel
`panel` and a Display Module `screen`, on one network.

## The messages

| Event | Sent by | Value |
| --- | --- | --- |
| `lamp.request` | anyone | `{version: 1, action: "on" or "off" or "toggle", by: name}` |
| `lamp.status` | the lamp | `{version: 1, lit: true or false, by: name}` |

A `version` field lets you change the format later without confusing old programs. Field names follow the rules for
names and cannot be keywords such as `on` or `in`.

## The lamp (drive type redstone_controller)

```fsig target="redstone_controller"
global lit = false

fn apply(request: dict) {
    if not request.contains("version") or request.version is not 1 {
        return
    }
    if request.action is "on" {
        lit = true
    } else if request.action is "off" {
        lit = false
    } else if request.action is "toggle" {
        lit = not lit
    } else {
        return
    }
    if lit {
        device.set(Direction.NORTH, 15)
    } else {
        device.set(Direction.NORTH, 0)
    }
    events.emit("lamp.status", {version: 1, lit: lit, by: request.by})
}

on startup {
    device.setMode(Direction.NORTH, PortMode.OUTPUT)
}

on events.custom("lamp.request", request, sender) {
    apply(request)
}
```

## The computer

```fsig
const panel = controlPanels.named("panel")
const screen = displays.named("screen")

fn request(action, by) {
    delivered = events.send("lamp", "lamp.request", {version: 1, action: action, by: by})
    if delivered is 0 {
        screen.write("The lamp is not listening")
    }
}

on panel.buttonOne(player) {
    request("toggle", player)
}

on events.custom("lamp.status", status, sender) {
    if sender.name is not "lamp" {
        return
    }
    if status.lit {
        screen.write("Lamp ON (" + status.by + ")")
    } else {
        screen.write("Lamp OFF (" + status.by + ")")
    }
}
```

## How it works

- `events.send("lamp", ...)` delivers only to the program on the device named `lamp` and returns how many programs
  received it, here `0` or `1`.
- `apply(request: dict)` declares that a request must be a dictionary. A program that sends something else only stops
  the lamp's handler with `TYPE_MISMATCH`; the lamp keeps running.
- The lamp ignores unknown versions and actions instead of guessing.
- The lamp reports with `events.emit`, so any number of programs can follow its status, such as several displays.
- The computer checks `sender.name`, which the server fills in, so only the real lamp updates the screen.

## Limits

Each event value is at most 4,096 bytes, and a program sends at most 16 events per tick. Messages cannot carry devices
or constants: send names and `str(constant)` instead. See [custom events](/minecraft/api/custom-events/).
