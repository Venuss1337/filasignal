# FilaScript 0.1 — draft language specification

**Status:** P0 rules are ratified in [ADR 0002](../decisions/0002-filascript-0.1-p0-semantics.md) and [ADR 0003](../decisions/0003-source-text-and-lexical-boundaries.md). The standalone language and controller runtime implement collections, JSON, classes, enums, annotations, events and persistent globals. See [ADR 0006](../decisions/0006-native-collections.md), [ADR 0007](../decisions/0007-classes-and-annotations.md) and [ADR 0008](../decisions/0008-standalone-scheduling-and-persistence.md). [ADR 0005](../decisions/0005-scalar-resolution-and-execution.md) records standalone activation and execution. See [ADR 0004](../decisions/0004-filascript-parser-and-ast.md) for the AST, error recovery and nesting limit. Later rules remain subject to [open decisions](../OPEN-QUESTIONS.md). See [the grammar](GRAMMAR-0.1.ebnf).

## 1. Design goals and boundaries

FilaScript (`.fsig`) is a small, safe, event-driven, dynamic language for programmable Minecraft automation, designed for a standalone Java 25 interpreter. Its syntax borrows readability from Python/Lua/Go but retains **curly-brace scopes** and **no mandatory `let` or semicolons**. It is not Java or a general-purpose unrestricted JVM runtime.

The syntax snippets below illustrate the full language contract. The standalone scalar example is [hello.fsig](../../examples/hello.fsig).

## 2. Source model

- Decode strict UTF-8 and remove exactly one initial U+FEFF BOM if present. Preserve LF and CRLF in decoded text. A lone CR is a lexical error. Invalid UTF-8 fails at the zero-based byte offset of the malformed sequence in the original input. In-memory source must contain valid Unicode scalars.
- Each source has an identifier and display filename. Token and diagnostic spans use zero-based, half-open Java UTF-16 offsets into the BOM-stripped text. Offsets cannot split a surrogate pair. EOF has an empty span. Line and column numbers are one-based. Columns count Unicode code points, including tabs and combining marks individually. They are independent of byte offsets, grapheme clusters and display width. LF or CRLF advances the line after LF.
- Identifiers are case-sensitive and start with an ASCII letter or underscore, followed by ASCII letters, digits or underscores.
- Keywords: `global`, `const`, `fn`, `class`, `enum`, `on`, `if`, `else`, `while`, `for`, `in`, `return`, `break`, `continue`, `true`, `false`, `nil`, `is`, `and`, `or`, `not`, `self`.
- Spaces and tabs separate tokens. `//` begins a line comment. `/* ... */` is a non-nesting block comment. The lexer preserves every LF or CRLF as a newline token, including those inside comments. A CRLF occupies both UTF-16 units in its token span.
- Double-quoted strings allow raw Unicode scalars except double quote, backslash and U+0000 through U+001F. Supported escapes are `\"`, `\\`, `\n`, `\r`, `\t`, and `\u{H…}`. A Unicode escape contains one to six ASCII hex digits and must denote a scalar (through U+10FFFF, excluding surrogates). Strings end on the same physical line.
- Numbers use decimal digits, an optional fraction with digits on both sides of the dot, and an optional exponent with optional sign and required digits. Leading zeros are decimal. Leading signs are separate operators. Literal syntax has no radix prefixes, digit separators or named NaN/Infinity literals. Integer-looking and floating literals round to finite IEEE 754 binary64 values (Java `double`). Literal overflow is a lexical error. Underflow to a finite subnormal or zero is permitted. Operations requiring integers check integrality and range.

The lexer returns tokens and structured diagnostics in source order of detection. Invalid lexemes generally produce an `ERROR` token. An unterminated block comment produces a diagnostic while preserving its newline tokens. Every token stream ends with `EOF`, including streams with errors.

### Statement boundaries

- Separate declarations and statements with newlines or semicolons. The final item can end immediately before its closing brace or EOF.
- The parser ignores newlines inside parentheses, brackets and dictionary literals, or after a comma or an operator that requires a right operand. It permits a newline between the words of `is not`. Outside those contexts, a newline ends the statement. An operator at the start of the next line does not continue a finished expression.
- Keep an opening block brace on the same line as the last token of its header. An `else` may follow the preceding closing brace on the same line or after newlines, including comment newlines. A semicolon ends the conditional and cannot precede an attached `else`. The words `else if` stay on the same line. A statement-ending newline directly after `return` means a zero-result return.

