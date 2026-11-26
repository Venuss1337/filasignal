---
title: Syntax
description: FilaScript's lexical rules, keywords, literals, statements, declarations and newline rules.
sidebar:
  order: 1
---

This page is a compact reference for FilaScript 0.1. The [grammar](/language/reference/grammar/) is the formal
version.

## Source text

- Files are UTF-8. A byte order mark at the start is ignored. Line endings are LF or CRLF; a lone CR is an error.
- Error positions use one-based lines and columns. Columns count Unicode code points.

## Names and keywords

Names start with an ASCII letter or `_`, followed by ASCII letters, digits or `_`. They are case-sensitive.

These 22 words are reserved and cannot be names:

`global` `const` `fn` `class` `enum` `on` `if` `else` `while` `for` `in` `return` `break` `continue` `true` `false`
`nil` `is` `and` `or` `not` `self`

`let` and `local` are ordinary names.

## Comments

`//` starts a comment until the end of the line. `/* ... */` is a block comment; block comments do not nest.

## Literals

| Literal | Form |
| --- | --- |
| Number | Decimal digits, an optional fraction with digits on both sides of the dot, an optional exponent: `42`, `2.5`, `1e-3`, `6.02E23`. No hex, no `_` separators, no `.5` or `5.`. A literal too large for a 64-bit float is an error. |
| String | Double quotes on one line. Escapes: `\"` `\\` `\n` `\r` `\t` `\u{H}` with 1 to 6 hex digits for a Unicode scalar. Control characters must be escaped. |
| Boolean | `true`, `false` |
| Nil | `nil` |
| List | `[a, b, c]`, trailing comma allowed |
| Tuple | `(a, b)`, `(a,)` for one element, `()` for none. `(a)` is grouping, not a tuple. |
| Dictionary | `{name: value, "quoted key": value}`. Bare keys are strings, not variables. |

## Statements and newlines

A newline ends a statement. Semicolons may also separate statements. A statement continues on the next line only
inside `()`, `[]` or `{}` of a dictionary, after a comma, or after an operator that needs a right side:

```fsig
fn main() {
    total = 1 +
        2
    items = [
        "a",
        "b",
    ]
    print(total); print(items)
}
```

An operator at the start of a line does not continue the previous line. A newline right after `return` means
"return nothing".

## Blocks

Blocks use braces. The opening `{` must be on the same line as the header (`if`, `while`, `for`, `fn`, `class`,
`on`). `else` may follow the closing `}` on the same line or on a later line; `else if` must be on one line.

## Top level

Only declarations may appear at the top of a file:

| Declaration | Form |
| --- | --- |
| Function | `fn name(a, b: number) { ... }` |
| Constant | `const NAME = expression` |
| Global | `global name = expression` |
| Class | `class Name { fields, constructor, methods }` |
| Enum | `enum Name { A, B, C }` |
| Event handler | `on name.parts(filters, values) { ... }` |

Ordinary statements such as `x = 1` or `print("hi")` are not allowed at the top level; put them in a function or
handler. `const` and `global` initializers may use literals, collection literals, operators, enum members and earlier
constants. They cannot call functions, except functions a host marks as references, such as FilaSignal's
`detectors.named("gate")`.

## Inside functions

| Statement | Form |
| --- | --- |
| Assignment | `x = value`, `a, b = f()`, `list[0] = v`, `dict.key = v`, `obj.field = v` |
| Annotated local | `count: number = 0` |
| Local constant | `const limit = 10` |
| Update | `x++`, `x--`, `x += v`, `-=`, `*=`, `/=`, `%=` |
| Call | `print(x)`, `list.append(v)` |
| If | `if cond { } else if cond { } else { }` |
| While | `while cond { }` |
| For | `for item in collection { }` |
| Loop control | `break`, `continue` |
| Return | `return`, `return v`, `return a, b` |

An expression on its own line must be a call. `x = y++` is invalid: `++`, `--` and compound assignments are statements,
not expressions.
