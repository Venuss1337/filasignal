---
title: JSON
description: Turn FilaScript values into JSON text and read JSON text back with json.stringify and json.parse.
sidebar:
  order: 7
---

JSON is a common text format for structured data. FilaScript can write values as JSON and read JSON back.

```fsig
fn main() {
    report = {name: "gate", visitors: ["Alex", "Steve"], open: true}
    text, err = json.stringify(report)
    print(text)
    copy, err = json.parse(text)
    print(copy.visitors[1])
}
```

```text title="Output"
{"name":"gate","visitors":["Alex","Steve"],"open":true}
Steve
```

## json.stringify(value)

Returns `(text, nil)`, or `(nil, err)` when the value cannot be written as JSON. Allowed values are `nil`, booleans,
finite numbers, strings, lists and dictionaries, nested inside each other. Tuples, enum values and class objects are
not JSON values: convert them to lists or dictionaries first. A list that contains itself cannot be written.
Every number is written with a decimal point, so `3` becomes `3.0` and `[1, 2]` becomes `[1.0,2.0]`; very large or
small numbers use an exponent such as `1.0E21`. Reading the text back gives the same numbers.

## json.parse(text)

Returns `(value, nil)`, or `(nil, err)` when the text is not valid JSON. JSON objects become dictionaries, arrays
become lists, and `null` becomes `nil`. Duplicate keys in one object are rejected.

Both functions always return two values, so catch both: `value, err = json.parse(text)`. The error code is
`json.invalid`.

## Limits

JSON text may be at most 65,536 characters, nested at most 64 levels deep. Collections still hold at most 4,096
entries.
