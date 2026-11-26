# Stage 5C resource accounting

An invocation has a work budget, call and evaluator depth limits, and cooperative cancellation at work checkpoints. Native operations charge for visited nodes, copied slots and string units. Each runtime has a configurable logical data allowance. At invocation start the runtime measures live global and argument graphs by identity. During execution it reserves units for new values, storage and bounded intermediate work. Temporary allowances reset at the next invocation. This conservative model can reject a computation that would fit after collection by the JVM.

Defaults are 100,000 operations, 64 call frames, 262,144 logical data units, 256 output lines and 32,768 UTF-16 output units per invocation. Collections cap at 4,096 entries, concatenation at 1,048,576 UTF-16 units, and JSON at 65,536 input/output units and 64 nested levels. A fault aborts the invocation and returns one diagnostic and a bounded call trace. Previously completed writes remain in the runtime.

Logical units describe script data and work. Java object headers, interpreter implementation objects, source and AST storage, external host state and GC timing have separate JVM costs. Host code must be trusted, bounded and nonblocking because cancellation cannot preempt a Java callback.
