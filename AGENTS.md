# Instructions for coding agents

FilaSignal is a Minecraft Java Edition mod (Fabric) built around **FilaScript**, its own small scripting language.
Players program in-game computers and hardware. The language also runs standalone through a CLI. This file is the
entry point for AI coding agents (Claude Code reads it through `CLAUDE.md`). Human contributors: see
[CONTRIBUTING.md](CONTRIBUTING.md).

## Environment

- JDK 25 (set `JAVA_HOME`); the Gradle wrapper downloads Gradle 9.6.1. Minecraft 26.3, Fabric Loader 0.20.1,
  Fabric API 0.166.2+26.3, Loom 1.17.21 (non-remapping). Versions are pinned in `filasignal-fabric/build.gradle.kts`;
  the project version is set once in the root `build.gradle.kts`.
- The documentation site needs Bun 1.4.4 (`web/`). The PowerShell scripts in `tools/` need PowerShell 7.
- Use `./gradlew` on Linux and macOS and `.\gradlew.bat` on Windows. Commands below use `./gradlew`.

## Layout

| Path | Responsibility |
| --- | --- |
| `filascript-core/` | The language: source, lexer, parser, AST, resolver (`analysis`), interpreter, events, persistence, debugger, host registry. **No Minecraft or Fabric imports.** |
| `filascript-cli/` | Standalone `run` and `ast` commands over core. |
| `filasignal-fabric/src/main` | Server and common mod code: `computer/` (scheduler, programs, editor protocol), `peripheral/` (network, hardware APIs, events), `drive/` (drives), `device/` (kinds and capabilities). |
| `filasignal-fabric/src/client` | Client-only editor, screens and renderers. Never referenced from `main`. |
| `filasignal-fabric/src/gametest` | Server and client GameTests, the API catalog export and the benchmark. |
| `web/` | Astro + Starlight site: `/language/` and `/minecraft/` areas. `web/src/data/generated/` is generated. |
| `examples/` | Standalone examples and the in-game starter programs (`starter-*.fsig`). |
| `docs/` | Architecture, ADRs (`docs/decisions/`), language spec and grammar, engineering references. |
| `tools/` | `verify/`, `benchmarks/`, `assets/` (texture and model generator), `recipes.py`. |

Read [docs/architecture/OVERVIEW.md](docs/architecture/OVERVIEW.md) before changing runtime, scheduling, networking
or persistence. [docs/development/EXTENDING.md](docs/development/EXTENDING.md) maps common changes to the files
involved.

## Commands

Start with the smallest check that covers the change:

| Change | Command |
| --- | --- |
| Language (core) | `./gradlew :filascript-core:test --tests '*ParserTest'` (narrow with `--tests`), then `:filascript-core:test` |
| CLI | `./gradlew :filascript-cli:installDist`, then `filascript-cli/build/install/filascript-cli/bin/filascript-cli run examples/hello.fsig` |
| Fabric logic without a world | `./gradlew :filasignal-fabric:test` |
| Mod behavior in a world | `./gradlew :filasignal-fabric:runGameTest "-PfilaTestFilter=filasignal-gametest:*detector*"` (Fabric glob), then all GameTests |
| Host API or events changed | `FILASIGNAL_UPDATE_DOCS=1 ./gradlew :filasignal-fabric:runGameTest`, commit `web/src/data/generated/minecraft-api.json` |
| Documentation site | `cd web && bun install --frozen-lockfile && bun --bun run build` (builds and runs the link checker) |
| Everything automated | `./gradlew build` (core and Fabric tests plus all server GameTests) or `tools/verify/Verify-All.ps1 -Level full` |
| Performance-sensitive change | `tools/benchmarks/Run-Benchmarks.ps1 -Profile quick` (see [benchmarks](docs/engineering/BENCHMARKS.md)) |

Client GameTests (`-PfilaClientTests=true :filasignal-fabric:runClientGameTest`) open a game window; run them only
for client UI changes. GameTests always start from a fresh world. `-PfilaTestReport=<absolute path>` writes a JUnit
report; read its failures instead of whole logs.

## Invariants

- Core stays independent of Minecraft. The CLI and the mod depend on core, never the reverse.
- Scripts reach the game only through registered, typed host functions with capabilities. No reflection, files,
  network, processes, threads or raw Minecraft objects from scripts.
- All world access and program execution happen on the server thread, through the one scheduler
  (`ServerComputers`). Handlers never run synchronously from world callbacks; events are queued.
- Every request from a client is validated on the server: owner or trust, device identity, distance, rate limits.
- Execution is bounded: 10,000 operations and 32 frames per handler, 40,000 operations per tick, data and collection
  limits, bounded queues. Do not raise a limit to fix a test or a benchmark.
- Never load chunks on behalf of a script; check `LoadedChunks` first. Devices in unloaded chunks are unavailable.
- Saved formats are versioned (`ControllerData`, global snapshots, drive records). Do not change them without a
  version bump, migration and a test that loads the old format.
- Expected failures return `(nil, error)` with a stable error code; faults are for programming errors.

## Conventions

- Java 25, packages under `dev.wiktorfila.filasignal`. Follow [engineering standards](docs/engineering/STANDARDS.md):
  focused classes, Javadoc on contracts and thread ownership, no speculative abstractions.
- Diagnostics carry precise source spans; grammar (`docs/language/GRAMMAR-0.1.ebnf`), spec and parser change together.
- A behavior change needs tests, the affected public docs in `web/src/content/docs/`, and an ADR for lasting
  decisions. Documentation examples are executed by tests, so keep them runnable.
- Do not add dependencies, reformat unrelated code or rename public APIs without a reason stated in the change.

## Working efficiently

1. Find the subsystem and read its code and existing tests first.
2. Make the focused change and add a regression test.
3. Run the narrowest command above; widen only when it passes and the change warrants it. Do not rerun unchanged
   full suites or launch the game client without a reason.
4. Update affected docs and the API catalog.
5. Report exactly what ran and what was not verified. Never weaken a test to make it pass.

A change is done when it builds, its tests pass, the docs match, module boundaries hold and anything unverified is
stated.
