---
title: The program editor
description: Open the computer's editor, write and save programs, use starters and completion, read errors and debug.
sidebar:
  order: 2
---

Programs are written inside Minecraft in the FilaSignal editor. You do not need any other software.

## Open it

Right-click a computer with an empty hand. The **dashboard** opens: it shows whether the program runs, lists the
network's devices with problems first, and has the drive slot controls. Select **Open Editor**. The `?` button opens
this documentation after Minecraft asks you to confirm the link.

The same editor also edits a [drive's program](/minecraft/guides/drives/). Its header always says what you are editing,
for example *Computer program* or *Drive gate_logger in computer · entity_detector*.

## Write and save

Type into the editor like any text box. Line numbers are on the left, and syntax errors are marked in the gutter as you
type.

| Button | What it does |
| --- | --- |
| **Validate** | Checks the program on the server without saving it. |
| **Save & Apply** | Saves the program and starts it if the computer is running. A stopped computer stays stopped. |
| **Start / Restart** | Starts the saved program, or restarts it with its saved globals. |
| **Stop** | Stops the program, saves its globals and turns off redstone outputs it controls. |
| **Reload** | Loads the saved program, replacing your draft after asking. |
| **From Book** | Copies the text of a Book and Quill you hold in your main hand. |
| **Starters** | Loads a ready-made example. Select it again for the next one. |
| **?** | Opens the editor guide after Minecraft asks you to confirm. |

Saving never runs broken code: if the program has an error, nothing changes and the first problem is shown.

The computer's screen in the world shows what happened: green text while a program runs, a dark screen when stopped,
and red when the program failed to start or a handler stopped on an error.

### Fresh globals

If you change or remove a `global` that the old program saved, the editor asks **Apply with fresh globals?** Agreeing
discards every saved global of the program. Only the owner or an operator can confirm it.

## Starters

**Starters** replaces your draft with a short example program, and pressing it again shows the next one. Your previous
draft is not lost: press Ctrl+Z to bring it back. The editor only offers starters that can run where you are editing:
computer starters on a computer, and the matching device's starters on a drive.

| Starter | Runs on | Needs |
| --- | --- | --- |
| Intruder alarm | computer | Entity Detector `gate`, Alarm Module `siren` |
| Night lights | computer | Weather Station `sky`, Redstone Controller `lamps` with lamps on its south side |
| Alarm buttons | computer | Control Panel `panel`, Alarm Module `siren` |
| Weather board | computer | Weather Station `sky`, Display Module `screen` |
| Low stock warning | computer | Inventory Module `chests` with an approved chest, Display Module `screen` |
| Door guest book | Access Controller drive | an Access Controller |
| Visitor counter | Entity Detector drive | an Entity Detector |
| Guard alarm | Alarm Module drive | an Alarm Module and an Entity Detector `gate` on the same network |

Name your devices like the starter, or change the names in the program.

## Completion

Press **Ctrl+Space** (Cmd+Space on macOS) to see suggestions. Use the arrow keys to choose, Enter or Tab to insert and
Esc to close. The line under the list explains the selected item.

- At the start of a line after `on `, it lists every event and inserts a whole handler, for example
  `on gate.entityEnter(entity) { … }`.
- After a device constant and a dot, such as `gate.`, it lists that device's methods and events.
- It offers constants such as `Direction.SOUTH` and the functions of the editing target only.

## Errors

- **Before running:** a mistake such as a misspelled name is shown in the status line with its line and column. Press
  **F8** to jump to the next problem the editor found.
- **While running:** a runtime error stops the handler it happened in, and the program keeps waiting for the next event.
  The dashboard shows the last error, and the computer's screen turns red.
- **Ignored failures:** a device call whose result you do not use, such as `siren.activate()` while the alarm is
  unloaded, writes a warning to the output instead of stopping the program.

## Debugging

You do not need the debugger to write programs. When a program does something unexpected, select **Debug ▸** to open
a panel next to the editor:

- **Output:** the last 48 lines from `print`, errors and starts and stops. This is where `print` shows its text.
- **Last runtime error:** the message, line and call stack. Select it to jump to the line.
- **Breakpoints:** click a line number, or press F9, to pause the program when it reaches that line. While paused, the
  panel lists variables, globals and the call stack. **Step** (F10) runs one statement, and F5 pauses or resumes.
- **Statistics:** how much of its work budget the last handler used.

Pausing only affects this computer. Breakpoints follow the saved program's line numbers, so save before relying on
them. Closing the editor, walking away or losing access ends debugging and lets the program continue.

## Shortcuts

| Keys | Action |
| --- | --- |
| Ctrl+S | Save & Apply |
| Ctrl+Z, Ctrl+Shift+Z or Ctrl+Y | Undo and redo |
| Ctrl+F, then Enter | Find the next match |
| Ctrl+Space | Suggestions |
| Tab, Shift+Tab | Indent or unindent the line by four spaces |
| F8 | Next syntax problem |
| F9 | Toggle a breakpoint |
| F5, F10 | Pause or resume, step (with the debug panel open) |

On macOS, use Cmd instead of Ctrl. Long lines wrap; there is no horizontal scrolling.

## Programs from books

You can also install a program from an unsigned Book and Quill: hold it in your main hand and select **From Book**, or
run `/filasignal install X Y Z` at the computer's position while holding it.
