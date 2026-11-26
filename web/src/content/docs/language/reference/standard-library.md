---
title: Standard library
description: Every built-in function and collection method available to every FilaScript program.
sidebar:
  order: 4
---

The built-ins below exist in every FilaScript program, including the command-line tool. A host adds its own functions
on top, such as the [FilaSignal API](/minecraft/api/). FilaScript 0.1 has no math or string helper library yet.

## print(value)

Shows one value on its own line. Returns nothing. Numbers print without `.0` when whole, and collections as readable
summaries.

```fsig
fn main() {
    print("Level " + str(5))
    print([1, 2.5, "three"])
}
```

```text title="Output"
Level 5
[1, 2.5, "three"]
```

Errors: `ARGUMENT_COUNT` when not given exactly one value; `TYPE_MISMATCH` for a function, class or namespace;
`OUTPUT_LIMIT` when the run has printed too much (256 lines or 32,768 characters per run in the command-line tool).

## str(value)

Returns the text `print` would show, as a string. Use it to join numbers and other values with text.

```fsig
fn main() {
    blocks = 12
    print("You have " + str(blocks) + " blocks and " + str(blocks * 64) + " items")
}
```

```text title="Output"
You have 12 blocks and 768 items
```

## json.stringify(value)

Returns `(text, nil)` or `(nil, err)`. See [JSON](/language/learn/json/). Error code: `json.invalid`.

## json.parse(text)

Returns `(value, nil)` or `(nil, err)`. `text` must be a string, otherwise the program stops with `TYPE_MISMATCH`. Error
code: `json.invalid`.

## Lists

| Member | Returns | Errors |
| --- | --- | --- |
| `list.length` | number of elements | |
| `list.append(value)` | nothing | `COLLECTION_LIMIT` beyond 4,096 elements |
| `list.insert(index, value)` | nothing | `INDEX_OUT_OF_BOUNDS` unless `0 ≤ index ≤ length` |
| `list.remove(index)` | the removed element | `INDEX_OUT_OF_BOUNDS` |
| `list.clear()` | nothing | |

```fsig
fn main() {
    queue = ["a", "c"]
    queue.insert(1, "b")
    first = queue.remove(0)
    print(first + " then " + str(queue))
}
```

```text title="Output"
a then ["b", "c"]
```

## Tuples

| Member | Returns |
| --- | --- |
| `tuple.length` | number of elements |

## Dictionaries

| Member | Returns | Errors |
| --- | --- | --- |
| `dict.keys()` | new list of keys in insertion order | |
| `dict.values()` | new list of values in insertion order | |
| `dict.contains(key)` | `true` when the key exists | `TYPE_MISMATCH` for a non-string key |
| `dict.remove(key)` | the removed value | `MISSING_KEY` |
| `dict.clear()` | nothing | |

```fsig
fn main() {
    stock = {iron: 30, gold: 4}
    total = 0
    for value in stock.values() {
        total += value
    }
    print(total)
    print(stock.contains("diamond"))
}
```

```text title="Output"
34
false
```

## Errors

| Member | Returns |
| --- | --- |
| `err.code` | stable code such as `json.invalid` |
| `err.message` | explanation for people |
