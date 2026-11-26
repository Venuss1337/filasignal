---
title: Persistent detection history
description: An Entity Detector with its own drive program records every visitor, and the computer reads the history later.
sidebar:
  order: 2
---

The detector keeps a visitor log on its own [data drive](/minecraft/guides/drives/). The log survives restarts, and it
keeps growing even when the computer is not loaded. Later the computer reads it.

**You need:** a computer, a Network Repeater, an Entity Detector named `gate` and a Programmable Data Drive.

## 1. The detector's program

Put the drive in the computer, set its **Type** to `entity_detector`, select **Edit drive** and enter:

```fsig target="entity_detector"
global total = 0

on device.entityEnter(entity) {
    if not entity.player {
        return
    }
    total++
    now = device.time()
    visits, err = storage.read("visits")
    if err is not nil {
        visits = []
    }
    visits.append({name: entity.name, day: now.day, time: now.dayTime})
    // Keep the newest 50 visits so the drive never fills up.
    while visits.length > 50 {
        visits.remove(0)
    }
    storage.write("visits", visits)
    storage.write("total", total)
}
```

Select **Save & Apply**, then **Eject**. Use the drive on the detector, open the detector and select **Start**.

## 2. The computer's report

On the computer:

```fsig
on startup {
    drive, err = drives.from("gate")
    if err is not nil {
        print("Cannot read the gate's drive: " + err.code)
        return
    }
    total, err = drive.read("total")
    if err is not nil {
        print("No visitors yet")
        return
    }
    visits = drive.read("visits")
    print(str(total) + " visitors in total. Most recent:")
    first = visits.length - 5
    if first < 0 {
        first = 0
    }
    i = first
    while i < visits.length {
        print(visits[i].name + " on day " + str(visits[i].day))
        i++
    }
}
```

## How it works

- `storage.read` gives an error the first time, because nothing is stored yet; the program starts a new list.
- `total` is a global: the drive saves it, so it keeps counting after restarts and when the drive moves to another
  detector.
- The computer reads the drive with `drives.from("gate")`. It can read but never change the drive's data.
- The report prints at most five visits, because one handler may only print 8 lines.

Walk past the detector a few times, then select **Start / Restart** on the computer to see the report.
