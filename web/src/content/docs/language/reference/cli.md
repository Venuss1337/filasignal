---
title: Command-line tool
description: Commands, output and exit codes of the standalone filascript-cli tool.
sidebar:
  order: 7
---

Build the tool as described in [Get the command-line tool](/language/getting-started/install/). It runs on any
operating system with a Java 25 runtime.

## Usage

```text
filascript-cli run FILE.fsig
filascript-cli ast FILE.fsig
filascript-cli
```

| Command | Behavior |
| --- | --- |
| `run` | Reads, checks and activates the file, then calls its zero-argument `fn main()` and discards its results. `print` writes to standard output. |
| `ast` | Parses the file and prints its syntax tree. Name errors are not checked. |
| none, `--version` or `--help` | Prints the version and a usage line. |

The program has no access to files, the network or the environment. Event handlers in the file are checked but never
run. Globals start from their initial values on every run.

## Errors

Problems are written to standard error, one per line:

```text
FILE:LINE:COLUMN: CODE: message
  at FUNCTION (FILE:LINE:COLUMN)
```

The `at` lines appear for runtime faults and list the call sites, innermost first. See
[diagnostic codes](/language/reference/diagnostics/).

## Exit status

| Status | Meaning |
| --- | --- |
| `0` | The program ran to the end. |
| `1` | The file could not be read, had a diagnostic or stopped with a runtime fault. |
| `2` | Wrong arguments. |
