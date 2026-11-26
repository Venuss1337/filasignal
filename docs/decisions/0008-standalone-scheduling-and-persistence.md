# ADR 0008 — Standalone scheduling and durable controller state

**Status:** Accepted for Stages 5C–5E.

Use the same resolver and interpreter for CLI functions and embedded event handlers. A controller owns one interpreter, its capabilities and a queue of future deliveries. Dispatch is sequential and tick-driven, with one eligible handler per controller per tick and a rotating cursor under the shared work budget. Reserve a full handler allowance before dispatch. Report a fault without aborting the scheduler. See [runtime API](../engineering/STANDALONE-RUNTIME.md) and [resource limits](../engineering/SCALAR-AND-COLLECTION-LIMITS.md).

Snapshot only named file globals. Use a bounded, versioned binary object table with a checksum. References preserve aliases and cycles through mutable objects. Restore instances from their class field schema without executing constructors. The storage adapter owns atomic replacement of the last valid state. Program changes that alter saved names or schemas require a host migration. See [persistence](../engineering/PERSISTENCE.md).
