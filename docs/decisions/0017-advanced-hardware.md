# ADR 0017 — Storage servers, access controllers, control panels and wireless bridges

Status: accepted for Milestone 15.

## Context

Milestone 15 adds four devices that make automation useful without programming and give programs shared state, physical input and long links. Each must fit the capability model (ADR 0014), the drive model (ADR 0015) and the event hub (ADR 0016) without creating a new trust path.

## Decision

**Storage Server** (`NETWORK`, `HARDWARE`).
- **Storage.** A `NamedStorage` of 256 KiB in up to 1,024 keys. Each value is still at most 64 KiB, and the value rules and error codes are the drive storage rules.
- **Persistence.** The data is saved with the block entity and belongs to the block. Breaking the block deletes it, and the form warns about this. A creative copy of the block, with its data, is an independent copy, like any other block data. Undecodable saved data is kept byte-for-byte and reported as `storage.damaged`.
- **Access.** Computer programs on the network use `storageServers.from` or `storageServers.named` with read, write, append, delete, has, keys, usage and clear. Device programs on the same connected network may read, and they may write unless the owner set the server to read-only. Device programs cannot clear the whole server.
- **No new programs.** The server never runs a program itself.

**Access Controller** (`NETWORK`, `HARDWARE`, `EXECUTION`).
- **Without a program.** Using the block asks to enter, and sneaking while using it opens its form. Owners, trusted players and players on its list (at most 32, by UUID, managed by the owner) are let in. A public controller lets everyone in.
- **Defaults.** It starts restricted. A granted attempt powers the configured side, or all sides, for 1–30 seconds (default 3).
- **Feedback and memory.** Every attempt gives sound and on-screen feedback and is remembered (the last 10). It raises `accessAttempted` and then `accessGranted` or `accessDenied`.
- **Programs.** A drive program or a computer can open, close and read the list and recent attempts, but cannot edit the list or relax the policy. The block controls only its own outputs and never touches Minecraft permissions or block protection.

**Control Panel** (`NETWORK`, `HARDWARE`, `EXECUTION`).
- **Buttons.** Four buttons with editable labels (1–24 characters) and stable identities `Button.ONE` to `Button.FOUR`.
- **Who may press.** It starts restricted to the owner and trusted players. A public panel lets anyone press. Visitors see only the buttons and cannot change anything.
- **Events.** Pressing gives click feedback and raises `buttonPressed(button)` and the button's own event, `buttonOne` to `buttonFour`.

**Wireless Network Bridge** (`NETWORK`, `RELAY`).
- **Pairing.** A bridge pairs with exactly one other bridge up to 256 blocks away in the same dimension. Pairing is a normal topology link with a longer range, so reachability, loaded-chunk rules and routing stay the existing graph rules. Loops are harmless because reachability is a set search.
- **Who may pair.** Pairing needs the owner of both bridges, and an administrator may do it too. It never joins two computers' networks: an unattached bridge joins the other bridge's network. The form lists the player's own loaded, unpaired bridges in range, nearest first, and shows connection status and distance.
- **Unloading.** Unpairing works even when the partner is unloaded. A bridge never loads or keeps chunks loaded.

**Milestone 14 review.**
- **Revocation.** Started device programs record the starter's UUID and stop when that player loses access. See ADR 0016.
- **Pruning.** `/filasignal drives prune DAYS` now only reports what it would delete, with the risk spelled out. Deleting needs `/filasignal drives prune DAYS confirm`. Installing a drive now refreshes its last-used time. Age still cannot prove that a drive is abandoned, because the item may sit in an unloaded chest or an offline player's inventory. Scanning the world for drive items is deliberately not done.

## Consequences

- A player can set up a guarded door or a button-operated alarm without writing code.
- Programs gain shared data and physical input without new authority, because every new action is bounded by the existing network access list.
- Storage server data is lost with its block, and this is documented in the form and the reference.
- Bridges reuse the topology graph, so a large network still recalculates reachability only when its topology changes.
