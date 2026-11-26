# ADR 0007 — Class and annotation execution

**Status:** Accepted for Stage 5B.

Class fields initialize in declaration order on a fresh instance. Initializers see file bindings and `self`. Reading a field before its initializer faults. The constructor runs after field initialization. A class has at most one constructor under the 0.1 rule and uses a zero-argument default when it declares none. Constructors may return early without values. Fields and methods share a namespace, and instance equality is identity. Enum members are immutable and canonical within the activated program.

The runtime enforces annotations on bindings, parameters and fields. Primitive names are reserved as type names, and class and enum types name declarations in the file. An annotated local is an explicit declaration. Return annotations await a syntax decision. Runtime values can nest instances, enum members and collections.
