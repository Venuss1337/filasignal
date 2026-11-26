# FilaScript core

- Pure Java: no Minecraft, Fabric or other non-JDK runtime dependencies. Tests use JUnit 5.
- Pipeline: `source` (UTF-8, spans) → `lexer` → `parser` (AST in `ast`) → `analysis.Resolver` (bindings, diagnostics)
  → `runtime.execution.Interpreter`. `runtime.event.Simulation` schedules controllers; `runtime.persistence` and
  `GlobalSnapshot` own the versioned global-state format; `runtime.debug` is the debugger; `host` is the typed host
  registry that the mod and tests implement.
- A syntax change updates `docs/language/GRAMMAR-0.1.ebnf`, `docs/language/SPEC-0.1-DRAFT.md`, parser tests with
  exact spans, and the language pages in `web/src/content/docs/language/`.
- Every new operation must be charged to the execution budget (operations or data units) and respect collection
  limits. Add a test that a hostile program hits the limit instead of exhausting memory or time.
- `DocumentationExamplesTest` runs every example in the language docs and compares its output; keep it passing.
- `LanguageBenchmark` is tagged `benchmark` and runs only with `./gradlew :filascript-core:languageBenchmark`.
