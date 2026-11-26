---
title: Monitoring multiple devices
description: Collect readings from a weather station, an environment sensor and a detector on one status screen.
sidebar:
  order: 1
---

**You need:** a computer, repeaters, a Weather Station `sky`, an Environment Sensor `greenhouse`, an Entity Detector
`gate` and a Display Module `screen`, all linked into one network.

## Program

```fsig
const sky = weather.named("sky")
const greenhouse = environment.named("greenhouse")
const gate = detectors.named("gate")
const screen = displays.named("screen")

fn weatherText() {
    now, err = sky.read()
    if err is not nil {
        return "Weather: offline"
    }
    if now.thundering {
        return "Weather: storm"
    } else if now.raining {
        return "Weather: " + now.precipitation
    }
    return "Weather: clear"
}

fn lightText() {
    light, err = greenhouse.light()
    if err is not nil {
        return "Greenhouse: offline"
    }
    return "Greenhouse light: " + str(light)
}

fn gateText() {
    players, err = gate.count(Entity.PLAYER)
    if err is not nil {
        return "Gate: offline"
    }
    return "Players at the gate: " + str(players)
}

fn redraw() {
    screen.write(weatherText() + "\n" + lightText() + "\n" + gateText())
}

on startup {
    redraw()
}

on timer.every(100) {
    redraw()
}

on gate.countChanged(count, previous) {
    redraw()
}

on sky.weatherChanged(now, previous) {
    redraw()
}
```

## How it works

- Each `...Text()` function reads one device with `value, err = ...`. If the device is unloaded, it returns a short
  "offline" line instead of stopping the program, so one missing device never blanks the whole screen.
- `redraw()` joins the lines with `"\n"`, which starts a new line on the display.
- The screen updates every five seconds, and at once when the weather or the number of players changes.

## Check the whole network

`devices.list()` describes every member of the network, including offline ones. Add this to see what is missing:

```fsig
on startup {
    members, err = devices.list()
    if err is not nil {
        return
    }
    missing = 0
    for member in members {
        if not member.connected {
            missing++
        }
    }
    print(str(members.length) + " devices, " + str(missing) + " offline")
}
```

The computer's dashboard shows the same list, with offline devices first.
