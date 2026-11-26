# Host objects

An embedded host registers static functions and an optional allowlist of object members. A `HostHandle` carries a type name, opaque scope and resource UUIDs. The interpreter supplies the handle as the first typed slot of its member function. Other slots may require a specific host object type. Properties take only the receiver and return one scalar value. Calls preserve ordinary FilaScript arity, budgets and source-located faults. The host validates scope and resource availability at use.

The registry exposes factories, object types, properties, methods, parameter and return slots, expected error codes, capability sets, base costs, documentation and examples. Dynamic work uses the same context as static calls. The registry is independent of Minecraft. See the [FilaSignal host API](../minecraft/HOST-API.md) for Minecraft-specific object behavior.

Handles are transient. A global snapshot containing a handle directly or through a collection is rejected. Store an alias string and resolve it at startup or during an event. Host properties are read-only. A detached read-only dictionary can be copied into a regular dictionary when selected fields need to be saved.
