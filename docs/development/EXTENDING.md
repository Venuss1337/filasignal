# Extending FilaSignal

Where common changes go, which existing code to copy from, and what to verify. Read the
[architecture overview](../architecture/OVERVIEW.md) first. Paths are relative to the repository root;
`fabric/` means `filasignal-fabric/src/main/java/dev/wiktorfila/filasignal/fabric/` and `core/` means
`filascript-core/src/main/java/dev/wiktorfila/filasignal/core/`.

## Language feature

1. Specify it: `docs/language/SPEC-0.1-DRAFT.md`, the grammar `docs/language/GRAMMAR-0.1.ebnf`, and an ADR in
   `docs/decisions/` for a lasting decision.
2. Implement through the pipeline: tokens in `core/lexer`, syntax in `core/parser` and `core/ast`, name rules in
   `core/analysis/Resolver.java`, behavior in `core/runtime/execution/Interpreter.java`. Built-in functions are the
   `Builtin` cases in the interpreter.
3. Charge the work: count operations, and data units for anything proportional to input size. Respect the collection
   and call-depth limits.
4. Test in `filascript-core/src/test`: parser tests with exact spans and recovery, resolver diagnostics, interpreter
   behavior, and a `SafetyTest`-style case where a hostile program hits a limit.
5. Document it in `web/src/content/docs/language/` with a runnable example; `DocumentationExamplesTest` executes it.
6. If saved globals can hold the new value kind, see [persistence](#persistence) below.

## Host API function on existing hardware

Hardware objects are declared as `Spec`s. Copy a method from `fabric/peripheral/SensingApis.java` (detectors, weather,
environment), `ControlApis.java` (display, alarm), `AdvancedApis.java` (storage server, access controller, control
panel, bridge) or `InventoryHosts.java`. A `method(...)` takes the name, return type, documentation, required
capability, operation cost, an example, its expected errors, the implementation and the parameter shapes.

- Return expected failures as `HostFailure` with a stable `kind.reason` code; they reach scripts as `(nil, error)`.
- Run only on the server thread, touch only loaded chunks, and bound the work (cap scans and result sizes).
- A new capability goes in `DeviceHosts.CAPABILITIES`; decide whether device programs receive it
  ([permissions](../minecraft/PERMISSIONS-AND-THREADING.md)).
- Regenerate the API catalog (below) and describe the function on the device's page in
  `web/src/content/docs/minecraft/`.
- Test it in a GameTest (`filasignal-fabric/src/gametest/.../peripheral/HardwareGameTests.java` has examples),
  including a refused or unavailable case.

## New hardware kind

A new kind touches: `fabric/device/DeviceNetwork.Kind` (capabilities), block registration in
`fabric/peripheral/PeripheralBlocks.java`, its `Spec` and API class, interaction and form in `DeviceConfigNetwork`
and `DeviceReadings`, language strings in `assets/filasignal/lang/en_us.json`, textures, models and blockstates
(generated, see [resources](#minecraft-resources)), a recipe, documentation pages and GameTests. Follow how the
Access Controller or Control Panel was added in `AdvancedApis.java`. Record the design in an ADR.

## Native event or constant

Events and constants are declared once in `fabric/peripheral/HardwareEvents.java`; the resolver, editor completion
and documentation read that catalog. A sampled event (a change between observations) needs an observer in
`DeviceObservers.java`; a callback event raises from the block or runtime through `EventHub`. Events are queued,
never run synchronously. Add a GameTest in `NativeEventGameTests.java` that fires and receives the event, and that a
device nobody listens to is not observed.

## API catalog and generated reference

```sh
FILASIGNAL_UPDATE_DOCS=1 ./gradlew :filasignal-fabric:runGameTest
```

This rewrites `web/src/data/generated/minecraft-api.json` from the live registries. Commit it. Without the variable,
`DocumentationGameTests` fails when the file is stale, and checks every `fsig target="..."` example in the Minecraft
docs against its device's registry. Never edit the JSON by hand.

## Starter program

Add `examples/starter-<name>.fsig` with the header format of the existing starters (the target on its first lines).
The build packages starters into the mod; a GameTest checks each one against its target, and the package check
counts them.

## Tests

| Kind | Location | Run |
| --- | --- | --- |
| Language | `filascript-core/src/test/java` | `./gradlew :filascript-core:test --tests '*Name*'` |
| Mod logic without a world | `filasignal-fabric/src/test/java` | `./gradlew :filasignal-fabric:test` |
| Server GameTest | `filasignal-fabric/src/gametest/java`, class listed in `src/gametest/resources/fabric.mod.json` | `./gradlew :filasignal-fabric:runGameTest "-PfilaTestFilter=filasignal-gametest:*name*"` |
| Client GameTest | `IntegratedWorldTest`, `HardwareShowcaseTest` | `./gradlew :filasignal-fabric:runClientGameTest -PfilaClientTests=true` |

GameTests run concurrently in one world. Build far from the test origin when chunks are involved, release forced
chunks in `runBeforeTestEnd`, and never assume world-wide state (such as every drive record) belongs to your test.
Intermittent failures: `tools/verify/Repeat-GameTests.ps1` ([testing](../engineering/TESTING.md)).

## Benchmark scenario

Scenarios are the `CATALOG` in `filasignal-fabric/src/gametest/.../peripheral/ServerBenchmark.java` and the profiles
in its `scenarios(...)` method and in `tools/benchmarks/Run-Benchmarks.ps1`. Add a scenario there and describe it in
[benchmarks](../engineering/BENCHMARKS.md). Run `-Profile quick` while developing.

## Persistence

Saved data has explicit versions: the global snapshot format (`core/runtime/execution/GlobalSnapshot.java`,
[persistence](../engineering/PERSISTENCE.md)), the computer record `fabric/computer/ControllerData.java`, drive records
`fabric/drive/DriveRecord.java` and the network directory `fabric/peripheral/NetworkSave.java`. To change one:

1. Bump its version and keep reading the previous version (migrate on load).
2. Keep size limits, and never reset or overwrite data that fails to load.
3. Add a test that decodes data in the old format (see `ControllerDataTest`, `DriveRecordTest`, `PersistenceTest`).

## Minecraft resources

- Textures, block and item models, blockstates and item definitions: edit `tools/assets/generate.py` and run
  `python tools/assets/generate.py` (Python 3.10+, Pillow). Do not hand-edit generated files.
- Recipes, unlock advancements, the pickaxe tag and `web/src/data/recipe-tiers.json`: `python tools/recipes.py`.
- Names and tooltips: `filasignal-fabric/src/main/resources/assets/filasignal/lang/en_us.json`.
- `SurvivalGameTests` checks that every block and item crafts, mines and has its assets.
