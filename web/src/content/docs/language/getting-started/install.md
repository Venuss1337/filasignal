---
title: Get the command-line tool
description: Download or build the standalone FilaScript command-line tool and run your first program.
sidebar:
  order: 1
---

The FilaScript command-line tool (CLI) runs `.fsig` programs on your computer, without Minecraft.

## What you need

A **Java runtime or JDK 25** or newer, for example Eclipse Temurin 25, on your `PATH` or in the `JAVA_HOME`
environment variable. You do not need Minecraft, Node.js or Bun.

## Download

1. Download `filascript-cli-1.0.0.zip` from the
   [GitHub release](https://github.com/Venuss1337/filasignal/releases/latest) and unzip it.
2. In a terminal, go into the unzipped `filascript-cli-1.0.0` folder and run the included example:

```sh
# Linux and macOS
bin/filascript-cli run examples/hello.fsig
```

```powershell
# Windows
.\bin\filascript-cli.bat run .\examples\hello.fsig
```

You should see:

```text
Hello, FilaScript!
```

## Build it yourself

With Git and a JDK 25:

```sh
git clone https://github.com/Venuss1337/filasignal.git
cd filasignal
./gradlew :filascript-cli:installDist
filascript-cli/build/install/filascript-cli/bin/filascript-cli run examples/hello.fsig
```

On Windows PowerShell, use `.\gradlew.bat` and `.\filascript-cli\build\install\filascript-cli\bin\filascript-cli.bat`.

## Commands

| Command | What it does |
| --- | --- |
| `filascript-cli run FILE.fsig` | Checks the program and runs its `fn main()`. |
| `filascript-cli ast FILE.fsig` | Prints the program's syntax tree, a debugging view of how it was understood. |
| `filascript-cli --version` | Prints the version and a usage line (also with no arguments). |

See the [CLI reference](/language/reference/cli/) for exit codes and error output.

Next: [write your first program](/language/getting-started/first-program/).
