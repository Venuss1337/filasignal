---
title: Control flow
description: Making decisions with if and else, repeating with while and for, and stopping loops with break and continue.
sidebar:
  order: 3
---

## Making decisions

`if` runs a block only when its condition is `true`. `else if` tries another condition, and `else` runs when none
matched.

```fsig
fn describe(light) {
    if light < 4 {
        return "dark"
    } else if light < 10 {
        return "dim"
    } else {
        return "bright"
    }
}

fn main() {
    print(describe(2))
    print(describe(8))
    print(describe(15))
}
```

```text title="Output"
dark
dim
bright
```

The condition must be `true` or `false`. Keep the opening `{` on the same line as `if`. `else` may start on the line
after the closing `}`.

## Repeating with while

`while` repeats its block as long as the condition stays `true`. It is the way to count:

```fsig
fn main() {
    countdown = 3
    while countdown > 0 {
        print(countdown)
        countdown--
    }
    print("Go!")
}
```

```text title="Output"
3
2
1
Go!
```

## Going through a collection with for

`for item in collection` runs the block once for each element of a list or tuple, or each key of a dictionary.

```fsig
fn main() {
    for mob in ["zombie", "skeleton", "creeper"] {
        print("Watch out for the " + mob)
    }
    prices = {iron: 3, gold: 8}
    for name in prices {
        print(name + " costs " + str(prices[name]))
    }
}
```

```text title="Output"
Watch out for the zombie
Watch out for the skeleton
Watch out for the creeper
iron costs 3
gold costs 8
```

The loop works on a snapshot taken when it starts, so adding or removing elements inside the loop does not change
which elements it visits. There is no `range`; use `while` with a counter to repeat a number of times. Strings cannot
be looped over.

## break and continue

`break` leaves the loop at once. `continue` skips to the next round.

```fsig
fn main() {
    for n in [1, 2, 3, 4, 5, 6] {
        if n % 2 is 0 {
            continue
        }
        if n > 4 {
            break
        }
        print(n)
    }
}
```

```text title="Output"
1
3
```

## Scope

A variable created inside a block, including a loop or `if` block, exists only inside it. The loop variable of `for`
belongs to the loop body. Assigning to a name that already exists outside changes that outer variable instead of
making a new one.

## Leaving a function

`return` ends the function immediately, optionally with values. See [functions](/language/learn/functions/).
