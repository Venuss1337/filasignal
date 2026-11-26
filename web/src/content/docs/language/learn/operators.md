---
title: Operators
description: Arithmetic, joining text, comparisons, is and is not, and, or, not, and changing variables with ++ and +=.
sidebar:
  order: 2
---

## Arithmetic

| Operator | Meaning | Example | Result |
| --- | --- | --- | --- |
| `+` | add, or join two strings | `2 + 3`, `"a" + "b"` | `5`, `"ab"` |
| `-` | subtract, or make negative | `10 - 4`, `-x` | `6` |
| `*` | multiply | `6 * 7` | `42` |
| `/` | divide | `7 / 2` | `3.5` |
| `%` | remainder after dividing | `7 % 3`, `-7 % 3` | `1`, `-1` |

Arithmetic works on numbers only. Dividing by zero, or a result too large to store, stops the program
(`DIVISION_BY_ZERO`, `NON_FINITE_NUMBER`). `"Score: " + 5` stops the program with `TYPE_MISMATCH`; write
`"Score: " + str(5)`.

## Comparing

| Operator | Meaning |
| --- | --- |
| `a is b` | `a` equals `b` |
| `a is not b` | `a` does not equal `b` |
| `<`, `<=`, `>`, `>=` | less than, at most, greater than, at least (numbers only) |

FilaScript uses the words `is` and `is not` instead of `==` and `!=`. `1 is 1.0` is `true`. Strings, booleans and
`nil` compare by value. Lists, tuples and dictionaries compare by their contents, so `[1, 2] is [1, 2]` is `true`, but
a list never equals a tuple. Objects made from classes are only equal to themselves.

Comparisons cannot be chained: write `0 < x and x < 10`, not `0 < x < 10`.

## Logic

`and`, `or` and `not` work on `true` and `false` only. `if 1 { }` is an error, because `1` is not a boolean.

```fsig
fn main() {
    raining = true
    hostile = 2
    if raining and hostile > 0 {
        print("Stay inside")
    }
    if not raining or hostile is 0 {
        print("Safe")
    }
}
```

```text title="Output"
Stay inside
```

`and` stops early if the left side is `false`, and `or` stops early if it is `true`, so the right side is not
evaluated.

## Changing a variable

These shortcuts change a variable, list item or dictionary entry in place. They are statements on their own line,
not values you can use inside another expression.

| Statement | Same as |
| --- | --- |
| `x++` | `x = x + 1` |
| `x--` | `x = x - 1` |
| `x += 5` | `x = x + 5` |
| `x -= 5`, `x *= 2`, `x /= 2`, `x %= 3` | the same idea |

```fsig
fn main() {
    coins = 10
    coins += 5
    coins++
    message = "Coins: "
    message += str(coins)
    print(message)
}
```

```text title="Output"
Coins: 16
```

## Order of operations

From first to last: unary `-` and `+`, then `*` `/` `%`, then `+` `-`, then comparisons and `is`, then `not`, then
`and`, then `or`. Use parentheses to choose another order. See the [operator reference](/language/reference/operators/).