## 3. Top-level and declarations

Allowed at file top level: `global`, `const`, `fn`, `class`, `enum`, `on`. Ordinary file-top-level assignments are invalid; behavior begins in handlers or called functions. `global` is only permitted here and is **persistent per controller**, not shared between controllers. No implicit globals.

```fsig
enum Mode {
    OFF,
    ON
}

global activations = 0
global mode = Mode.OFF
const MAX_POWER = 15
```

Top-level declarations are registered before entry code runs. The runtime evaluates file constants and missing global initializers in source order using literals, collection literals, scalar operators, enum members and earlier file constants. Function bodies can reference later file declarations. A parameter shares its function body's declaration scope, and an explicit parameter or inner constant can shadow an enclosing binding. Persistent global initializer expressions are restricted to deterministic, side-effect-free literals, collection literals, enum constants and constant expressions. Compatible saved values take precedence over initializers. A missing saved value uses its initializer. An incompatible saved value requires an explicit migration or an activation error. Do not execute user host API calls during global initialization. Class-instance persistence requires an explicit schema contract; do not silently claim support before tested.

Local assignment syntax is implicit:

```fsig
fn sample() {
    amount = 10          // Declares local `amount`
    amount += 2          // Updates it
    amount++             // Updates it
    amount = "twelve"    // Dynamic type change allowed

    if true {
        inside = 3       // Declared in this block
        amount = 20      // Existing outer local updated
    }
    // `inside` not visible here
}
```

Plain assignment updates the nearest visible earlier binding. If no binding is visible, it declares a dynamic local in the current block. Resolve the right-hand side before introducing newly declared names. Parameters and loop variables are bindings in their respective bodies. Globals and other top-level declarations are known before function analysis. Reading a name without a visible binding is a diagnostic. Compound and postfix updates require an existing writable binding or writable member/index target. `const` declares a non-reassignable binding and leaves contained mutable objects mutable. A write to an existing constant is an error. `let` and `local` are ordinary identifiers.

## 4. Types and truthiness

Values: `number`, `string`, `bool`, `nil`, `error`, `list`, `tuple`, `dict`, `enum`, `class instance`, `host handle` (validated opaque reference). Native lists/dicts/classes are heap-managed; values are never unrestricted Java objects.

- Dynamic type changes are allowed for unannotated bindings: `x = 1; x = "a"`.
- Optional binding, field and parameter annotations enforce exact types at initialization, assignment and argument binding. An annotated local is an explicit declaration. Return annotation syntax is a P1 decision.
- `if`, `while`, `and`, `or`, `not` require booleans. No Lua/Python truthiness coercion.
- Arithmetic on numbers only; `+` also concatenates two strings (no implicit string conversion). Number/string type errors report source spans.
- Supported annotation types are `number`, `string`, `bool`, `list`, `tuple`, `dict`, `error`, and named classes and enums. Optional and union types are deferred.
- A host may provide named enums, such as `Direction` or `Weather`. They behave like declared enums, persist in globals and compare by value. A program's own enum with the same name shadows them.

## 5. Operators

```fsig
count++
count--
count += 5
count *= 2

if count >= 10 and not disabled {
    print("ready")
}

if mode is Mode.ON {
    print("enabled")
}

if result is not nil {
    print(result)
}
```

- Equality: `a is b`, inequality: `a is not b`; `==` and `!=` intentionally unsupported initially.
- `and` and `or` short-circuit. Every evaluated operand must be Boolean and the result is Boolean. `not` negates its Boolean operand.
- Number equality is numeric; `1 is 1.0` true. Strings/bools/nil compare by value. Enums compare by declared member identity/type.
- Lists, tuples and dicts compare structurally with cycle detection and explicit cost accounting. Class instances and host handles compare by identity, not by traversing arbitrary fields.
- Ordered comparisons `<`, `<=`, `>`, `>=` are numeric initially (string ordering deferred). Chained comparisons (`a < b < c`) are invalid in 0.1; use `a < b and b < c`.
- Arithmetic precedence: unary (`-`, `+`), multiplication/division/remainder, addition/subtraction, comparison/equality, `not`, `and`, `or`; see grammar. Parentheses override precedence.
- Signed zero compares equal to zero. Division and remainder by either signed zero raise a numeric fault. A non-finite arithmetic result also raises a numeric fault. Finite subnormal results and underflow to signed zero are allowed.
- Postfix `++` and `--` are statements only in v0.1, not general expressions (`x = y++` invalid); compound assignments are statements too.

