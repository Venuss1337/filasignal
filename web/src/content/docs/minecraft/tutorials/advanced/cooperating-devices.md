---
title: Cooperating devices
description: A Redstone Controller program combines a detector and a light sensor to light a hall only when it is dark and someone is there.
sidebar:
  order: 2
---

A drive program can listen to other devices on its network. Here one Redstone Controller decides when to light a
hallway by combining two other devices, with no computer involved.

**You need:** an Entity Detector `hall`, an Environment Sensor `hall_light`, a Redstone Controller with lamps above it,
a repeater and a drive. Link everything to the same network and name the detector and the sensor.

## Program

Drive type `redstone_controller`:

```fsig target="redstone_controller"
const hall = detectors.named("hall")
const hallLight = environment.named("hall_light")

global dark = false
global people = 0

fn update() {
    if dark and people > 0 {
        device.set(Direction.UP, 15)
    } else {
        device.set(Direction.UP, 0)
    }
}

on startup {
    device.setMode(Direction.UP, PortMode.OUTPUT)
    update()
}

on hall.countChanged(count, previous) {
    people = count
    update()
}

on hallLight.darkened(light) {
    dark = true
    update()
}

on hallLight.brightened(light) {
    dark = false
    update()
}

on hall.disconnected {
    // Fail safe: if the detector is unreachable, assume nobody is there.
    people = 0
    update()
}
```

## How it works

- The controller's program holds the two facts it needs, `dark` and `people`, in globals, and recomputes the lamp in
  one place, `update()`.
- `hall` and `hallLight` are device constants. On a drive program they give **events only**: the controller cannot
  call `hall.count(...)` or change the sensor's settings. It learns everything from events.
- Set the detector's **Events report** to `player` so animals do not switch the lights on, and keep the lamps from
  shining on the sensor.

## Why not a computer?

A computer program could do the same, but this one keeps working when the computer's area is unloaded, and the lighting
logic lives next to the lights. When devices need to share decisions rather than raw readings, use
[structured custom events](/minecraft/tutorials/advanced/structured-custom-events/).
