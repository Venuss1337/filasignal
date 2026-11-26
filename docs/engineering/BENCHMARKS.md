# Benchmarks

FilaSignal has two automated benchmarks and a manual client checklist. They are meant to be run by a person, without
an AI agent watching: every run writes a self-contained result folder.

```powershell
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile quick      # language + idle and small scenarios
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile normal     # + advanced and large
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile large      # large and multiplayer, 4 GB heap
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile stress     # multiplayer, stress and saturated, 6 GB heap
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile extended   # every regular scenario, 6,000 measured ticks each
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile client     # editor timings from the client GameTest (opens a window)
```

`-WarmupTicks`, `-MeasureTicks` and `-Iterations` override a profile's lengths. Each profile takes one to three
minutes, mostly Gradle and Minecraft startup. Set `JAVA_HOME` to a JDK 25 first.

## Results

Each run creates `benchmark-results/<timestamp>-<profile>/` (ignored by Git):

| File | Contents |
| --- | --- |
| `SUMMARY.md` | Environment (date, commit and whether the tree was dirty, CPU, memory, OS, Java, Minecraft, Loader and Fabric API versions, heap limit), warm-up and measurement lengths, JVM arguments and every table. |
| `results.csv` | One row per part, scenario and metric, for spreadsheets. |
| `server-results.json`, `language-results.json` | Raw numbers. |
| `*-console.log`, `server-report.xml` | Gradle and server output, for failures. |

## Server benchmark

`ServerBenchmark` is a GameTest that does nothing unless the runner passes a profile. It builds whole networks 4,096
blocks from the other tests, in forced chunks 150 blocks up, in the disposable GameTest world
(`filasignal-fabric/build/run/gameTest`, deleted before every run). It never opens another world.

Each network is a computer, a repeater and up to 15 devices of eight kinds. The computer runs a program that reacts to
a detector, writes to a display and a Storage Server, toggles redstone, reads the weather, lists devices and receives
custom events. Drive programs on detectors, displays, weather stations, redstone controllers, sensors, alarms and
control panels use device events, timers, drive storage and custom events. An armor stand per network moves in and
out of detector range every 40 ticks.

| Scenario | Devices | Programs | Notes |
| --- | ---: | ---: | --- |
| idle | 0 | 0 | The mod is installed and nothing is placed. |
| small | 10 | 3 | One network, two drive programs. |
| advanced | 30 | 10 | |
| large | 100 | 30 | |
| multiplayer | 300 | 100 | A busy server. |
| stress | 900 | 500 | 15 devices per network, 440 drive programs. |
| saturated | 100 | 10 | Every computer loops forever on every tick. Measures the budget cap. |

After placing a scenario, the benchmark waits for every device to attach and every program to start (*Start* is the
ticks this took), warms up, then records every tick between Fabric's tick start and end events. It removes everything
and, 40 ticks later, checks that no program or device of the scenario is still loaded.

The GameTest server does not wait between ticks: a run measures thousands of ticks in seconds. MSPT is the real work
per tick. *TPS (from MSPT)* is `min(20, 1000 / MSPT)`, what the same work allows on a normal server. *FilaSignal* is
the program scheduler plus the device runtime, timed inside the tick. *Faults* are handler failures programs reported,
and *Dropped* are events refused by full queues.

Not measured automatically: a server without the mod installed (compare `idle` with a vanilla server by hand), real
terrain, players and farms, and a paced server's idle time between ticks.

### Reference results

Windows 11, Intel Core i5-12400F (6 cores, 12 threads), 16 GB, Temurin 25.0.5, Minecraft 26.3, Fabric Loader 0.20.1,
Fabric API 0.166.2+26.3, with FilaSignal 1.0.0 code. The `stress` profile measured 2,400 ticks per scenario after 300
warm-up ticks with a 6 GB heap. The `extended` profile's 6,000-tick runs show the same pattern with lower means.

| Scenario | Mean MSPT | P95 | P99 | FilaSignal mean | FilaSignal P95 | Operations / tick | Faults | Dropped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| idle | 0.12 ms | 0.22 | 0.49 | 0.004 ms | 0.010 | 0 | 0 | 0 |
| multiplayer (300 / 100) | 0.49 ms | 1.29 | 2.62 | 0.263 ms | 0.852 | 5,631 | 0 | 0 |
| stress (900 / 500) | 0.77 ms | 1.63 | 2.32 | 0.546 ms | 1.247 | 16,555 | 2,162 | 0 |
| saturated (100 / 10) | 0.90 ms | 1.46 | 2.17 | 0.772 ms | 1.255 | 40,000 | 10,804 | 15,890 |

In the `normal` and `large` profiles, small, advanced and large used 0.06 to 0.13 ms of FilaSignal time per tick on
average. Every scenario left 0 programs and 0 devices loaded after removal. Single maximum ticks of 10 to 80 ms
appear in some scenarios, including `idle`, where no program runs. The benchmark does not attribute them; chunk
loading and garbage collection are the likely sources.

What this shows:

- The cost grows with the work programs do, not with the number of placed devices. Idle devices cost nothing
  measurable.
- 500 programs used about 0.55 ms of a 50 ms tick on average.
- The global budget holds. In `saturated`, every computer tries to run forever on every tick. The scheduler stops at
  exactly 40,000 operations per tick, about 0.8 ms here. The runaway handlers fault with `EXECUTION_BUDGET`, the
  programs keep running, and surplus timer events are dropped rather than queued without bound.
- The `stress` faults are the entity scan limit working. Hundreds of detector programs start on the same tick, so
  their 40-tick timers line up, and the 16 fresh scans per tick refuse the rest with `detector.busy`. Programs that
  need a census on every timer tick should stagger their timers or handle the error. The log shows the first faults
  and rate-limits the rest.

No optimization was made from these results: nothing approached the tick budget, and no limit was relaxed.

## Language benchmark

`LanguageBenchmark` (core, JUnit tag `benchmark`, excluded from `test`) parses and resolves a 2,720-line program, then
runs six workloads: arithmetic loop, function calls, recursion, string building, list operations, maps and objects.
It reports wall time and nanoseconds per counted operation. It uses the default call depth and data limits, with a
10-million-operation budget so each workload completes in one call; the in-game handler limit is not changed.

```powershell
.\gradlew.bat :filascript-core:languageBenchmark -PfilaBenchIterations=200   # results in filascript-core/build/benchmark
```

On the reference machine, parsing and resolving took 1 to 5 ms. Interpreted operations cost about 13 to 19 ns, except
in list operations, which charge operations by data size and work out cheaper per counted operation.

## Client

The `client` profile runs the client GameTests and collects the editor's own timings: highlighting analysis of 512,
4,096 and 16,384-character drafts (8-run averages) and the insertion and resize of a maximum-size draft (single samples).
It opens a game window and needs a desktop session.

World rendering is measured by hand:

1. Start a client (`.\gradlew.bat :filasignal-fabric:runClient`) and create a new creative superflat world.
2. Press F3 and note the FPS with nothing placed.
3. Place 50 Display Modules with text in view, plus a computer of each state. Note FPS again from the same spot.
4. Repeat at 10, 32 and 64 blocks away, and record your GPU, resolution, render distance and graphics settings.

Record results in the release acceptance notes (see [release acceptance](../RELEASE-ACCEPTANCE.md)).
