---
title: Values and variables
description: Numbers, strings, booleans, nil, variables, constants, comments and type annotations in FilaScript.
sidebar:
  order: 1
---

## Comments

Text after `//` until the end of the line is a comment. FilaScript ignores it, so you can write notes for people.
`/*` starts a comment that continues until `*/`, even across lines.

```fsig
// This whole line is a note.
fn main() {
    print("Hi") // A note after code.
    /* A longer note
       over two lines. */
}
```

## Values

Every piece of data is a **value**. These are the simple kinds:

| Kind | Examples | Notes |
| --- | --- | --- |
| number | `5`, `-3`, `2.5`, `1e6` | All numbers are the same kind. Whole numbers print without `.0`. |
| string | `"Hello"`, `"line\nbreak"` | Text between double quotes, on one line. |
| bool | `true`, `false` | Yes or no. |
| nil | `nil` | "Nothing here". |

Inside a string, a backslash starts a special character: `\n` new line, `\t` tab, `\"` a quote, `\` a backslash and
`\u{1F600}` any Unicode character by its code.

```fsig
fn main() {
    print(7 / 2)
    print(3.0)
    print("Say \"hi\"")
    print(true)
    print(nil)
}
```

```text title="Output"
3.5
3
Say "hi"
true
nil
```

Numbers are 64-bit floating-point numbers, so very large or fractional results can show small rounding, as in
`0.1 + 0.2`, which prints `0.30000000000000004`.

## Variables

A variable is a name that holds a value. Assigning with `=` creates it the first time and changes it afterwards.
There is no `let` or `var`.

```fsig
fn main() {
    score = 10
    score = score + 5
    print(score)
    score = "fifteen"
    print(score)
}
```

```text title="Output"
15
fifteen
```

A variable may hold any kind of value, and the kind can change. Names start with a letter or `_` and may contain
letters, digits and `_`. They are case-sensitive: `Score` and `score` are different. Reading a name that has no value
yet is an error before the program runs.

A variable created inside `{ }` belongs to that block. Changing an existing variable from inside a block changes the
outer one:

```fsig
fn main() {
    total = 1
    if true {
        total = 2        // changes the outer total
        inside = 3       // only exists in this block
    }
    print(total)
}
```

```text title="Output"
2
```

## Constants

`const` makes a name that can never be given another value. Use it for settings that should not change.

```fsig
const MAX_ITEMS = 64

fn main() {
    const greeting = "Welcome"
    print(greeting + ", you can carry " + str(MAX_ITEMS))
}
```

```text title="Output"
Welcome, you can carry 64
```

A constant cannot be reassigned, but a list or dictionary it holds can still change. Constants at the top of a file
may only use values written in the program, operators and earlier constants, never function calls.

## Global variables

`global` creates a variable at the top of a file that every function shares. A host such as FilaSignal saves globals
so they survive restarts. See [globals and events](/language/learn/globals-and-events/).

```fsig
global visits = 0

fn main() {
    visits++
    print(visits)
}
```

```text title="Output"
1
```

## Turning values into text

`print(value)` shows any value. `str(value)` gives the same text back as a string, which you need to join it with
other text, because `+` only joins two strings.

```fsig
fn main() {
    count = 3
    print("Count: " + str(count))
}
```

```text title="Output"
Count: 3
```

## Type annotations (optional)

You can write a type after a name with `:` to make sure it only ever holds that kind of value. A wrong value then
stops the program with `TYPE_MISMATCH`.

```fsig
fn area(width: number, height: number) {
    return width * height
}

fn main() {
    size: number = area(4, 5)
    print(size)
}
```

```text title="Output"
20
```

The type names are `number`, `string`, `bool`, `list`, `tuple`, `dict`, `error`, and the names of your own classes and
enums. Functions cannot declare a return type yet.
