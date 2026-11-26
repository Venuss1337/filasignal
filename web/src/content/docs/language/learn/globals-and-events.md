---
title: Globals and events
description: Persistent global variables and event handlers in FilaScript, and how their meaning depends on the host.
sidebar:
  order: 9
---

Two features only reach their full meaning inside a host, the program that runs FilaScript. The language defines the
syntax and rules; the host decides when handlers run and where globals are saved.

## Globals

`global` declares a variable at the top of a file that every function and handler shares:

```fsig
global launches = 0
global history = []

fn main() {
    launches++
    history.append(launches)
    print(history)
}
```

```text title="Output"
[1]
```

- A global needs a starting value made only of values written in the program, operators, enum members and earlier
  constants.
- A host can **save** globals and give them back when the program starts again, so a counter keeps counting. The
  command-line tool does not save them: each run starts fresh.
- When a program is changed, saved globals with the same name are kept. A saved value that no longer fits the program,
  such as a global whose type annotation changed, has to be discarded on purpose; the host asks before doing so.
- Each running program has its own globals. Two programs never share them.

## Event handlers

An `on` block at the top of a file is an **event handler**: code the host runs each time something happens.

```fsig
on startup {
    print("Ready")
}

on timer.every(20) {
    print("About a second passed")
}
```

- The event name can have dots, such as `timer.every`. The host decides which events exist. A host that declares its
  events reports an unknown event name as an error before the program runs.
- `startup`, `shutdown` and `timer.every(ticks)` are always available names. In FilaSignal, 20 ticks are about one
  second.
- Values in the parentheses come in two parts. The first ones, up to the number the event declares, are **filters**
  fixed when the program starts, such as the `20` above. The rest are plain names that receive the event's values, like
  function parameters.
- Inside a handler, the name `event` describes the delivery: `event.name`, `event.arguments` (the filters, as a tuple),
  `event.values` (a tuple of the delivered values) and `event.payload` (the first value, or `nil`).
- Handlers never run at the same time. The host queues events and runs one handler at a time per program.
- A runtime fault stops only the handler it happens in.

Events carry only plain data: numbers, strings, booleans, `nil`, enum members and lists, tuples and dictionaries of
them, at most 512 values, 8 levels deep and 16,384 characters. Every handler receives its own copy.

The command-line tool checks event handlers but only runs `main`. To see events in action, use them in
[FilaSignal](/minecraft/guides/events/), where devices raise events such as `on gate.entityEnter(entity)`.
