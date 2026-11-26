# ADR 0002 — FilaScript 0.1 P0 semantics

**Status:** Accepted for the 0.1 language contract. Runtime behavior is specified for later milestones.

## Decisions

- Braces delimit lexical scopes. Plain assignment to a name updates the nearest visible binding or declares a dynamic local in the current scope. Resolve the right-hand side before introducing a new local. A write to an existing constant fails. Compound and postfix updates require an existing writable target.
- File scope contains declarations. Top-level globals are registered before resolution of function bodies and persist per controller. A missing saved value uses a deterministic initializer. An incompatible saved value requires validation and an explicit migration or activation error.
- `is` compares primitive values, enum members within their type, and collections structurally with bounded cycle handling. Class instances and handles use identity. `is not` negates that result. Boolean operators require booleans and short-circuit where applicable.
- Calls and destructuring use exact arity. A direct call on the right of a multi-target assignment supplies its results. A grouped call can explicitly select a single collection for destructuring. Scalar contexts require one result, and call statements discard results. This keeps a returned tuple distinct from a result list.
- Numbers use finite binary64 values with round-to-nearest, ties-to-even. Signed zero compares equal to zero. Division or remainder by either signed zero and non-finite arithmetic results raise deterministic numeric faults. Underflow to a finite subnormal or signed zero is permitted.

The [specification](../language/SPEC-0.1-DRAFT.md) defines scope, evaluation and unpacking details. Storage schemas and runtime verification are tracked in later milestones.
