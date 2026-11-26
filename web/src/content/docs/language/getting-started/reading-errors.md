---
title: Reading errors
description: Understand FilaScript error messages, where they point and how to fix the most common ones.
sidebar:
  order: 3
---

Everyone's programs have mistakes. FilaScript tells you where a mistake is and what kind it is. There are three
kinds of problems.

## 1. The program cannot be understood

Before running anything, FilaScript reads the whole program. If something is misspelled or a bracket is missing, it
stops and nothing runs:

```text
door.fsig:3:1: EXPECTED_TOKEN: Expected ')'
```

Every message has the same parts:

| Part | Meaning |
| --- | --- |
| `door.fsig` | The file. |
| `3:1` | Line 3, column 1. Lines and columns count from 1. |
| `EXPECTED_TOKEN` | The kind of problem, the same every time it happens. |
| `Expected ')'` | An explanation. |

The line shown is where FilaScript noticed the problem. Sometimes the real mistake is just before it, such as a
missing `)` at the end of the previous line.

Common ones:

- `UNKNOWN_BINDING`: a name was used before it was given a value, or it is misspelled.
- `EXPECTED_TOKEN` or `EXPECTED_EXPRESSION`: a bracket, quote or value is missing.
- `INVALID_ASSIGNMENT_TARGET`: something on the left of `=` cannot hold a value.
- `INVALID_INITIALIZER`: a top-level `const` or `global` tried to call a function. Their values must be simple
  values written directly in the program.

## 2. The program stops while running

Some mistakes only show up when the program runs, such as dividing by zero. The program stops and shows where it
was, plus the chain of function calls that led there:

```text
fault.fsig:2:12: DIVISION_BY_ZERO: Division or remainder by zero
  at half (fault.fsig:5:11)
  at main (fault.fsig:4:4)
```

Read it from the top: the problem is at line 2, inside `half`, which was called on line 5 from `main`. Other common
runtime problems are `TYPE_MISMATCH` (for example adding a number to a string), `INDEX_OUT_OF_BOUNDS` (asking for
item 5 of a 3-item list) and `MISSING_KEY` (reading a dictionary key that is not there).

A program that loops forever is stopped with `EXECUTION_BUDGET`, because every run may only do a limited amount of
work.

## 3. Something expected went wrong

Some operations can fail for reasons that are not a programming mistake, such as reading JSON text that is broken.
They do not stop the program. Instead they give you two results, the value and an error, and you decide what to do:

```fsig
fn main() {
    data, err = json.parse("[1, 2")
    if err is not nil {
        print("Could not read the data: " + err.message)
        return
    }
    print(data)
}
```

```text title="Output"
Could not read the data: Expected ']' at JSON UTF-16 offset 5
```

[Errors and failures](/language/learn/errors/) explains this in detail, and the
[diagnostics reference](/language/reference/diagnostics/) lists every error kind.
