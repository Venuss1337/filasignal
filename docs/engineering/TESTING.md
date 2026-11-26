# Test strategy and quality gates

## Layers

1. **Lexer/parser tests (JUnit):** tokens/spans, operator precedence, multiline syntax, errors/recovery, fuzzed invalid inputs.
2. **Resolver/semantic tests:** implicit declarations, globals, shadowing, const, arity, type annotations.
3. **Interpreter tests:** values, strict bools, collections, class instances, returns, recursion, diagnostics.
4. **Sandbox tests:** operation/memory/call/event limits, malicious scripts, expensive natives, overflow.
5. **Conformance suite:** source programs + expected parse/run/error results, reused by any future browser interpreter.
6. **CLI + mock host tests:** deterministic ticks, fake players/blocks/inventories, permissions and events without Minecraft.
7. **Fabric GameTests:** redstone, inventory, chunk reload, persistence, multi-controller feedback and world APIs.
8. **Dedicated server smoke:** no client-only classloading and verified headless server behavior.
9. **Multiplayer/security:** permissions, malformed packets, claim integration, untrusted edits.
10. **Performance:** active controller scaling, world scans, serialization, GC, tick impact and scheduler fairness.

## Acceptance evidence

For every change, record the commands actually run, relevant environment and pass/fail outputs. No green checkmarks based on anticipated results. CI should run core tests without Minecraft where possible and separate slow GameTests from fast unit tests. Match generated Gradle tasks to real Gradle project names.

## Verification entry points

| Command | What it does | Results |
| --- | --- | --- |
| `.\tools\verify\Verify-All.ps1` | Core tests, Fabric unit tests, build, CLI distribution, package check | `verification-results/verify-<timestamp>/SUMMARY.md` |
| `.\tools\verify\Verify-All.ps1 -Level full` | The above plus every server GameTest and the documentation site | same |
| `... -Client`, `... -CleanServer` | Adds the client GameTests, or the packaged dedicated-server smoke test | same |
| `... -Only gametests`, `... -From package` | Reruns selected steps | same |
| `.\tools\verify\Check-Package.ps1` | Mod JAR contents and metadata, installed CLI | `verification-results/package-<timestamp>/` |
| `.\tools\verify\Start-CleanServer.ps1` | A dedicated server with only the packaged JAR and Fabric API, in a new folder | that folder's `SUMMARY.md` |
| `.\tools\verify\Repeat-GameTests.ps1` | Repeats the server GameTests to catch intermittent failures | `verification-results/gametest-repeat-<timestamp>/` |
| `.\tools\benchmarks\Run-Benchmarks.ps1` | Performance; see [benchmarks](BENCHMARKS.md) | `benchmark-results/<timestamp>-<profile>/` |

`gradlew build` already runs the server GameTests through `check`. CI (`.github/workflows/build.yml`) runs `build`
and the installed CLI on every pull request. The documentation workflow builds the site. The manual checks are in
[release acceptance](../RELEASE-ACCEPTANCE.md).

GameTest selection and diagnostics, as Gradle properties on `runGameTest`:

- `-PfilaTestFilter='filasignal-gametest:*chunk_unload*'` runs matching tests (Fabric's glob syntax).
- `-PfilaTestReport=<absolute path>` writes a JUnit XML report. A relative path crashes the test server.
- `-PfilaTrace=true` logs program activation, detachment and unloading (`[lifecycle]` lines).

## Intermittent failures

Server GameTests share one world and run concurrently, so a test must not depend on state another test can change.
One such defect was found and fixed before 1.0.0: `drivePruningPreviewsBeforeDeleting` pruned every unmounted drive record
in the world, including records that a concurrent chunk-unload test had just saved, which then failed with
"Unloading lost stored data". The test now prunes only records older than its own.

`computerChunkUnloads` failed once during development (the computer stayed active although its chunk was no longer
FULL). It has not recurred in later traced full runs, so its cause is not established. Its failure message now
includes the chunk's state, forced status, pending chunk tasks and Minecraft's chunk debug data, which name the
ticket holding the chunk. To reproduce, run:

```powershell
.\tools\verify\Repeat-GameTests.ps1 -Runs 20 -Trace
.\tools\verify\Repeat-GameTests.ps1 -Runs 50 -Filter 'filasignal-gametest:*chunk_unload*'
```

Then attach the summary and the failing run's log.

## CI bootstrap targets

- Gradle multi-project wrapper verified and committed with expected checksums.
- JDK 25 runner and `:filascript-core:test`.
- Build CLI and Fabric mod; cache appropriately, don't cache secrets.
- Smoke test CLI Java entry point (not fake SignalScript interpreter claim).
- Release build provenance and artifacts when actual releases exist.
