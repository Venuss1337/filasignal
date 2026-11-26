# Computer and program editor

Use a FilaSignal client. Place a computer or have its owner grant you access. Right-click it with an empty main hand or an unsigned Book and Quill. The LibGui dashboard shows:
- the device count, how many devices are online and how many device programs run, then the owner;
- a short device list with problems first: offline devices and failed programs in amber, with each device's drive, program, storage use or bridge state;
- a quiet diagnostics line with event handler, delivery, drop and custom-event counts;
- the current runtime snapshot.

Screens taller than the window scroll. The ▶ button next to **Device name** steps through the network's programmable devices for **Edit remote**. **Open Editor** opens a Minecraft multiline text widget. The dark gutter displays source line numbers and marks local syntax errors. Matching brackets at the caret are highlighted.

| Action | Behavior |
| --- | --- |
| Validate | Checks the current draft on the server, including FilaScript bindings and compatibility with saved globals. |
| Save & Apply | Saves a valid draft and queues activation if the controller is enabled. A stopped controller stays stopped. If the draft removes or retypes a saved `global`, the owner or an administrator can confirm **Apply with fresh globals**, which discards all saved globals. |
| Start / Restart | Starts the saved program, or detaches and restarts it with compatible saved globals. It leaves an unsaved draft alone. |
| Stop | Checkpoints globals, detaches execution and clears loaded outputs. The stopped state is saved. |
| Reload | Fetches the current program and revision. Confirms before replacing a local draft. |
| From Book | Imports from an unsigned Book and Quill held in the main hand. The command `/filasignal install X Y Z` remains available. |
| Starters | Replaces the draft with a short starter program, such as the intruder alarm. Press again for the next one; Ctrl+Z restores your draft. Replacing an edited draft asks first. Only starters for the editing target are offered: computer starters for the computer, and a device's own starters for its drive, recognized from a `// Device: KIND` header line. |
| ? | Opens the editor guide on the documentation site after Minecraft's link confirmation. The dashboard and every device form have the same button for their own page. |

To use **From Book**, open the computer while holding the unsigned book in your main hand. The import path applies the same source and snapshot validation as the editor.

## Editing targets

The same editor edits several program locations. Its header names the current one:

| Target | How to open it | Notes |
| --- | --- | --- |
| Computer program | **Open Editor** on the dashboard | The computer's own program. |
| Drive in the computer | **Edit drive** on the dashboard | Choose the drive's **Type** first. Validated against that hardware's API. Never run in the computer. |
| Device drive | **Edit program** in a device form | Start, Stop, Restart and Debug act on the device's program. |
| Remote device drive | **Edit remote** with a device name on the dashboard | The device must be a reachable member of the computer's network. |

