---
title: Functions
description: Declaring functions, parameters, return values, multiple return values and recursion in FilaScript.
sidebar:
  order: 4
---

A function is a named group of instructions you can run again and again. Declare it with `fn` at the top level of a
file:

```fsig
fn greet(name) {
    print("Hello, " + name)
}

fn main() {
    greet("Alex")
    greet("Steve")
}
```

```text title="Output"
Hello, Alex
Hello, Steve
```

`name` is a **parameter**: a variable that gets the value passed in the call. A call must pass exactly as many values
as there are parameters.

## Giving values back

`return` sends a value back to the caller:

```fsig
fn square(n) {
    return n * n
}

fn main() {
    print(square(4) + square(3))
}
```

```text title="Output"
25
```

A function that ends without `return`, or with a plain `return`, gives back no value. Using such a call where a value
is needed stops the program with `RETURN_ARITY`.

## Several return values

A function can return more than one value. Catch them with several names on the left of `=`:

```fsig
fn divide(a, b) {
    return a / b, a % b
}

fn main() {
    whole, rest = divide(17, 5)
    print(str(whole) + " remainder " + str(rest))
}
```

```text title="Output"
3.4 remainder 2
```

The number of names must match the number of values exactly. When a call is used as a single value, as in
`print(divide(17, 5))`, it must return exactly one value. A call on its own line may return any number of values,
and they are ignored.

## Functions calling functions

Functions can call other functions, including themselves. Each call uses a little of the program's work budget, and
there is a limit on how deeply calls may nest (64 in the command-line tool).

```fsig
fn factorial(n) {
    if n <= 1 {
        return 1
    }
    return n * factorial(n - 1)
}

fn main() {
    print(factorial(5))
}
```

```text title="Output"
120
```

## Rules to know

- Functions are declared at the top level of a file, or as methods inside a [class](/language/learn/classes-and-enums/).
  They cannot be nested inside other functions.
- A function is not a value: you cannot store it in a variable, pass it to another function or return it.
- A function may use top-level constants, globals and other functions declared anywhere in the file.
- Parameters can have [type annotations](/language/learn/values-and-variables/#type-annotations-optional), such as
  `fn heal(amount: number)`.
