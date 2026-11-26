# Changelog

All notable changes are documented here. Versions follow [Semantic Versioning](https://semver.org/): a major version
for incompatible changes to programs, saved worlds or the FilaScript language, a minor version for new features, and
a patch version for fixes. Add changes under **Unreleased** in the same pull request, grouped as Added, Changed,
Fixed or Removed.

## Unreleased

## 1.0.0 - 2026-11-26

The first public release, for Minecraft 26.3 with Fabric.

### FilaScript

- A standalone, event-driven scripting language: numbers, text, booleans, `nil`, lists, tuples, dictionaries,
  functions with multiple return values, classes, enums, optional type annotations, JSON and error values.
- Persistent `global` variables saved in a versioned, checksummed format, with clear errors instead of silent resets.
- Diagnostics with file, line and column; execution budgets for operations, call depth, data and output.
- A command-line interpreter (`filascript-cli run` and `ast`) that runs programs without Minecraft.

### Minecraft mod

- The Programmable Computer with an in-game editor: highlighting, completion, starter programs, validation,
  revision-safe saving, and a debugger with breakpoints, stepping, variables and a console.
- Hardware: Network Interface, Sensor, Network Repeater, Entity Detector, Weather Station, Environment Sensor, Redstone
  Controller, Inventory Module, Display Module, Alarm Module, Storage Server, Access Controller, Control Panel and
  Wireless Network Bridge, linked with the Linking Tool.
- Native hardware events (`on gate.entityEnter(entity)`) and named constants, plus custom events between programs
  with server-verified senders.
- Autonomous devices: a Programmable Data Drive lets a device run its own program and keep its own storage.
- Shared Storage Servers, device aliases, owners and trusted players, and server-side permission checks.
- Strict limits per handler and per server tick so programs cannot stall a server.
- Survival recipes for every block and item, original textures and models, a computer that shows its program state,
  and display text drawn in the world.
- Documentation website with a language guide, Minecraft guides, tutorials and a generated API reference.

### Known limitations

- Supports Minecraft 26.3 with Fabric only.
- The bundled LibGui is a release-candidate build for Minecraft 26.3 (`18.0.1+26.3-rc-2`).
- The editor wraps long lines; there is no horizontal scrolling.
- At most 16 fresh entity scans run per server tick across all detectors; further scans in that tick return
  `detector.busy`. Programs that scan on the same timer should stagger their timers or handle the error.
