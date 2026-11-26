# Open decisions to ratify before implementing corresponding features

All original P0 questions are resolved. [ADR 0002](decisions/0002-filascript-0.1-p0-semantics.md) records lexical binding, file scope, globals, equality, result arity and numeric faults. [ADR 0003](decisions/0003-source-text-and-lexical-boundaries.md) records source and newline rules. The [specification](language/SPEC-0.1-DRAFT.md) and [grammar](language/GRAMMAR-0.1.ebnf) define the contract.

| Priority | Question | Current default / proposed direction |
|---|---|---|
| P1 | What is the return annotation syntax? | Bindings are dynamic by default. Existing binding, field and parameter annotations enforce exact types. Return type syntax remains open. |
| P1 | Constructor overloads | By arity only after base classes; type-based overloading excluded initially |
| P1 | Class inheritance / closures | Exclude from core v0.1; introduce only with justified use cases |
| P2 | Non-string dictionary keys | Version 0.1 uses string keys. A later extension would need hashing, equality and JSON rules. |
| P1 | Behavior of invalid edited script | Last-known-good program vs fail-closed; decide with UI/server lifecycle requirements |
| P1 | Future save format upgrades | Format 1 preserves graphs, detects corruption, and requires explicit migration for incompatible saved schemas. Decide upgrades and backup policy in the storage adapter. |
| P1 | Player API trust/permission model | Read access and privileged operations separately; controls at owner/server level |
| P2 | TSON format | No single accepted standard; not a runtime value format. Support only if a concrete interoperability need appears |
| P2 | Browser playground execution | Choose later; test any browser runtime against language conformance suite |
| P2 | Public brand trademark/domain | Search before launch; no implied connection to FILA clothing brand |

P1 decisions are needed before implementing their corresponding runtime or Minecraft features. P2 decisions can remain open through language development.
