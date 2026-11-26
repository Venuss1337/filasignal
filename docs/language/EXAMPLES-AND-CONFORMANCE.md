# Examples and conformance catalog (planned, not runnable yet)

Use this document to create actual `.fsig` files under `examples/` and `conformance/` during implementation. A language feature is incomplete until its valid and invalid cases run in automated tests. The following samples describe **expected** behavior, not a completed implementation.

## A. State and scopes — valid

```fsig
global count = 0
const LIMIT = 5

fn tick() {
    localCount = 1
    count++
    if count < LIMIT {
        localCount += 2
        temp = "inside"
    }
    return localCount
}

on startup {
    print(tick())
}
```

Expected: `global count` persists across events/restarts after supported serialization; `localCount` is function-local, `temp` is block-local.

## B. Multiple returns — valid

```fsig
fn divide(a, b) {
    if b is 0 {
        return nil, "division by zero"
    }
    return a / b, nil
}

on startup {
    result, err = divide(10, 2)
    if err is nil {
        print(result)
    }
}
```

## C. Collections and classes — valid

```fsig
enum State { OFF, ON }
class Counter {
    value = 0
    Counter(initial) { self.value = initial }
    fn advance() { self.value++ }
}

on startup {
    c = Counter(3)
    c.advance()
    config = { mode: "fast", levels: [1, 2, 3] }
    position = (1, 64, 1)
    x, y, z = position
    print(c.value)
    print(config.mode)
}
```

## D. Host API — target usage (requires simulated host first)

```fsig
global activations = 0

on redstone.rising("left") {
    strength, err = redstone.read("left")
    if err is not nil {
        log.warn(err.message)
        return
    }
    ok, err = redstone.set("right", strength)
    if err is not nil {
        log.error(err.message)
        return
    }
    activations++
}
```

## E. Player proximity — target usage, privilege-gated

```fsig
on timer.every(20) {
    nearby, err = players.nearby(8)
    if err is not nil {
        log.warn(err)
        return
    }
    for player in nearby {
        log.info(player.name)
    }
}
```

## F. Negative tests

- `global` inside a function: compile error.
- `x++` without an existing `x`: compile error.
- `const limit = 5; limit = 6`: compile error.
- `if 1 { }`: runtime type error (or analyzer error if static type known).
- `if a == b { }`: syntax error; use `is`.
- `if a != b { }`: syntax error; use `is not`.
- `if a && b { }`: syntax error; use `and`.
- `a, b = fnReturningOne()`: arity error.
- `num = 1 / 0`: deterministic numeric error, not Infinity.
- `list = [1]; item = list[100]`: bounds error with span.
- `while true { }`: budget exhaustion, other simulated controllers remain runnable.
- `global items = []; on timer.every(1) { items.append("x") }`: persistence/memory limit enforcement under long simulation.
- `world.setBlock(...)` with read-only capability: permission failure, no mutation.
- World block query into unloaded chunks: no unexpected forced load.

## Conformance test manifest (to generate later)

Each case should record: input `.fsig`, expected parse/diagnostic, expected runtime output/values, expected host mutations, resource budget, version of grammar. Shared behavior is critical if a browser playground ever uses a separate interpreter implementation.
