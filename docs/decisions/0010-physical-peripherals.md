# ADR 0010 — Physical peripherals and loaded-only redstone

**Status:** Accepted for the prototype.

A programmable computer remains the single scheduler attachment for its network. Passive devices carry stable UUIDs in block-entity data. Server-thread Fabric lifecycle callbacks feed a loaded registry and the existing cached device graph. One versioned world SavedData record owns aliases, port definitions, topology and network membership. This keeps an edge in one place and preserves the distinction between absent and unavailable endpoints.

Wireless links require both endpoints to be loaded for edits. Routing follows loaded same-dimension edges. A network adopts peripheral endpoints as it grows; unlink disconnects without transferring ownership. The temporary linking tool and configuration commands require vanilla game-master permissions. This avoids an incomplete per-player permission model while still allowing an operator to test end-to-end automation on a server.

The prototype uses one port per world-facing side. Redstone writes are bounded 0–15 and transient. Input and player observations are round-robin and tick-bounded; notifications join the existing controller event queue following script dispatch. This avoids a new event thread and synchronous redstone reentry. Polling trades short-pulse fidelity and latency for bounded world work. Device host metadata lives in the existing registry, so tooling can discover the exact parameter, return, capability and cost contracts. Neither the core language nor its standalone documentation acquires a Minecraft dependency.

Chunk saves and world SavedData writes are independent vanilla operations. A flushed save is testable; atomicity across records and power-loss recovery remain outside this prototype. See [device network](../minecraft/DEVICE-NETWORK.md), [Minecraft host API](../minecraft/HOST-API.md) and [persistence](../minecraft/COMPUTER-PERSISTENCE.md).
