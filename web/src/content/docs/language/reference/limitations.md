---
title: Known limitations
description: What FilaScript 0.1 deliberately does not have yet, and how to work around it.
sidebar:
  order: 9
---

FilaScript 0.1 is small on purpose. These are the gaps most people meet first.

| Missing | Work-around |
| --- | --- |
| String length, indexing, searching, splitting, upper/lower case | Keep structured data in lists and dictionaries instead of parsing text. |
| `range()` and counting `for` loops | `while` with a counter. |
| Math helpers such as `abs`, `floor`, `round`, `min`, `max` | Write small functions, for example `fn abs(n) { if n < 0 { return -n } return n }`. |
| Functions as values, closures, lambdas | Use named functions and pass data instead. |
| Inheritance, interfaces, static members | Use separate classes or dictionaries. |
| `try`/`catch` | Expected failures return `(value, err)`; programming mistakes should be fixed. |
| Modules and imports | One program is one file. |
| Return type annotations, optional and union types | Annotate parameters and variables. |
| Dictionary keys other than strings | Convert keys with `str(value)`. |
| Ordering strings with `<` | Compare numbers, or use enums. |
| Async code, threads, sleeping | Hosts run handlers when events arrive, such as `on timer.every(20)`. |
| Files, network, operating system access | Not available by design. |

Other behaviors to know:

- JSON writes every number with a decimal point (`3.0`).
- `print` shows a one-element tuple as `(1)`.
- Numbers are 64-bit floats; very large whole numbers lose precision.

The project's open design questions are tracked in the repository's `docs/OPEN-QUESTIONS.md`.
