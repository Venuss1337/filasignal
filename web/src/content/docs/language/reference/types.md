---
title: Types and values
description: FilaScript value types, their annotation names, how they print and which values are mutable.
sidebar:
  order: 3
---

FilaScript is dynamically typed: a variable may hold any value unless it has a type annotation.

| Type | Annotation | Example | `print` shows | Mutable |
| --- | --- | --- | --- | --- |
| Number | `number` | `2.5` | `2.5`; whole numbers without a fraction (`3`); very large or small numbers in Java notation (`1.0E21`) | no |
| String | `string` | `"hi"` | `hi` | no |
| Boolean | `bool` | `true` | `true` | no |
| Nil | none | `nil` | `nil` | no |
| Error | `error` | from a failed call | `code: message` | no |
| List | `list` | `[1, 2]` | `[1, 2]` | yes |
| Tuple | `tuple` | `(1, 2)` | `(1, 2)` | no |
| Dictionary | `dict` | `{a: 1}` | `{a: 1}` | yes |
| Enum member | the enum's name | `Mode.ON` | `Mode.ON` | no |
| Class object | the class's name | `Counter()` | `Counter(instance)` | fields can change |
| Host handle | none | a device in FilaSignal | its type and name | no |

Long or deeply nested collections print as a shortened summary of at most a few hundred characters.

## No truthiness

`if`, `while`, `and`, `or` and `not` need a boolean. `0`, `""`, `nil` and empty lists are not "false"; using them as a
condition stops the program with `TYPE_MISMATCH`. Compare explicitly, such as `if list.length > 0` or
`if value is not nil`.

## Annotations

An annotation checks the value on every assignment, argument and field initialization. A mismatch stops the program
with `TYPE_MISMATCH`, for example `Expected number, got string`. Annotations exist for variables, parameters and class
fields; there is no return type syntax and no optional or union types. `nil` does not match any annotation.

## Strings

Strings support `+` (joining two strings), `is`, `print`, `str` and use as dictionary keys. They have no length,
indexing, slicing, searching or case methods in FilaScript 0.1, and cannot be looped over.

## Functions are not values

Function, class and enum names can only be called or used for their members. They cannot be stored, passed or returned.
