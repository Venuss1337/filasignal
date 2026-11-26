# ADR 0006 — Native collections and JSON

**Status:** Accepted for Stage 5A.

Lists and dictionaries hold mutable references. Tuples have immutable slots. List and tuple indexes are zero-based integers. Dictionaries use case-sensitive string keys and preserve insertion order. Indexing or reading an absent key faults. A dictionary key takes precedence over a method of the same name. A dictionary member or index assignment inserts or replaces a key.

List and tuple iteration visits elements. Dictionary iteration visits keys. Iteration snapshots the slots or keys at loop entry, so structural changes during the loop do not change its itinerary. Nested values remain shared references. Structural equality distinguishes collection kinds, ignores dictionary order, and compares cyclic graphs using visited identity pairs. Parallel assignment checks arity, resolves addresses and verifies writable targets before committing writes. Evaluation side effects remain visible.

List methods are `append(value)`, `insert(index, value)`, `remove(index)`, and `clear()`. The read-only `.length` property applies to lists and tuples. Dictionary methods are `keys()`, `values()`, `contains(key)`, `remove(key)`, and `clear()`. Remove returns the removed value, keys and values return snapshot lists, and append, insert and clear return zero values.

JSON encodes scalars, lists and dictionaries. Duplicate JSON keys, unpaired surrogates, non-finite numbers, cycles and non-JSON runtime values produce `(nil, error)`. Success produces `(value, nil)`. The codec caps input and output at 65,536 UTF-16 units and nesting at 64. Collections cap at 4,096 entries. JSON remains an interchange codec over native values.
