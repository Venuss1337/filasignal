# Engineering standards

These requirements apply to all project changes, including AI-assisted work. Tests and documentation are part of the feature.

## Design

- Apply SOLID where it makes change easier to reason about, especially single responsibility, dependency inversion and interface segregation. Keep interfaces narrow and use dependency injection where it solves concrete coupling or testability problems.
- Keep the language core, runtime, Minecraft adapters, devices, networking, persistence and UI separated by explicit ownership and dependencies. Prefer composition to inheritance when either works.
- Use focused classes and descriptive, consistent names. Avoid catch-all managers, unrelated utility collections, duplicated business rules and deeply nested control flow.
- Keep methods cohesive and reasonably small. Extract behavior for a meaningful boundary or reusable operation. Avoid arbitrary line limits, speculative frameworks and premature generalization.
- Keep changes localized and dependencies minimal. Use the existing API or representation when it already meets the contract.

## Contracts

- Validate at trust boundaries. Prefer immutable snapshots, event payloads and cross-component messages. Document who owns mutable state and which thread may access it.
- Use structured diagnostics and consistent error handling. Propagate or report failures with enough context to act on them.
- Document public APIs with useful Javadoc covering parameter contracts, return values, failure cases, ownership and thread requirements where relevant. Use comments to explain non-obvious invariants, sequencing, synchronization, security and performance choices.

## Verification and review

- Write meaningful automated tests for normal behavior, boundaries, failures and regressions. Use standalone tests for platform-independent behavior and world tests for integration.
- Record significant choices in ADRs and keep the implementation, tests and contract documentation aligned.
- Before closing substantial work, review readability, duplication, coupling, performance, correctness and testability. Address concrete problems introduced by the change and record any unverified behavior.
