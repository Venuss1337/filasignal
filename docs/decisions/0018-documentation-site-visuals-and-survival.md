# ADR 0018 — Documentation site, visual identity and survival play

Status: accepted for Milestones 16 and 17.

## Context

FilaSignal's language and hardware were complete enough for a first public release, but the mod looked like colored
placeholder cubes, could not be obtained in survival, and its documentation lived in internal engineering notes.

## Decision

### Documentation site

- **One site, two areas.** `web/` is an Astro + Starlight site with its own Bun lockfile. `/language/` documents
  FilaScript as a standalone language with the CLI; `/minecraft/` documents the mod. The `starlight-sidebar-topics`
  plugin gives each area its own sidebar and a switcher. This replaces the earlier proposal of `/filascript/` and
  `/filasignal/` routes: plain words are clearer for beginners.
- **Generated reference, written guides.** The Minecraft API reference renders from
  `web/src/data/generated/minecraft-api.json`, which `DocumentationGameTests` exports from the host registries that
  programs run with. Recipes are read from the mod's recipe files and examples from `examples/` at build time. Guides and
  tutorials are written by hand.
- **Checked examples.** Language examples are parsed, resolved and run by `DocumentationExamplesTest`, and their
  `Output` blocks must match. Minecraft examples are resolved against the registry of their declared target by the
  GameTest. A stale API catalog fails the GameTest.
- **GitHub Pages.** `site` and `base` target `https://venuss1337.github.io/filasignal/`. A rehype plugin adds the base to
  root-relative content links, and `scripts/check-site.ts` verifies the built output. `.github/workflows/docs.yml`
  builds pull requests and deploys only `main`.

### Visual identity

- **Generated pixel art.** `tools/assets/generate.py` defines one palette and casing and draws every 16×16 texture,
  model, blockstate and item definition. The output is committed; the mod build never runs Python.
- **Full cubes with fronts.** Devices stay full cubes. Devices with a front face gain a horizontal `facing` property
  (Entity Detector, Weather Station, Display Module, Storage Server, Control Panel, Wireless Bridge, Access Controller).
  Devices whose faces are named by world direction (Redstone Controller, Inventory Module, Network Interface) and
  non-directional devices look the same on every side; the Redstone Controller's top shows north.
- **The computer model.** A cuboid retro desktop (keyboard, case with drive slot, CRT monitor) with a matching
  collision shape, `noOcclusion`, and states `facing`, `status` (`off`, `running`, `error`) and `drive`.
- **Visual states only reflect real state.** The scheduler queues a computer's indicator and applies it once per tick,
  never during chunk loading or unloading. The Access Controller's `open` follows its powered outputs and resets on
  load. The alarm keeps its existing `lit` state.
- **Saved worlds.** Block IDs are unchanged. States saved before these properties existed load with defaults.

### Display Module text

A `BlockEntityRenderer` draws the stored text on the front face, scaled to fit, laid out once per change and skipped
for other hardware. The block entity's update tag contains only a Display Module's text, and the server sends it only
when the text changes, so no other device data reaches clients.

### Survival

- `tools/recipes.py` generates shaped recipes, recipe-book unlock advancements and the `mineable/pickaxe` tag. Early
  hardware uses Overworld materials; advanced hardware asks for comparators (Nether quartz), ender pearls and amethyst.
  The repeater recipe yields two.
- The `mineable/pickaxe` tag fixes a defect: blocks copied iron's "requires correct tool" property without being
  mineable by any tool, so they dropped nothing in survival.
- Destruction semantics are unchanged. The Storage Server now warns when a player starts breaking one that holds data.

### In-game help

Item tooltips describe each block. Device forms, the dashboard and the editor have a `?` button that opens the matching
page through Minecraft's link confirmation screen. Starters are filtered to the editing target, and two drive starters
were added. A new computer's default program declares no globals, so a beginner's first program applies without the
fresh-globals confirmation. Screens taller than the window scroll.

## Consequences

- The documentation cannot silently drift from the registries or the language implementation, at the cost of a
  generated JSON file in the repository and a GameTest run to refresh it.
- The art can be changed in one place and regenerated. Blockbench can open the computer model, but edits must be moved
  back into the generator.
- Pull request builds do not run the Minecraft GameTests in CI; the Minecraft examples and catalog are checked locally
  with the GameTests until a Java CI workflow exists.
