# Architecture overview

FilaScript is a standalone programming language. FilaSignal is a Minecraft Fabric mod that embeds it and supplies
programmable blocks and Minecraft-specific host APIs. One repository holds both, so language, mod, documentation and
tests change together.

## Modules and dependency direction

```text
filascript-cli    ─┐
                   ├──> filascript-core   (pure Java, no Minecraft or Fabric)
filasignal-fabric ─┘

web/   Astro + Starlight site; reads examples, recipes and the generated API catalog
```

| Module | Contents |
| --- | --- |
| `filascript-core` | `source` (strict UTF-8, half-open spans), `lexer`, `parser` and `ast`, `analysis` (resolver), `runtime` (`execution`, `value`, `event`, `persistence`, `debug`), `host` (typed host registry), `diagnostic`. |
| `filascript-cli` | `run` and `ast` commands. |
| `filasignal-fabric` | `computer` (scheduler, program hosts, saving, editor and debugger protocol), `peripheral` (network graph, hardware APIs, events, permissions, commands), `drive` (drive items and records), `device` (kinds and capabilities); `src/client` for screens, the editor and renderers. |

The mod's client source set is separate so a dedicated server never loads client classes.

## From source to execution

`.fsig` source → tokens with spans → AST → resolution and diagnostics → validated program → one runtime per program
with persistent globals → queued events → interpreter under budgets → typed host functions with capability checks →
Minecraft operations on the server thread.

Programs are parsed and checked when they are saved or activated, never per tick. An invalid edit is rejected and the
previous saved program and data stay intact. See ADRs [0004](../decisions/0004-filascript-parser-and-ast.md) and
[0005](../decisions/0005-scalar-resolution-and-execution.md).

## Language runtime

- Values are FilaScript values, never Java objects exposed to scripts. Host objects carry scoped identities and an
  explicit member allowlist ([host objects](../engineering/HOST-OBJECTS.md)).
- Expected failures are returned as `(nil, error)` with a stable code; programming errors are located faults.
- Budgets: each handler gets 10,000 operations, 32 call frames, 262,144 data units and limited output; collections
  hold at most 4,096 entries. Expensive operations (string building, copies, JSON, host calls) are charged by size.
- `global` variables persist per program in a versioned, checksummed snapshot (format 1) that preserves shared
  references and cycles. Incompatible saved data fails activation instead of being reset
  ([persistence](../engineering/PERSISTENCE.md)).
- The debugger pauses at statement boundaries. A debugged handler runs as a coroutine of the scheduling thread; host
  calls still happen on that thread ([ADR 0013](../decisions/0013-runtime-debugging.md)).

## Scheduling and threads

There is one scheduler per server, `ServerComputers`, on the server thread. Every program, on a computer or on a
device with a drive, is a `ProgramHost`
([ADR 0015](../decisions/0015-autonomous-devices-and-drives.md)).

- Activation is queued: at most 16 programs and 64K characters of source per tick (always at least one), at most 512
  active and 512 pending programs.
- Each tick shares 40,000 operations fairly between programs with pending work. A controller holds at most 32 queued
  deliveries; surplus events are dropped and counted.
- World callbacks never run handlers synchronously: they queue events for a later turn, which prevents feedback
  loops between programs.
- No thread is created per program. Nothing script-driven runs off the server thread.

## Devices and networks

Blocks share one block and block-entity model composed from capabilities (network, relay, hardware, execution)
([ADR 0014](../decisions/0014-unified-hardware.md)).

- `NetworkDirectory` is the only owner of persistent membership, aliases and links (at most 1,024 endpoints).
  Minecraft objects never enter the graph. A computer reaches a device through linked repeaters (links up to 32 blocks by default) or
  paired wireless bridges ([device network](../minecraft/DEVICE-NETWORK.md)).
- A device is usable only when it is a member of the program's network, of the expected kind, reachable through
  loaded relays and its chunk is loaded. Scripts never load chunks: `LoadedChunks` checks first.
- Hardware objects come from declarative specs (`HardwareHosts`), one resolution path for factories and methods.
- Ownership: the network root's owner, trusted players and server administrators. Every client request is checked
  for permission, matching device identity, distance and rate limits
  ([permissions](../minecraft/PERMISSIONS-AND-THREADING.md),
  [security review](../engineering/SECURITY-AND-RUNTIME.md#security-review-for-100)).

## Events

`HardwareEvents` is the single catalog of native events and constants; the editor's completion and the documentation
read it too. `EventHub` routes events
([ADR 0016](../decisions/0016-native-events-and-device-references.md),
[ADR 0017](../decisions/0017-advanced-hardware.md)).

- `on gate.entityEnter(entity)` is resolved statically against host-declared event types. Device names are resolved
  against the network periodically and on network changes.
- Hardware nobody listens to does no work. `DeviceObservers` samples observed devices on fixed intervals, at most 32
  per tick; detectors share 16 fresh entity scans per tick (`detector.busy` beyond that).
- Custom events (`events.emit`, `events.send`) carry a server-filled sender and are rate- and size-limited.

## Drives and storage

- A drive item carries an identity. `DriveStore` keeps drive records in 16 saved-data shards and a mount table so one
  identity runs in at most one block at a time. `/filasignal drives prune` deletes unmounted records on request.
- A device program gets its drive's storage; a Storage Server is shared by its network (256 KiB, 1,024 keys) and its
  data is deleted with the block.
- A computer's program, enabled state and global snapshot are saved in the block entity as a versioned record
  (`ControllerData`) and published only after encoding succeeds
  ([computer persistence](../minecraft/COMPUTER-PERSISTENCE.md)).

## Chunk lifecycle

Fabric's block-entity load and unload events attach and detach programs. Minecraft can drop a chunk below full status
and promote it again with the same block entities; the runtime then detaches and re-activates the same program from
its saved state. Detaching checkpoints globals. Lifecycle tracing (`-PfilaTrace=true` on GameTests) logs these
transitions.

## Editor and client

The editor and dashboard use LibGui and native text input; completion and highlighting run locally. The server owns
source, revisions and device aliases; saves use revision tokens to detect conflicts
([ADR 0012](../decisions/0012-native-editor-and-revisions.md), [program editor](../minecraft/PROGRAM-EDITOR.md)).
Display text and block states are synced through normal block updates.

## Documentation sources

Language semantics: the [specification](../language/SPEC-0.1-DRAFT.md) and [grammar](../language/GRAMMAR-0.1.ebnf).
Public guides and references live in `web/src/content/docs/`. The Minecraft API reference is rendered from
`web/src/data/generated/minecraft-api.json`, exported from the real registries by `DocumentationGameTests`, which
fails when the file is stale. Documentation examples run in tests
([ADR 0018](../decisions/0018-documentation-site-visuals-and-survival.md)).
