# ADR 0009 — Fabric computer lifecycle and device boundaries

**Status:** Accepted for Milestone 6.

Run one core scheduler per Minecraft server, driven by Fabric's end-of-server-tick hook. Defer a block entity's attachment until a server tick because Fabric's load notification can precede fully loaded NBT. Use common-side lifecycle and level callbacks for both server types. The Fabric adapter injects an empty custom host registry so standalone simulated functions stay in the simulator. Core print, lifecycle events and timers remain available. The adapter needs no Mixin and creates no script threads.

Persist one versioned block-entity record containing the UUID, source and opaque FilaScript snapshot. A handler dispatch dirties the block. Capture the bounded snapshot on the server thread as vanilla builds NBT, then hand the already encoded record to Minecraft's save pipeline. Retain the previous record if new serialization fails, and preserve incompatible saved records. See [Minecraft persistence lifecycle](../minecraft/COMPUTER-PERSISTENCE.md).

Keep the device graph independent of world lookups. Server adapters will supply loaded state, permissions and validated link/unlink requests. Discovery uses cached reachability scoped to the computer. Network interface and sensor blocks, world-facing host functions and persistence for graph topology remain later work. See the [device network](../minecraft/DEVICE-NETWORK.md) and [Minecraft host contract](../minecraft/HOST-API.md).
