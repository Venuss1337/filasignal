---
title: Diagnostic codes
description: Every FilaScript error code, when it happens and how to fix it.
sidebar:
  order: 6
---

Each problem has a stable code. Codes in the first three tables stop a program before it runs. Codes in the last table
stop a running function or handler.

## Reading the source

| Code | Meaning |
| --- | --- |
| `INVALID_CHARACTER` | A character that FilaScript does not use outside a string or comment, such as `&` or `\|`. Use `and` and `or`. |
| `INVALID_LINE_ENDING` | A carriage return without a line feed. Save the file with LF or CRLF line endings. |
| `UNSUPPORTED_OPERATOR` | `==` or `!=`. Use `is` and `is not`. |
| `INVALID_NUMBER` | A malformed number, such as `1.` or `2e`. |
| `NUMBER_OUT_OF_RANGE` | A number literal too large to store. |
| `INVALID_ESCAPE` | An unknown `\` escape in a string. |
| `INVALID_STRING_CHARACTER` | A control character inside a string. Use `\n` or `\t`. |
| `UNTERMINATED_STRING` | A string without its closing `"` on the same line. |
| `UNTERMINATED_COMMENT` | A `/*` comment without `*/`. |

## Parsing

| Code | Meaning |
| --- | --- |
| `EXPECTED_TOKEN` | A specific symbol is missing, such as `)` or `}`. |
| `EXPECTED_EXPRESSION` | A value is missing, for example after `=` or an operator. |
| `EXPECTED_DECLARATION` | Something other than `fn`, `const`, `global`, `class`, `enum` or `on` at the top level. |
| `EXPECTED_SEPARATOR` | Two statements on one line without `;`. |
| `EXPECTED_CALL_STATEMENT` | A line that computes a value but does nothing with it. Only calls may stand alone. |
| `INVALID_ASSIGNMENT_TARGET` | The left side of `=` cannot be assigned, such as a call or a literal. |
| `INVALID_ASSIGNMENT_ARITY` | `++`, `--` or a compound assignment such as `+=` with more than one target or value. |
| `CHAINED_COMPARISON` | `a < b < c`. Write `a < b and b < c`. |
| `INVALID_CONSTRUCTOR_NAME` | A constructor whose name differs from its class. |
| `UNSUPPORTED_UPDATE_POSITION` | `++`, `--` or `+=` used inside an expression instead of as a statement. |
| `NESTING_LIMIT` | Brackets or blocks nested too deeply. |

## Names and structure

| Code | Meaning |
| --- | --- |
| `UNKNOWN_BINDING` | A name with no visible declaration, or an unknown type annotation. |
| `DUPLICATE_BINDING` | A name declared twice in one scope, a second constructor, or a class named like a built-in type. |
| `IMMUTABLE_BINDING` | Assigning to a constant, function, class or enum. |
| `INVALID_DECLARATION` | A declaration in the wrong place, such as `global` inside a function, or an event a host does not provide. |
| `INVALID_CONTROL_FLOW` | `break` or `continue` outside a loop, or a constructor returning a value. |
| `INVALID_INITIALIZER` | A top-level constant or global calling a function or reading something other than earlier constants. |
| `FUNCTION_AS_VALUE` | A function, class or enum name used as a value instead of being called. |
| `UNSUPPORTED_FEATURE` | Syntax that exists but is not allowed here, such as `self` outside a class. |
| `ANALYSIS_DEPTH_LIMIT` | The program's structure is nested too deeply to analyze. |

## While running

| Code | Meaning |
| --- | --- |
| `UNKNOWN_ENTRY_POINT` | No `fn main()` for the command-line tool to run. |
| `NOT_CALLABLE` | Calling something that is not a function, class or method. |
| `ARGUMENT_COUNT` | Wrong number of arguments in a call. |
| `RETURN_ARITY` | A call gave a different number of results than needed, such as a function with no `return` used as a value. |
| `TYPE_MISMATCH` | A value of the wrong kind: adding a string to a number, a non-boolean condition, a failed annotation. |
| `DIVISION_BY_ZERO` | Division or remainder by zero. |
| `NON_FINITE_NUMBER` | A result too large to store. |
| `UNSUPPORTED_ITERABLE` | `for` over something that is not a list, tuple or dictionary. |
| `UNSUPPORTED_DESTRUCTURING` | Unpacking a value that cannot be unpacked, or dictionary unpacking with names that do not match. |
| `EXECUTION_BUDGET` | The invocation used up its work budget, usually an endless loop. |
| `CALL_DEPTH_LIMIT` | Too many nested calls, usually endless recursion. |
| `EXECUTION_DEPTH_LIMIT` | Expressions or blocks nested too deeply while running. |
| `COLLECTION_LIMIT` | A collection would exceed 4,096 entries. |
| `INDEX_OUT_OF_BOUNDS` | A list or tuple index that is negative, fractional or too large. |
| `MISSING_KEY` | A dictionary key or object field that does not exist. |
| `IMMUTABLE_VALUE` | Changing a tuple, an enum or a read-only value. |
| `DATA_LIMIT` | The invocation created too much data. |
| `OUTPUT_LIMIT` | The invocation printed too much. |
| `CANCELLED` | The host stopped the invocation, for example from a debugger. |
| `PERSISTENCE_FAILURE` | Saved globals could not be restored into this program. |
| `HOST_FAILURE` | A host function failed. In FilaSignal, the message includes the device's explanation and error code. |
