# Development tools

## Verification and benchmarks

PowerShell scripts that write their results to `verification-results/` or `benchmark-results/` (both ignored by Git).
See [testing](../docs/engineering/TESTING.md#verification-entry-points) and [benchmarks](../docs/engineering/BENCHMARKS.md).

| Script | Does |
| --- | --- |
| `verify/Verify-All.ps1` | The automated checks, `-Level quick` or `full`, with one summary. |
| `verify/Check-Package.ps1` | Checks the mod JAR and the installed CLI. |
| `verify/Start-CleanServer.ps1` | A dedicated server with only the packaged JAR, in a new folder; `-Smoke` tests it headless. |
| `verify/Repeat-GameTests.ps1` | Repeats the server GameTests to find intermittent failures. |
| `benchmarks/Run-Benchmarks.ps1` | Server and language benchmarks by profile. |

## Asset generators

Small scripts that generate committed resources. Building the mod never runs them. They need Python 3.10+ and
[Pillow](https://python-pillow.org/); `preview.py` also needs NumPy.

| Script | Generates |
| --- | --- |
| `assets/generate.py` | Every block and item texture, block model, blockstate and item definition in `filasignal-fabric/src/main/resources/assets/filasignal`. The shared palette, casing and each device's face are defined here. |
| `assets/preview.py OUT --sheet` | Renders of every model for review: an orthographic three-quarter view with Minecraft-style shading. `--site` writes `web/public/renders`. These are asset renders, not in-game screenshots. |
| `recipes.py` | Crafting recipes, their recipe-book unlock advancements, the `mineable/pickaxe` block tag and `web/src/data/recipe-tiers.json`. |

Run them from the repository root, for example `python tools/assets/generate.py`. The computer's model JSON can also
be opened and edited in [Blockbench](https://www.blockbench.net/) as a Java block model; if you edit it there, move the
change back into `generate.py` so the next generation keeps it.

All art is original to this project and uses the repository's MIT license. No third-party assets are used.
