---
title: Operators and precedence
description: Every FilaScript operator, what it accepts, its precedence and the equality rules.
sidebar:
  order: 2
---

## Precedence

From highest (binds tightest) to lowest:

| Level | Operators | Accepts |
| --- | --- | --- |
| 1 | `x()` `x[i]` `x.name` | calls, indexing, members |
| 2 | unary `-x` `+x` | numbers |
| 3 | `*` `/` `%` | numbers |
| 4 | `+` `-` | numbers; `+` also joins two strings |
| 5 | `<` `<=` `>` `>=` `is` `is not` | ordering: numbers only; `is`: any values |
| 6 | `not` | booleans |
| 7 | `and` | booleans, short-circuit |
| 8 | `or` | booleans, short-circuit |

Operators at the same level group left to right, except comparisons, which cannot be chained: `a < b < c` is an
error. Parentheses change the order. Because `not` is below comparisons, `not a is b` means `not (a is b)`.

## Arithmetic rules

- Numbers are IEEE 754 64-bit floats. Integer-looking values are floats too.
- Division or remainder by zero stops the program (`DIVISION_BY_ZERO`). A result that is infinite or not a number
  stops it too (`NON_FINITE_NUMBER`).
- `%` keeps the sign of the left operand: `-7 % 3` is `-1`.
- No value converts to a number or string by itself. Use `str(value)` to make text.

## Equality: is and is not

| Values | Equal when |
| --- | --- |
| Numbers | numerically equal; `1 is 1.0`, `0 is -0` |
| Strings, booleans, `nil` | same value |
| Errors | same `code` and `message` |
| Enum members | same enum and member |
| Lists, tuples | same kind, same length and equal elements in order |
| Dictionaries | same keys with equal values, in any order |
| Class objects, host handles | the very same object |

Values of different kinds are never equal: `1 is "1"` and `[1] is (1,)` are both `false`. Comparing collections that
contain themselves is safe.

## Assignment operators

`=`, `+=`, `-=`, `*=`, `/=`, `%=`, `++` and `--` are statements. Compound forms and `++`/`--` need an existing
variable, list element, dictionary key or field. `+=` on two strings joins them.
