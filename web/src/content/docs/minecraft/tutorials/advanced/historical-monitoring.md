---
title: Historical monitoring
description: A computer collects the history that autonomous devices recorded on their drives and turns it into statistics.
sidebar:
  order: 6
---

Autonomous devices record while you are away. When you return, a computer program reads their drives and summarizes.
This builds on the [autonomous weather station](/minecraft/tutorials/intermediate/autonomous-weather-station/) and
[detection history](/minecraft/tutorials/intermediate/detection-history/).

**You need:** the Weather Station `roof` with the weather-logging drive, the Entity Detector `gate` with the visitor
drive, a Display Module `screen` and a computer, all on one network.

## Program

```fsig
const screen = displays.named("screen")

fn countStormy(history) {
    stormy = 0
    for entry in history {
        if entry.weather is not "Weather.CLEAR" {
            stormy++
        }
    }
    return stormy
}

fn weatherLines() {
    roof, err = drives.from("roof")
    if err is not nil {
        return "Weather log: " + err.code
    }
    history, err = roof.read("weather")
    if err is not nil or history.length is 0 {
        return "Weather log: empty"
    }
    days, err = roof.read("days")
    if err is not nil {
        days = 0
    }
    last = history[history.length - 1]
    // A line that ends with an operator continues on the next line.
    return "Days logged: " + str(days) + "\nWeather changes: " + str(history.length) + ", stormy: " +
        str(countStormy(history)) + "\nLast: " + last.weather + " on day " + str(last.day)
}

fn visitorLines() {
    gate, err = drives.from("gate")
    if err is not nil {
        return "Visitors: " + err.code
    }
    total, err = gate.read("total")
    if err is not nil {
        return "Visitors: none yet"
    }
    return "Visitors in total: " + str(total)
}

on timer.every(1200) {
    screen.write(weatherLines() + "\n" + visitorLines())
}

on startup {
    screen.write(weatherLines() + "\n" + visitorLines())
}
```

## How it works

- `drives.from("roof")` gives read access to the drive in the device named `roof`. The computer can read but never
  change it.
- Each read returns a copy of the stored value. The program computes statistics from the copies: the number of
  entries, the stormy ones and the last one.
- If a device is unloaded or has no drive, its section shows the error code instead.
- The text has at most 5 lines, which fits the display's 8.

## Going further

- Keep long-term totals on a [Storage Server](/minecraft/tutorials/advanced/shared-storage-server/), because device logs
  only keep their newest entries.
- Use `drive.status()` to report devices whose programs stopped, and `drive.restart()` to restart them.
