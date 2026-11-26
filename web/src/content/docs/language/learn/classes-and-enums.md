---
title: Classes and enums
description: Group data and behavior with classes, fields, constructors and methods, and name fixed choices with enums.
sidebar:
  order: 8
---

## Classes

A class describes a kind of object: the data it holds (**fields**) and what it can do (**methods**). Calling the class
name creates a new object from it.

```fsig
class Counter {
    total = 0
    name = "counter"

    Counter(name) {
        self.name = name
    }

    fn add(amount) {
        self.total += amount
        return self.total
    }
}

fn main() {
    visitors = Counter("visitors")
    visitors.add(2)
    visitors.add(3)
    print(visitors.name + ": " + str(visitors.total))
}
```

```text title="Output"
visitors: 5
```

- **Fields** are written in the class body with a starting value. Every new object gets its own fresh copy, in the
  order they are written.
- The **constructor** has the same name as the class and runs after the fields are set. It is optional, a class has at
  most one, and it cannot return a value. Without one, the class is called with no arguments.
- **Methods** are functions declared with `fn` inside the class.
- Inside the constructor and methods, `self` is the object itself.

Each object is separate. Two objects are equal with `is` only if they are the same object, even when their fields
match. `print` shows an object as `Counter(instance)`.

Fields can have [type annotations](/language/learn/values-and-variables/#type-annotations-optional), and a class name
can be used as a type:

```fsig
class Point {
    x: number = 0
    y: number = 0
}

fn distance(a: Point, b: Point) {
    return (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
}

fn main() {
    far = Point()
    far.x = 3
    far.y = 4
    print(distance(Point(), far))
}
```

```text title="Output"
25
```

FilaScript classes have no inheritance, static members or operator overloading, and methods cannot be used as values.

## Enums

An enum is a fixed set of named choices. It is safer than using strings, because a misspelled choice is an error
before the program runs.

```fsig
enum Mode { OFF, ON, ALARM }

global mode = Mode.OFF

fn main() {
    mode = Mode.ALARM
    if mode is Mode.ALARM {
        print("Alarm mode")
    }
    print(mode)
    print(mode is "ALARM")
}
```

```text title="Output"
Alarm mode
Mode.ALARM
false
```

Enum members compare with `is`, are never equal to strings, and cannot be changed. An enum name can be used as a type
annotation. A host may provide its own enums, such as `Direction` in FilaSignal; an enum you declare with the same name
replaces it in your program.
