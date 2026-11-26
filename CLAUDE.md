@AGENTS.md

## Claude Code

- The shared instructions above are canonical; keep Claude-specific notes here short.
- Subsystem notes live in `filascript-core/AGENTS.md`, `filasignal-fabric/AGENTS.md` and `web/AGENTS.md`. Claude Code
  loads them when you work in those directories; read them first if your session does not.
- Prefer the narrow Gradle commands from the table above. GameTests and the client take minutes; run them in the
  background and read the JUnit report (`-PfilaTestReport=<absolute path>`) rather than the whole console log.
- On Windows the shell may be PowerShell: use `.\gradlew.bat` and set `JAVA_HOME` to a JDK 25.
