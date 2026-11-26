# FilaScript command-line interpreter

FilaScript is the scripting language of the FilaSignal Minecraft mod. This distribution runs FilaScript programs
outside Minecraft. It needs Java 25 or newer on the `PATH` or in `JAVA_HOME`.

```text
bin/filascript-cli run examples/hello.fsig      (Windows: bin\filascript-cli.bat)
bin/filascript-cli ast examples/hello.fsig      prints the program's syntax tree
bin/filascript-cli --version
```

`run` calls the file's `fn main()`. `print(value)` writes a line to standard output. Errors are printed with the
file, line and column, and the exit status is 1. Each run has an operation budget and a call-depth limit.

Documentation: https://venuss1337.github.io/filasignal/language/

FilaScript is part of FilaSignal and is licensed under the GNU General Public License v3.0 only; see `LICENSE`.
