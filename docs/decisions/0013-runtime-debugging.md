# ADR 0013 — Runtime status and statement debugging

Status: accepted for Milestone 11.

## Context

Players need to see why a program failed, where it is, what it holds and whether it is running, paused or limited. The interpreter is a recursive tree walker. Suspending it in the middle of a nested call requires keeping its Java stack. An explicit-stack interpreter would be a large rewrite. Java continuations are internal JDK API. Re-executing a handler to reach a point would repeat world side effects.

## Decision

**Hook.** `Interpreter.instrument` installs an optional `Instrumentation`. It is called before every statement and routes every host function and output callback. Without instrumentation, execution performs one null check per statement and calls hosts directly. Global initializers run during activation and are not statement boundaries.

**Coroutine.** A `Debugger` belongs to one program execution instance and has no block, GUI or Minecraft dependency. While a debugger is attached, each handler invocation runs on a virtual thread used strictly as a coroutine. The owner thread blocks until the worker suspends or finishes, and the worker blocks while suspended. Exactly one of them runs at a time. `SynchronousQueue` handoffs publish memory effects. Host callbacks and output run on the owner thread, so Minecraft world access stays on the server thread. This is a bounded exception to the rule against controller threads: at most 16 debuggers per scheduler and 8 sessions per Minecraft server, each with at most one parked thread.

**Semantics.**

- A line breakpoint pauses at a statement that starts on that line. It pauses once each time execution arrives at the line, so several statements on one line or a one-line loop do not pause repeatedly. At most 64 breakpoints apply to the installed program.
- Pause takes effect at the next executed statement. When the program is idle, that is the first statement of its next handler.
- Step continues to the next executed statement, including one inside a called function or a later handler. There is no separate step over or step out.
- A paused handler keeps its original operation budget. Resumed work is charged to the scheduler's global tick budget and only starts when a full handler budget remains in that tick. Inspection is bounded and uncharged.
- The scheduler skips a paused controller. Other controllers keep running. Its queued events stay bounded by the normal queue limit, and timer periods that elapse while paused are skipped rather than delivered as a burst.
- Globals cannot be saved while a handler is paused. World saves keep the last complete record. Stop, restart, program installation, unload and server shutdown cancel a paused handler with a `CANCELLED` fault. Like other faults, writes made before the pause remain.
- Snapshots contain up to 32 locals and 32 globals or constants, 16 frames and 120-unit value summaries. Containers show at most eight entries and three levels and mark cycles. Host handles show only their type and never call a host.

**Minecraft sessions.** A session exists while at least one authorized player has the debug panel open. Up to four players can monitor one computer. Every request repeats the editor's proximity and ACL checks. Monitors are rechecked every tick for presence, level and distance, and every 20 ticks for access. Detaching, disconnecting, leaving range or losing trust removes a monitor. Without monitors, breakpoints and pause requests are cleared, a paused handler continues on the next scheduler turn and the debugger detaches. The session survives Stop and Restart, so a breakpoint can pause the next startup handler.

**Protocol.** Debugger actions reuse the editor request payload with new action codes. They do not take or change the edit revision. Detach bypasses the request quota because it only removes the sender's monitor. One clientbound `editor_debug_v1` payload carries state, the latest fault or activation rejection with its frames, the paused statement, variables, breakpoints, statistics and console. Every string and list has a codec bound. A maximal view encodes well under the 1 MiB clientbound limit. The server sends a view on change, at most every two ticks per monitor, and refreshes statistics every two seconds. The editor reply gained a short state line, so its payload ID moved to `editor_reply_v2`.

**Diagnostics.** Each loaded computer keeps a 48-line console of output, runtime errors and lifecycle messages, the latest located fault with its call stack, and the latest activation rejection. These survive Stop and Restart and are discarded when the block entity unloads. Installing a new program clears stale fault locations. Program output is mirrored to the server log for at most 20 lines per computer per minute.

## Consequences

Debugging works for any interpreter host that can drive a `Debugger`, including future capability-restricted devices that use the shared scheduler. A debugged handler costs an extra thread start and one handoff per host call. On the development machine, a 600-operation timer handler took about 30–40 µs plain and 42–50 µs with a debugger attached and no breakpoint hit. The coroutine relies on host callbacks being short and non-blocking, as all host functions already must be.

Limitations: no conditional breakpoints, watches, expression evaluation, variable editing, step over or step out. Breakpoints refer to installed lines, so an unsaved draft may not align. Locals of outer frames are not listed. A paused program cannot save new global values until it resumes or is cancelled.
