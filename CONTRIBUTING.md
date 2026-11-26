# Contributing to FilaSignal

Thank you for helping. Bug reports, documentation fixes, examples, tests and code are all welcome.

## Before you start

- **Bugs:** open an issue with the bug report template: versions, steps to reproduce, expected and actual behavior,
  and the relevant part of the log.
- **Changes:** for anything larger than a fix (a new API, language syntax, hardware, a save format change), open an
  issue first so the design can be agreed before you build it.
- **Security problems:** do not open a public issue; see [SECURITY.md](SECURITY.md).

## Set up

You need Git and JDK 25 (`JAVA_HOME` set). For the documentation site you also need [Bun](https://bun.sh) 1.4.4; for
the asset tools, Python 3.10+ with Pillow.

```sh
git clone https://github.com/Venuss1337/filasignal.git
cd filasignal
./gradlew build                          # all tests, including server GameTests, and the mod JAR
./gradlew :filasignal-fabric:runClient   # a development Minecraft client with the mod
./gradlew :filasignal-fabric:runServer   # a development server (accept the Minecraft EULA in run/eula.txt)
```

On Windows PowerShell use `.\gradlew.bat`. The [AGENTS.md](AGENTS.md) command table lists the focused test commands;
[testing](docs/engineering/TESTING.md) describes the verification scripts. `tools/verify/Verify-All.ps1` runs every
automated check with one summary.

## Making a change

- Keep pull requests focused on one change. Don't reformat unrelated code.
- Follow the [engineering standards](docs/engineering/STANDARDS.md) and the invariants in [AGENTS.md](AGENTS.md):
  core stays free of Minecraft code, scripts reach the game only through checked host APIs, everything runs on the
  server thread within budgets, and saved formats are versioned.
- Add or update tests: a regression test for a fix, and tests for normal, boundary and failure cases for a feature.
  [Extending FilaSignal](docs/development/EXTENDING.md) shows where each kind of change goes.
- Update the documentation the change affects (`web/src/content/docs/`), and regenerate the API catalog when host
  APIs or events change.
- Changes to permissions, client requests, resource limits or saved data need tests for the refused or failing case.
  Do not relax a limit without measurements and an explanation.
- Avoid new dependencies unless they are clearly needed and their license is compatible.

## Pull requests

Describe what changed and why, link the issue, and list the commands you ran with their results. Say what you could
not verify (for example, behavior that needs a second player). CI runs the full build and the documentation site on
every pull request.

## AI-assisted contributions

You may use AI coding tools; the repository includes instructions for them ([AGENTS.md](AGENTS.md),
[CLAUDE.md](CLAUDE.md)). You are responsible for what you submit: understand it, review it, test it, and make sure
you have the right to contribute it. The same quality, security and licensing expectations apply to every change.

## License

FilaSignal is licensed under the [GNU General Public License v3.0 only](LICENSE). By submitting a contribution, you
agree that it is licensed under the same terms. There is no copyright assignment or contributor license agreement;
you keep the copyright to your contribution. Do not submit code or assets you are not allowed to license this way.

Please follow the [code of conduct](CODE_OF_CONDUCT.md).