## 6. Functions and multiple returns

```fsig
fn powered(side) {
    strength, err = redstone.read(side)
    if err is not nil {
        return false, err
    }
    return strength > 0, nil
}

on redstone.rising("left") {
    active, err = powered("right")
    if err is not nil {
        log.warn(err)
        return
    }
    if active { print("powered") }
}
```

Named functions are top-level (methods inside classes). First-class functions, closures and nested function declarations are deferred. `return` and falling off the end produce zero results. `return x` produces one result and `return x, y` produces two. Each return expression is scalar. A scalar call context requires exactly one result. A standalone call statement discards any results. Function recursion consumes budget and call depth. Host functions document exact return arity; expected failures typically return `(value, err)`.

**Host results.** Two rules apply to a host function whose results are `(value, err)`:
- In a scalar context, such as `x = gate.count(Entity.HOSTILE)` or `print(station.weather())`, it yields the value. If it fails, it stops the handler with a `HOST_FAILURE` fault that includes the host's message and error code.
- As a standalone call statement whose results are discarded, a failure is not silent: the first failure at each call site in an invocation writes one `Warning: …` line to the program output, within the output allowance.

Explicit `value, err = f()` is unchanged. User functions keep the exact-arity rule. See [ADR 0016](../decisions/0016-native-events-and-device-references.md).

In `a, b = foo()`, a single direct call supplies exactly two results. A list of right-hand expressions supplies one scalar per expression, evaluated left to right. To destructure a single returned collection, use `a, b = (foo())`. List and tuple destructuring require exactly one element per target. Dictionary destructuring uses distinct bare identifier targets whose names match the key set exactly. Each value is selected by key. The complete result count or collection shape is checked before writing any targets. Plain assignment writes targets left to right. A repeated target name denotes the same binding.

## 7. Lists, tuples, dictionaries and JSON

```fsig
nums = [1, 2, 3]
nums.append(4)
nums[0] = 10

coords = (10, 64, -2)
x, y, z = coords
onlyOne = (42,)

player = {
    name: "Alex",
    score: 15,
    active: true,
}
player.score++
player["score"] = 20

payload = json.stringify(player)
parsed, err = json.parse(payload)
```

- Lists are mutable ordered sequences. Tuples are immutable ordered sequences; grouping `(x)` is not a tuple, singleton `(x,)` is.
- Dictionaries are mutable insertion-ordered **string-key** maps in v0.1. Bare keys in literal `{ name: "Alex" }` are string names, not identifier lookups. Quoted keys permit `"minecraft:id"`. Arbitrary key types deferred.
- Property sugar `obj.name` on dictionaries resolves the string key `"name"`; missing keys are a defined error (not silently `nil`). Class instance members use declared fields.
- Indexing is zero-based. Out-of-bounds indices and missing dictionary keys are runtime faults. Tuples have immutable slots. Loops snapshot list and tuple elements or dictionary keys in iteration order. See [collection operations](../decisions/0006-native-collections.md).
- `json.stringify` accepts JSON-compatible values only (`nil`, bool, finite number, string, arrays/lists, string-key dicts); tuple/class/enum require explicit conversion, or error. Both JSON functions return `(value, nil)` on success or `(nil, error)` on invalid data. JSON input and output are bounded. Duplicate JSON keys, cycles and values outside the JSON model are rejected.
- **TSON/TOON is not the internal value representation.** May be added as a separate serializer if an exact format and need are established. More compact tokens do not automatically imply faster execution.

## 8. Classes, enums, methods

```fsig
class Motor {
    speed = 0
    running = false

    Motor(initialSpeed) {
        self.speed = initialSpeed
    }

    fn accelerate(amount) {
        self.speed += amount
        return self.speed
    }
}

m = Motor(10)
m.accelerate(5)
```

Class fields are declared in the class body; initializers run per instance. Constructor is named exactly after the class. `self` is bound in methods/constructor. Classes can hold list/dict fields. A class may declare one constructor. A missing constructor implies a zero-argument default. Field defaults execute in declaration order before the constructor. A constructor may return early without values. Function overload-by-type, class inheritance, static methods, reflection and operator overloading are excluded from 0.1. Enums are unique immutable values, not plain strings:

