---
title: Autonomous weather station
description: A Weather Station with its own drive program logs every weather change and counts days, without a computer.
sidebar:
  order: 3
---

**You need:** a Weather Station named `roof` and a Programmable Data Drive. A computer is only needed to write the drive.

## Program

Write this onto a drive whose **Type** is `weather_station`, install it in the station and select **Start**:

```fsig target="weather_station"
on device.weatherChanged(now, previous) {
    when = device.time()
    history, err = storage.read("weather")
    if err is not nil {
        history = []
    }
    history.append({weather: str(now), day: when.day, time: when.dayTime})
    while history.length > 64 {
        history.remove(0)
    }
    storage.write("weather", history)
}

on device.dayStart {
    days, err = storage.read("days")
    if err is not nil {
        days = 0
    }
    storage.write("days", days + 1)
}
```

## How it works

- `device.weatherChanged` runs on the station itself. `now` is a constant such as `Weather.RAIN`; `str(now)` turns it
  into the text `"Weather.RAIN"`, because constants cannot be stored.
- Each entry is a small dictionary. The list keeps the newest 64 changes.
- `device.dayStart` counts sunrises in storage instead of a global, so a computer can read the count too.

The station records while its area is loaded, whether or not a computer is nearby. Open its form and select **Storage**
to see the stored keys.

Use the data later in [historical monitoring](/minecraft/tutorials/advanced/historical-monitoring/).
