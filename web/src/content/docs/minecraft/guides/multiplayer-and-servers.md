---
title: Multiplayer and servers
description: Running FilaSignal on a dedicated server - access rules, limits, scheduling, persistence, commands, drive pruning and backups.
sidebar:
  order: 6
---

This page is for server operators and players who want the details. You do not need it to play.

## Installation

Install Fabric Loader, Fabric API and FilaSignal on the server, and the same FilaSignal build on every client. See
[installation](/minecraft/getting-started/installation/).

## Access rules

- The player who places a block owns it. Devices follow the access list of the computer they are linked to.
- The owner may trust up to 16 players per network. Trusted players can edit programs, configure and link devices and
  install drives. Only the owner can approve inventory faces, change Access Controller lists, trust players, apply
  programs with fresh globals, clear stored data and break devices.
- Server operators (permission level 2) count as owners for configuration. They do not bypass inventory protection.
- Revoking trust stops future edits. A drive program started by a player who loses access stops within five seconds
  and does not restart on its own. Computer programs keep running with the owner's access.
- Every request from a client is checked on the server: the player's access, distance, the block's current state and
  the program revision. A client cannot forge results.
- FilaSignal does not add a land-claim or protection system. Mods can veto inventory automation through
  `InventoryProtection.CHECK`; test your protection mod before relying on it.

## Resource limits

All programs share one scheduler on the server thread.

| Limit | Value |
| --- | --- |
| Work per handler | 10,000 operations, 32 nested calls |
| Work per server tick, all programs | 40,000 operations |
| Output per handler | 8 lines, 1,024 characters |
| Queued events per program | 32 (more are dropped and counted) |
| Running programs per server | 512 |
| Program starts per tick | 16, and at most 64K characters of source |
| Detector scans | 16 fresh scans per tick, 256 entities and 16 blocks radius each |
| Sampled devices | 32 per tick |
| Sounding alarms | 64 at once |
| Custom events | 16 sends per program per tick, 64 receivers each |
| Devices per network | 256; 1,024 endpoints per world |
| Drive records | 4,096 per world |

Each program runs at most one handler per tick. When the tick budget is used up, the remaining programs wait for the
next tick, in rotating order, so one busy program cannot starve the others. A handler that exceeds its own budget stops
with `EXECUTION_BUDGET`; the program keeps running.

Devices nobody listens to cost nothing. Event sampling only happens for devices with listeners.

## Persistence

| Data | Saved in |
| --- | --- |
| Computer program and globals | the computer's block data, with the chunk |
| Drive programs, globals and stored data | world files `filasignal:drives_0` to `drives_f` |
| Network topology, names, links, access lists, inventory approvals | world file `filasignal:device_network` |
| Storage Server data, Access Controller lists, panel labels, display text | the block's data, with the chunk |

Everything is written with normal world saves. A clean shutdown saves it; a crash loses changes since the last save.
Chunk data and world files are written separately, so after a crash a drive record can be one save behind its device.
Undecodable records are kept unchanged and reported as damaged rather than deleted.

Outputs are not persisted: redstone outputs turn off and alarms stop when a chunk unloads, and they are not restored on
load. Event queues and handlers are rebuilt when programs start.

### Backups and versions

Back up the whole world folder, including the `data` folder, with the server stopped or after `save-all flush`. Newer
FilaSignal versions read data saved by older ones. Older versions refuse newer network records instead of guessing,
and keep them unchanged.

## Commands

All commands start with `/filasignal` and take block coordinates `X Y Z`. Operators (permission level 2) may use every
command on every block. Device commands need the block's area to be loaded.

| Command | Who | Does |
| --- | --- | --- |
| `status X Y Z` | owner, trusted | Shows a device's identity, network, links and settings. |
| `alias X Y Z NAME` | owner, trusted | Names a device. `-` removes the name. |
| `trust X Y Z PLAYER`, `untrust X Y Z PLAYER` | owner | Changes the network's trusted players, at the computer. |
| `claim X Y Z PLAYER` | operator | Gives an ownerless block from an old world an owner. Claim the computer first. |
| `range BLOCKS` | operator | Sets the link range for the world, 1 to 64. Refuses values that would break existing links. |
| `install X Y Z` | owner, trusted | Installs the program from the unsigned Book and Quill in your hand. |
| `program X Y Z SOURCE`, `program-reset X Y Z SOURCE` | owner, trusted; reset: owner | Installs a short program inline, optionally discarding saved globals. |
| `port X Y Z FACE MODE NAME` | owner, trusted | Configures a Network Interface redstone port. |
| `sensor X Y Z RANGE PERIOD` | owner, trusted | Configures an older Sensor. |
| `inventory X Y Z FACE NAME` | owner | Approves an inventory face on an interface or Inventory Module. |
| `drives` | operator | Counts drive records. |
| `drives prune DAYS` | operator | Previews how many drive records would be deleted. |
| `drives prune DAYS confirm` | operator | Deletes them. |
| `programs` | operator | Lists the busiest running programs by peak handler work. |

### Drive pruning

`/filasignal drives prune DAYS` only reports how many drive records are not installed in any block and have not been used
for that many days. Adding `confirm` deletes them.

:::danger
Pruning deletes drive data permanently, including drives that sit in chests or in offline players' inventories. The
server cannot see those items, so age is the only clue. Warn players and back up the world before confirming.
:::

## Known multiplayer limitations

- Access Controller lists accept online players only. Offline players can be removed but not added.
- Device forms do not detect two players editing the same settings at once; the last change wins. Program edits do
  detect it and keep the second player's draft.
- Up to four players can debug one computer, and up to eight computers per server can be debugged at once.
- Drive data does not move between worlds or servers.
- Multiplayer playtesting with many clients and production performance measurements are still pending. See the
  project's acceptance records.

## Performance

FilaSignal runs programs on the server thread within the budgets above, so a busy network adds bounded work to each
tick. `/filasignal programs` shows which programs use the most. Large detector radii with many entities and many
listened devices cost the most; keep radii small and listen only to what you need.