```fsig
enum State { OFF, ON }
state = State.ON
state is "ON" // false
```

The JVM can collect unreachable interpreter objects; per-controller memory quotas and persistent object-graph limits are separately required.

## 9. Control flow and events

Support `if`, `else if`, `else`, `while`, `for item in collection`, `break`, `continue`, `return`.

```fsig
on startup {
    print("Booted")
}

on timer.every(20) {
    for p in players.nearby(8) {
        print(p.name)
    }
}

on redstone.rising("left") {
    print("Signal!")
}
```

**Event declarations** only at top level. Event names are dotted host-defined names; event filter arguments are literals or supported constant expressions resolved during program activation.

**Handler values.** When the host declares an event, its signature has filter slots and named values.
- The leading parenthesized arguments, up to the filter count, are filters. The rest must be plain names and become handler parameters bound to the event's values in order. A handler may declare fewer values than the event provides. A parameter shadows outer names and cannot be called `event`.
- In hosts that declare no events, every argument is a filter, as before. A registry that declares events rejects unknown event names.
- `timer.every(ticks)`, `startup` and `shutdown` are always declared.

**Device constants.** A host function marked as a *reference* may be called in a file constant initializer, for example `const gate = detectors.named("gate")`.
- It must take constant arguments, return exactly one value and never fail.
- `on gate.entityEnter(entity)` handles an event of the constant's host object type. The type is known statically from the reference function's declared result, so an unknown event is a compile-time diagnostic.
- At activation the subscription records the constant's handle, and the host delivers events to that subscription directly.

**Event values.** The `event` binding has `name`, `arguments`, `payload` (the first value, or `nil`) and `values`.
- Values may be scalars, or lists, tuples and dictionaries of them: at most 512 values, 8 levels deep and 16,384 characters.
- Each delivery is an independent copy.
- Host handles, functions and instances cannot be delivered, and filters stay immutable scalars. Events are queued and processed sequentially; server tick scheduling is fair and bounded. `20` ticks ≈ 1 real second only when server is at 20 TPS. Redstone feedback must never re-enter the interpreter synchronously.

`on player.enter(8)` is a proposed host event, not a guarantee that an event is available before Minecraft integration. A handler receives the `event` binding with `name`, `arguments`, and `payload`. Its subscription filters are evaluated at activation. The standalone simulator runs lifecycle, timer and custom events and offers synthetic redstone input. See [controller runtime](../engineering/STANDALONE-RUNTIME.md).

## 10. Errors

Expected host failures return `(value, err)` where `err is nil` denotes success and non-nil `error` has stable `code` and `message` attributes. Runtime faults (type mismatch, undeclared read, budget exhaustion, call-depth exceeded, invalid indexing) abort the **current handler** and produce a diagnostic. No implicit exception swallowing or automatic `(value,err)` for programming faults. No `try/catch` in 0.1.

```fsig
ok, err = world.setBlock(pos, "minecraft:stone")
if err is not nil {
    log.error(err.message)
    return
}
```

## 11. Persistence and capability boundaries

- Each controller has independent `global` state; handlers share it. Script edits must not silently discard globals. Default initializers apply when a saved value is missing. Incompatible values follow the migration or activation-error rule in §3.
- Host handles are transient. Their scope and resource identity compare by value, and the host revalidates availability at use. Store an alias or another documented serializable identifier and resolve it again. See [host objects](../engineering/HOST-OBJECTS.md).
- Persist explicit supported values with a versioned format, bounded bytes and maximum graph depth. Persistent snapshots preserve shared references and cycles through mutable storage. See [versioned persistence and migration](../engineering/PERSISTENCE.md).
- Scripts do not access arbitrary Java/Minecraft objects. Host APIs validate permission, radius, chunk load state, world changes, resource costs and server thread.
- On budget exhaustion stop the handler; do not auto-resume arbitrary AST frames in v0.1. External world effects already performed are **not** automatically rolled back.

## 12. Excluded / deferred features

Dynamic imports, general-purpose IO, class inheritance, closures, coroutines, bytecode VM, custom extension packages loaded by script, reflection, arbitrary networking, unconstrained world mutation, global state shared across controllers, type-driven overload resolution. These can be considered later with explicit requirements and tests.

## 13. Implementation order

Parser correctness → resolver → primitive interpreter → control flow/functions/returns → collections → enums/classes → budget system → events/mock host → globals persistence → Fabric adapter.
