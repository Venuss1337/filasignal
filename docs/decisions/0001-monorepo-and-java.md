# ADR 0001 — One repository, Java interpreter, Fabric adapter

**Status:** Proposed, awaiting initial implementation validation  
**Date:** 2026-11-22

## Context

FilaSignal contains a standalone custom scripting language, CLI, Minecraft Fabric mod, docs and later a website. The language runtime will execute many scripts concurrently in Minecraft's simulation sense, must be safe and testable, and must have inexpensive integration with Java/Fabric APIs.

## Decision

Use **one monorepo** and **Java 25** for the interpreter. Use Gradle multi-project builds for Java modules, with pure-Java `filascript-core`, `filascript-cli`, and `filasignal-fabric`. The web app is a separate Astro workspace later. No native sidecar, JNI or Rust runtime initially.

## Rationale

- No JVM ↔ Rust FFI/IPC bridge, platform-specific native binaries or process supervision.
- JUnit tests for core without Minecraft; immediate access to controlled Java host adapters.
- JVM garbage collection handles unreachable script instance implementations, although **not** controller-specific memory quotas (we implement those).
- Gradle multi-project builds have clear dependencies and separate compilation units.
- Splitting into repos prematurely would complicate versioning and simultaneous changes to language/host contracts.

## Consequences

- Gradle and Fabric version selection must be verified from official docs during bootstrap.
- A pure Java interpreter is not magically sandboxed: explicit budgets, resource accounting and host API validation are mandatory.
- Memory allocations and GC effects need measurement, and any actual bottleneck should be profiled before optimizing to bytecode or native code.

## Revisit if

Language consumers outside Minecraft grow independently or profiling demonstrates a persistent bottleneck that cannot be addressed through interpreter implementation and scheduling.
