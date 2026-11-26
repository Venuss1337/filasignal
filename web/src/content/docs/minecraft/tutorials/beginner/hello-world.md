---
title: Hello World
description: Write your first FilaSignal program on a computer, see where its output appears and count with a timer.
sidebar:
  order: 1
---

**You need:** one Programmable Computer. Nothing else.

## Write it

1. Place the computer and right-click it with an empty hand.
2. Select **Open Editor**. Select all the text with Ctrl+A and delete it.
3. Type this program:

```fsig
on startup {
    print("Hello, world!")
}
```

4. Select **Save & Apply**. The computer's screen turns green: the program is running.
5. Select **Debug ▸** in the editor. Under **Output** you see *Hello, world!*

`on startup` runs once, when the program starts. `print` writes a line to the program's output.

## Count something

Programs can remember things in **globals**. A global keeps its value even when the program stops and starts again.

```fsig
global ticks = 0

on startup {
    print("Starting the clock")
}

on timer.every(20) {
    ticks++
    print(str(ticks) + " seconds")
}
```

`on timer.every(20)` runs every 20 game ticks, about once a second. `ticks++` adds one to `ticks`, and `str(ticks)`
turns the number into text so it can be joined with `" seconds"`.

Select **Stop**, then **Start / Restart**: the count continues where it was, because `ticks` is a global.

## What you learned

- Programs are made of event handlers that start with `on`.
- `print` shows text in the debug panel's Output.
- `global` values are saved.

Next: build an [intruder alarm](/minecraft/tutorials/beginner/intruder-alarm/), or learn more about the language in
[values and variables](/language/learn/values-and-variables/).
