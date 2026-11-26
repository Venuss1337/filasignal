---
title: Lists, tuples and dictionaries
description: Store many values together with lists, tuples and dictionaries, and read, change, loop over and compare them.
sidebar:
  order: 6
---

## Lists

A list holds values in order. Write it with square brackets. Positions, called **indexes**, start at `0`.

```fsig
fn main() {
    mobs = ["zombie", "skeleton"]
    mobs.append("spider")
    print(mobs[0])
    print(mobs.length)
    mobs[1] = "creeper"
    removed = mobs.remove(0)
    print(removed)
    print(mobs)
}
```

```text title="Output"
zombie
3
zombie
["creeper", "spider"]
```

| Operation | Does |
| --- | --- |
| `list[i]` | Reads element `i`. A negative or too-large index stops the program with `INDEX_OUT_OF_BOUNDS`. |
| `list[i] = value` | Replaces element `i`. |
| `list.length` | The number of elements. |
| `list.append(value)` | Adds a value at the end. |
| `list.insert(i, value)` | Inserts at position `i`, from `0` to `length`. |
| `list.remove(i)` | Removes element `i` and gives it back. |
| `list.clear()` | Removes everything. |

## Tuples

A tuple is a fixed group of values that cannot change after it is made. Use parentheses. A tuple with one element
needs a trailing comma, because `(5)` is just the number 5 in brackets.

```fsig
fn main() {
    position = (10, 64, -2)
    single = (42,)
    print(position[1])
    print(position.length)
    print(single.length)
}
```

```text title="Output"
64
3
1
```

Changing a tuple element stops the program with `IMMUTABLE_VALUE`.

## Dictionaries

A dictionary stores values under names called **keys**. Keys are always strings. A bare word before `:` is a key name;
use quotes for keys with other characters, such as `"minecraft:stone"`. A bare key cannot be a keyword such as
`on` or `in`: write `{"on": true}` and read it with `dict["on"]`.

```fsig
fn main() {
    player = {name: "Alex", score: 15}
    player.score++
    player["level"] = 3
    print(player.name + " has " + str(player.score))
    print(player.contains("level"))
    print(player.keys())
    player.remove("level")
    print(player)
}
```

```text title="Output"
Alex has 16
true
["name", "score", "level"]
{name: "Alex", score: 16}
```

| Operation | Does |
| --- | --- |
| `dict.key` or `dict["key"]` | Reads a value. A missing key stops the program with `MISSING_KEY`. |
| `dict.key = value` or `dict["key"] = value` | Adds or replaces a value. |
| `dict.contains(key)` | `true` when the key exists. Check before reading a key that might be missing. |
| `dict.keys()` | A new list of the keys, in the order they were added. |
| `dict.values()` | A new list of the values, in the same order. |
| `dict.remove(key)` | Removes a key and gives back its value. |
| `dict.clear()` | Removes everything. |

If a key has the same name as a method, such as a key called `keys`, the key wins.

## Looping

`for x in list` visits elements, and `for key in dict` visits keys in the order they were added. See
[control flow](/language/learn/control-flow/#going-through-a-collection-with-for).

## Nesting and sharing

Collections can contain other collections. Lists and dictionaries are shared, not copied: if two variables hold the
same list, a change through one is visible through the other.

```fsig
fn main() {
    base = {chests: [], name: "Base"}
    same = base
    same.chests.append("north")
    print(base.chests)
}
```

```text title="Output"
["north"]
```

## Comparing

`is` compares collections by their contents: two lists with equal elements in the same order are equal, and two
dictionaries with the same keys and values are equal in any order. A list never equals a tuple.

## Unpacking

Several names on the left of `=` can unpack one collection:

```fsig
fn makePlayer() {
    return {name: "Steve", score: 3}
}

fn main() {
    position = (4, 70, -9)
    x, y, z = position
    print(y)
    name, score = (makePlayer())
    print(name)
}
```

```text title="Output"
70
Steve
```

A list or tuple needs exactly one name per element. A dictionary needs names that match its keys exactly. When the
right side is a function call, wrap it in parentheses as above: `a, b = makePair()` expects the function to return
two values, while `a, b = (makePair())` unpacks the one collection it returns.

## Limits

A list, tuple or dictionary holds at most 4,096 entries.
