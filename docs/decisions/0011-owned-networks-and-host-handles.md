# ADR 0011 — Owned networks and typed Minecraft handles

**Status:** Accepted for the Milestone 8 prototype.

Minecraft supplies the placing player's UUID. A linked peripheral inherits configuration authority from its network. A peripheral that has never been linked uses its placement owner. One versioned topology record owns ACLs, grants, aliases and edges. Owners delegate configuration to at most 16 trusted players. Destructive reset, player removal, grant management and access management require an owner or administrator. Legacy ownerless devices stay administrator-managed until explicitly claimed.

Register factories and instance members through the same typed host registry. A handle contains an immutable type, controller UUID and resource UUID. The host resolves current Minecraft state on the server thread. Property access and method calls share the existing checks for types, capabilities, budgets and source spans. Host handles compare by scoped identity and are excluded from globals snapshots, JSON and event payloads. A script can copy selected slot snapshot fields into a dictionary for persistence. The grammar stays the same.

Inventory mappings bind an interface face and alias to an owner and a persistent identity on the adjacent block entity. A new block does not inherit that identity. The Fabric Transfer API supplies sided storage and transactions. Protection stays a separate veto checked on every operation. Output power remains transient. Bounded vanilla neighbor notifications capture visible short redstone transitions, and polling reconciles the final state.

See [host API](../minecraft/HOST-API.md), [inventory automation](../minecraft/INVENTORY.md), and [permissions](../minecraft/PERMISSIONS-AND-THREADING.md).
