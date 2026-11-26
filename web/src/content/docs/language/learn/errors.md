---
title: Errors and failures
description: How FilaScript separates programming mistakes that stop a program from expected failures that return an error value.
sidebar:
  order: 5
---

FilaScript has no `try` and `catch`. Instead it separates two situations.

## Programming mistakes stop the program

A mistake such as dividing by zero, adding a number to a string, reading item 10 of a 3-item list or looping forever
is a **runtime fault**. It stops the function that is running, with a location and the chain of calls. Changes made
before the fault are kept. Fix the program; there is no way to catch a fault.

In the command-line tool a fault stops the whole run. In a host such as FilaSignal it stops only the current event
handler, and the program keeps waiting for the next event. See [execution](/language/reference/execution/).

## Expected failures return an error value

Some operations can fail for reasons outside your program, such as broken JSON text. They return **two values**: the
result and an error. When it worked, the error is `nil`. When it failed, the result is `nil` and the error describes
the problem:

```fsig
fn main() {
    good, err = json.parse("[1, 2, 3]")
    print(err is nil)
    bad, err = json.parse("{oops}")
    if err is not nil {
        print(err.code)
    }
}
```

```text title="Output"
true
json.invalid
```

An error value has two read-only fields: `code`, a short stable name such as `json.invalid`, and `message`, an
explanation for people. `print(err)` shows both as `code: message`. Two errors are equal when both fields are equal.

You must catch both values. Writing `data = json.parse(text)` stops the program with `RETURN_ARITY`, because
`json.parse` always gives back two values.

## Returning your own failures

Your functions can follow the same pattern by returning a value and a status. Programs cannot create `error` values
themselves, so use a string or `nil`:

```fsig
fn withdraw(balance, amount) {
    if amount > balance {
        return balance, "not enough coins"
    }
    return balance - amount, nil
}

fn main() {
    left, problem = withdraw(10, 25)
    if problem is not nil {
        print("Failed: " + problem)
    }
}
```

```text title="Output"
Failed: not enough coins
```

## Host functions

A host is the program that runs FilaScript, such as FilaSignal. Host functions that can fail also return
`(value, err)`. Hosts that declare a function this way get two conveniences that do not apply to `json.parse`:

- Used where one value is expected, such as `count = gate.count(Entity.HOSTILE)`, the call gives just the value. If it
  fails, the handler stops with the host's explanation.
- Used on its own line with its results ignored, a failure prints a warning instead of passing silently.

Writing `count, err = gate.count(Entity.HOSTILE)` always works and lets you handle the failure yourself. See
[results and errors in FilaSignal](/minecraft/api/#results-and-errors).
