# FilaScript standard library — proposed v0.1 surface

The standalone CLI implements `print(value)`, `str(value)` and the native collection and JSON operations. `print` and `str` show whole numbers without a fraction (`3`, not `3.0`) and collections as bounded readable summaries such as `[1, 2.5]` or `{name: "Zombie"}`. `str` returns that text, so a message can be built as `"Count: " + str(count)`. The simulator supplies a host-specific `log.info(message)`. Math, string helpers and `typeOf` remain proposed. The core language standard library must remain independent of Minecraft. Error and allocation behavior must be explicit and charged to execution budgets.

| Namespace / operation | Expected behavior | Notes |
|---|---|---|
| `print(value)` | output for CLI/debug host | Rate-limited; side effects depend on host |
| `str(value)` | text as `print` would show it | Bounded summary for collections; one result |
| `log.info(value)`, `.warn`, `.error` | structured logging | Host-specific, rate-limited |
| `math.abs(x)`, `min`, `max`, `floor`, `ceil`, `round` | numeric operations | Finite numbers only; defined invalid-type error |
| `string.length`, `contains`, `lower`, `upper`, `split` | string helpers | Bound output length/allocations; UTF-8/Unicode rules to specify |
| `list.append`, `insert`, `remove`, `clear`, `length` | mutable list operations | Account allocations; `.length` read-only |
| `dict.keys`, `values`, `contains`, `remove`, `clear` | string-key map helpers | Stable insertion order for iteration |
| `json.parse(text)` | `(value, err)` | Reject excess size/depth, invalid JSON |
| `json.stringify(value)` | `(string, err)` | JSON-compatible values only; no automatic serialization of classes/tuple/enum |
| `typeOf(value)` | stable language type name | Useful for debugging dynamic values |

**Important API consistency correction:** Use `(value, err)` for expected serialization failures: `encoded, err = json.stringify(data)`. `print` returns zero values. See the [native collection operations](../decisions/0006-native-collections.md) for signatures and result arities.

Minecraft namespaces such as `players`, `world`, `blocks`, `inventory`, `redstone` are **host library APIs**, not implemented inside core. See `docs/minecraft/HOST-API.md`.
