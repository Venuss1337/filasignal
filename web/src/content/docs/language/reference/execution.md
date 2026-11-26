---
title: Execution and limits
description: How a FilaScript program is checked, started and run, how faults behave and which resource limits apply.
sidebar:
  order: 5
---

## From text to running program

1. **Read.** The source is decoded and split into tokens. Lexical problems, such as an unterminated string, are
   reported with their position.
2. **Parse.** The tokens are built into a syntax tree. Several syntax errors can be reported at once.
3. **Resolve.** Every name is matched to its declaration, and rules such as "no statements at the top level" and "no
   assignment to constants" are checked. A host can also check event names and device references here.
4. **Activate.** Top-level declarations are registered. Constants and missing globals are initialized in source order.
   A host may first restore saved globals. If activation fails, nothing of the program runs.
5. **Run.** The command-line tool calls `fn main()`. A host calls event handlers as events arrive.

Steps 1 to 3 never run any of your code. A program with a diagnostic never starts.

## Binding rules

- Top-level names (functions, classes, enums, constants, globals) are known everywhere in the file, so a function may
  call a function declared below it.
- Inside a function, assigning to a name that is not visible creates a local in the current block. Assigning to a
  visible name updates it. Reading a name with no visible declaration is a diagnostic.
- Parameters and `for` variables belong to their bodies. A parameter or local `const` may shadow an outer name.
- Constants and functions cannot be assigned. Two declarations of one name in the same scope are an error.

## Calls

- A call must pass exactly as many arguments as there are parameters.
- A call used as a value must produce exactly one result. A call on its own line discards all results.
- `a, b = f()` requires `f` to return exactly two results. Results are checked before any target is written.
- Calls may recurse. Each call counts toward the call-depth limit.

## Faults

A runtime fault, such as `TYPE_MISMATCH`, stops the current invocation (`main`, or one event handler) and reports the
code, message, position and call stack. Writes made before the fault stay. There is no way to catch a fault, and no
automatic rollback.

## Limits

Every invocation gets fresh limits. The command-line tool uses these defaults:

| Limit | Default |
| --- | --- |
| Work per invocation | 100,000 operations (`EXECUTION_BUDGET`) |
| Call depth | 64 nested calls (`CALL_DEPTH_LIMIT`) |
| Data per invocation | 262,144 logical units (`DATA_LIMIT`) |
| Output per invocation | 256 lines and 32,768 characters (`OUTPUT_LIMIT`) |
| Collection size | 4,096 entries (`COLLECTION_LIMIT`) |
| Joined string | 1,048,576 characters |
| JSON text | 65,536 characters, 64 levels deep |
| Syntax nesting | 192 levels; execution nesting 256 |

Operations are counted for evaluating expressions, running statements, copying collection elements and handling text,
so a large loop or a big collection uses more. Hosts choose their own limits. FilaSignal gives each event handler
10,000 operations and 32 call levels, and shares 40,000 operations among all programs per server tick. See
[FilaSignal execution](/minecraft/api/execution/).

## Events in a host

A host queues events per program and runs at most one handler at a time, never inside another handler. Filters are
evaluated once at activation. Each delivered value is copied, so handlers never share a collection. A host may drop
events when a program's queue is full.