Completion lists only the APIs the target can use. Saving, stale-revision checks, fresh-globals confirmation and debugging work the same for every target. See the [hardware reference](HARDWARE.md#programmable-data-drives-and-autonomous-devices).

## Editing shortcuts

| Shortcut | Action |
| --- | --- |
| Ctrl/Cmd+S | Save & Apply |
| Ctrl/Cmd+Z / Ctrl/Cmd+Shift+Z / Ctrl/Cmd+Y | Undo and redo local text |
| Ctrl/Cmd+F, then Enter | Focus Find and jump to the next exact match |
| Ctrl/Cmd+Space | Open suggestions. After `on ` at the start of a line it lists events and inserts a whole handler, such as `on gate.entityEnter(entity) { … }`. After `name.`, where `name` was assigned from a device function such as `detectors.named(...)`, it lists that device's members. Constants such as `Direction.SOUTH` are offered too. Use arrows to select, Enter or Tab to insert, Esc to dismiss. The line below the list explains the selected item. |
| F8 | Move to the next locally detected syntax problem |
| Tab / Shift+Tab | Insert four spaces or remove up to four leading spaces from the active line |
| F9 or click a line number | Toggle a breakpoint on that line. Only lines where a statement starts accept one. |
| F5 | With the debug panel open, pause, or resume a paused program |
| F10 | With the debug panel open, step to the next statement |
| Enter | Insert a newline carrying its preceding space indentation |

Minecraft supplies text input, word and caret movement, mouse selection, clipboard and vertical scrolling. Lines wrap. Completion inserts names and uses a mixed member fallback for receivers whose type is unknown. Suggestion rows show the owning type, signature and available catalog documentation. Server validation reports its first failure in the status area. The dashboard and a freshly opened editor show the execution state, such as Running, Stopped, Paused at line 4 or the reason a program could not start.

## Debug panel

Select **Debug ▸** to open a panel beside the editor. Select **Debug ◂** to close it. The panel needs the same access as editing and updates live while it is open:

- **Status.** Running, waiting for events, paused, stopped, starting, or not running with the rejection location.
- **Last runtime error.** The message, line, column and call stack. A handler stopped by the operation, memory, output, call depth or collection limits says so. Select the error or a stack frame to jump to its line.
- **Call stack and variables.** While paused, the panel lists frames, locals, persistent `global` values and constants. Long, nested and cyclic values are shortened. Device handles show only their type.
- **Statistics.** Operations used by the last handler against its budget, the peak, handler and fault counts, dropped events and the scheduler's last tick.
- **Output.** The last 48 lines of program output, runtime errors and lifecycle messages such as starts, stops, pauses and cancelled handlers.

Use **Pause**, **Resume**, **Step** and **Clear**, or F5, F10 and F9. Breakpoints and pauses take effect at statements. Pausing an idle program pauses at the first statement of its next handler. Step moves to the next statement that runs, including one inside a called function. A paused line is highlighted and the editor jumps to it. Breakpoints use the line numbers of the saved program. Save a changed draft before relying on them.

Pausing affects only this computer. Other computers and the server keep running. Events that arrive while paused wait in the computer's normal queue. Timer ticks that pass while paused are skipped. A paused handler keeps its operation budget, so pausing cannot extend it. **Stop**, **Start / Restart** and **Save & Apply** cancel a paused handler. Writes it made before the pause remain. Breakpoints survive a restart while the panel stays open, so a breakpoint can catch the next startup handler.

Closing the panel or the editor, disconnecting, moving more than eight blocks away or losing access ends your debugging session. When nobody is debugging a computer, its breakpoints are cleared and a paused handler continues. Up to four players can debug one computer, and up to eight computers per server can be debugged at once. The panel cannot evaluate expressions, change variables or set conditional breakpoints.

Use **Save name** on the dashboard to set or clear the computer's existing network alias. The scheduler meter shows total operations for the most recent server tick against the shared tick budget.

## Device forms

Right-click a nearby interface or sensor with an empty main hand. The interface form saves a device alias, an input/output port or an inventory alias for a world face. Clear an alias with an empty value or `-`. The sensor form saves the alias, radius and scan period. Inventory grants still require the computer owner or administrator. Use the linking tool for topology changes.

## Manual verification walkthrough

1. Run `./gradlew.bat :filasignal-fabric:runClient --console=plain` and place a computer, interface and sensor in an owned world. Link them. Open the forms and save a sensor radius and a redstone port.
2. Open the editor, type a second handler, paste multi-line text containing CRLF and supplementary Unicode, and exercise selection, clipboard, undo, redo, search and indentation. Test at two GUI scales and resize with an unsaved draft.
3. Introduce a syntax error, navigate to it with F8, and confirm that server Validate rejects it. Fix it, use Save & Apply, then Stop and Start / Restart. Reopen to confirm the saved source and globals.
4. In multiplayer, trust another player and open the same computer from both clients. Apply from one. Attempt to apply from the other. Confirm its draft stays intact when the stale write is rejected. Revoke trust and retry, and verify an unauthorized player cannot open the editor.
5. Install an unsigned book via the command and confirm that the revision changes. Verify the editor can reload the book source. Close and reopen the world and check ownership, configuration, source and globals.
6. Open **Debug ▸**, click a line number to set a breakpoint, then use Start / Restart. Confirm the highlighted line, variables and call stack. Step with F10, resume with F5 and confirm the output list. Introduce a division by zero and select the reported error to jump to it. Close the editor while paused and confirm the program continues.

See [release acceptance](../RELEASE-ACCEPTANCE.md) for the manual and two-player checks.
