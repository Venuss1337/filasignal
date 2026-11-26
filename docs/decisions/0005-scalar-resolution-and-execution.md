# ADR 0005 — Scalar resolution and execution

**Status:** Accepted for the combined resolver and core interpreter milestone.

## Binding and activation

- Resolution walks the immutable AST in source order after registering file-scope names. It stores reference-to-symbol mappings by AST occurrence. Each invocation gets fresh parameter and body storage, and each entered child block gets its own scope instance.
- Parameters share the function body scope. A parameter or a nested `const` may shadow an outer name. Explicit duplicate declarations within one scope are errors. Plain assignment follows ADR 0002. For parallel writes, resolve and evaluate all right-hand expressions first, then apply targets left to right after checking arity. A repeated name in a plain target list refers to one binding.
- This scalar activation evaluates file-scope `const` and `global` initializers in source order. They may use literals, scalar operators and earlier file constants. Requiring constants in the dependency chain to be pure prevents a global initializer from indirectly invoking a host function or observing mutable state. Functions can refer to later file declarations because the runtime activates the file before invoking a function.
- A failed activation publishes no global environment. Successful activation lasts for the lifetime of one interpreter instance. A fault during a later call leaves completed writes and host effects in place. The activation boundary is where compatible restored values will be installed once persistence exists.

## Calls and values

- The CLI invokes a zero-argument `fn main()` and discards its results. Embedded callers can invoke named top-level functions explicitly. Parenthesized function names work as call targets. Callable values remain internal and named functions cannot be stored, returned or passed by scripts.
- Call results are separate from language values. A scalar context requires exactly one result, and a call statement discards every result. Only a direct call can supply multiple results to an assignment. Collection destructuring and iteration await collection values, and scalar operands produce specific faults.
- Host-provided `error` values expose read-only `code` and `message` and compare by that pair. The CLI exposes `print(value)` as an immutable host binding in the outermost scope. It emits one line and returns zero values. An explicit declaration in an inner scope may shadow it.
- Collection literals, classes, enums, events, indexed access, member writes and annotation enforcement await later milestones. The resolver rejects their unsupported executable forms. The scalar runtime implements member reads for error fields.

## Limits

Each invocation has a fresh configurable operation budget and call-depth limit. Defaults are 100,000 operations and 64 call frames, including builtins. The first invocation pays for activation, and large numeric spellings, string operations and output are charged by size. String concatenation has a ceiling of 1,048,576 UTF-16 units per result. Structural analysis and combined evaluator nesting have separate limits of 192 and 256 to guard Java stack usage even with a high configured call depth. Runtime diagnostics contain a source span and innermost-first call sites. Calls on one interpreter are serial, and synchronous host re-entry fails. Full memory accounting and host scheduling are deferred.
