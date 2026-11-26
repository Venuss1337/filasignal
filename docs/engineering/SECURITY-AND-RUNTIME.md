# Runtime sandbox and performance rules

**Design requirements, not measured performance guarantees.** A Java interpreter inside Minecraft can still lock a server if it performs unbounded work. The runtime must be engineered against that risk.

## Resource categories

| Budget | Purpose | Failure behavior |
|---|---|---|
| Handler operation budget | Stop infinite loops and runaway recursion | Abort current handler, diagnostic |
| Per-controller memory/accounting | Limit retained script values, lists, strings and objects | Reject allocations/abort handler |
| Global per-tick work budget | Prevent a large number of controllers multiplying cost | Defer eligible events fairly; avoid starvation |
| Event queue length | Prevent event storms | Deterministic bounded overflow policy |
| Max call depth | Prevent recursive stack exhaustion | Abort handler with source trace |
| Max source/AST depth | Prevent parser denial-of-service | Compile diagnostic |
| Host call work/action quota | Bound scanning, transfers and mutations | Deny or bounded partial result with explicit error |
| Serialization size/depth | Avoid save crashes/corruption | Reject/limit with diagnostic; preserve last valid data |
| Logging rate | Prevent massive console/log spam | Rate-limit and summarize suppression |

**Starting proposals only:** ~10,000 logical operations per handler, 32 call depth, 64 queued events, 256 KiB controller-owned script data. Must be measured in real Minecraft and tuned. Counting AST nodes is not enough: string concatenation, list copies, deep equality, serialization and world scans cost much more.

## Work scheduler

Interpreters don't spontaneously run on background threads. Instead, active controllers have bounded pending events. Per server tick, a scheduler services eligible controllers fairly within a global work quota. Script execution/host calls are charged. Expired or over-limit handlers produce diagnostics; follow-up redstone changes are queued for later dispatch to avoid synchronous loops.

Wall-clock/time limits are supplementary: operation budgets cannot interrupt arbitrary blocking Java code. All host functions must be bounded and nonblocking; code review and tests enforce this.

## Memory and GC

The Java GC reclaims unreachable Java objects, including interpreter values, but is **not** a secure per-controller heap limiter. Maintain script-owned logical allocation/retained-size accounting and cap large operations before allocating. Beware shared objects, cycles and retained objects after variables are overwritten. Never assume Java class allocation is cheap or safe without bounds.

## Persistence

Persistent `global` state is serialized with schema/version metadata, size/depth limits and compatibility handling. Missing/corrupt state must not silently reset valuable data. Save/load tests include data migration and world restart; class instance graphs with cycles require explicit treatment.

## Host security

No Java reflection, filesystem, network, process execution or arbitrary class binding from FilaScript. Native Minecraft objects remain private to adapters. Require capabilities, owner context and claim/protection checks on sensitive APIs. A programmable controller isn't trusted merely because a player placed it.

## Validation scenarios

- `while true {}`; deep recursive functions; massive list append; exponential string concatenation; deep nested objects; cyclic references.
- Timer event storms; mutually triggering controllers; hot chunk unload/reload; player disconnect during handler.
- World scans of maximum radius; invalid chunk positions; denial of permissions; malformed packet/script data.
- Hundreds of idle vs active controllers under test, measure tick/GC impact, benchmark real host APIs.

## Security review for 1.0.0

A review of the release candidate by reading the code and adding targeted tests. It is not an audit or a certification.

**Client requests.** Every serverbound payload (editor, debugger, device forms, dashboard) is decoded with explicit
size bounds, admitted at most once per 5 ticks per player and 8 times per server tick, and then checked on the server
thread for an owned or trusted device, the request's device identity matching the block at that position, and distance.
Nothing in a request is trusted beyond its target and requested change.

**Fixed: visitors learned private device details.** A visitor to a public Control Panel received the same form data as
the owner: the access summary (owner and trusted names) and the drive's program state, errors and starter. Visitors
now receive only the buttons and status, as the hardware documentation already promised.
`SecurityGameTests.strangersAndVisitorsCannotChangeOrInspectDevices` checks this and that strangers cannot rename,
retune, trust, view or clear storage, start, edit, eject or relabel drives, change Access Controller settings, open the
computer or a remote drive program, forge a device identity or act from 40 blocks away.

**Trust model, by design:**

- Ownership and trust are per network root. Trusted players can edit programs and settings. Only the owner can clear a
  Storage Server or change trust; server administrators can do everything the owner can.
- Physical access is not authorization. Breaking a block is governed by Minecraft and server protection plugins; a
  player who can break a device can take its drive. Storage Server data is deleted with the block, and the server
  warns before that.
- Display Module text and block states (computer status, open Access Controller, ringing alarm) are visible to every
  nearby player. Programs should not write secrets to displays.
- A Storage Server is shared by every program on its network. Device programs can write to it unless the owner sets
  it to read-only for them. Data from it is as trustworthy as the network's programs.
- Custom events carry a sender filled in by the server (`sender.kind`, `sender.name`), which a program cannot forge.

**Resource exhaustion.** Reviewed: per-handler (10,000 operations, 32 frames) and per-tick (40,000 operations)
budgets, data units, 4,096-element collections, 64 KiB JSON, event queues, 16 custom events per tick, alarms, 16 fresh
entity scans per tick, program activation (16 programs and 64K source characters per tick) and rate-limited fault
logging. The `saturated` benchmark (every computer looping forever on every tick) shows the per-tick budget holding at
40,000 operations, about 0.8 ms on the reference machine; see [benchmarks](BENCHMARKS.md). No limit was relaxed.

**Sandbox.** FilaScript reaches the game only through registered host objects. Core has no Minecraft imports and no
filesystem, network, process, reflection or thread access from scripts. The only file read in the language tools is
the CLI loading the source file it was given.

**Not covered:** fuzzing of network payloads, protection-plugin integration (no claims API is integrated), and
multiplayer abuse by real players. Section 6 of the [release acceptance](../RELEASE-ACCEPTANCE.md) covers the
two-player checks.
