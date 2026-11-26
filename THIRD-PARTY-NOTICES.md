# Third-party notices

FilaSignal is licensed under the GNU General Public License v3.0 only (`GPL-3.0-only`); see [LICENSE](LICENSE).
This file lists third-party software that is redistributed with FilaSignal, or that the project depends on, and
their licenses. Each component keeps its own license and copyright.

## Bundled in the FilaSignal mod JAR

The mod JAR nests LibGui, which itself nests Jankson and LibNinePatch. They are included unmodified.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| LibGui | 18.0.1+26.3-rc-2 | MIT | https://github.com/CottonMC/LibGui |
| Jankson (Fabric wrapper and library) | 14.0.0+j1.2.3, Jankson 1.2.3 | MIT | https://github.com/falkreon/Jankson, https://github.com/CottonMC/Jankson-Fabric |
| LibNinePatch | 1.2.0 | MPL-2.0 | https://github.com/Juuxel/LibNinePatch |

LibNinePatch is distributed under the Mozilla Public License 2.0 in unmodified binary form. Its source code is
available at the address above, and the full license text is included in its JAR and at
https://www.mozilla.org/MPL/2.0/.

### LibGui

MIT License

Copyright (c) 2018-2021 The Cotton Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### Jankson

The Jankson-Fabric wrapper declares the MIT license with authors Falkreon and The Cotton Project. The Jankson
library's license:

MIT License

Copyright (c) 2018-2025 Falkreon (Isaac Ellingson)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Required at runtime, not bundled

| Component | License | Notes |
| --- | --- | --- |
| Minecraft | Mojang Studios / Microsoft EULA | Not distributed. Players need their own copy. |
| Fabric Loader | Apache-2.0 | Installed by the player. |
| Fabric API | Apache-2.0 | Installed by the player. |
| Java 25 runtime | Depends on the distribution | Installed by the player. |

## Build, test and documentation tools

Gradle (Apache-2.0), Fabric Loom (MIT), JUnit 5 (EPL-2.0), Astro (MIT), Starlight (MIT), starlight-sidebar-topics
(MIT), Pagefind (MIT) and sharp (Apache-2.0) are used to build, test or publish the project. Only the published
documentation site contains code from Astro, Starlight and Pagefind; the mod and the CLI do not.

## Images

The block and item textures and models were drawn by `tools/assets/generate.py`, which is part of this repository,
and are distributed under the project license. They use no third-party assets.

The crafting recipes on the documentation website show Minecraft's own item textures (iron ingot, redstone dust and
the other ingredients). They are not stored in this repository: `web/scripts/minecraft-textures.ts` extracts them
from the official Minecraft client when the site is built. They belong to Mojang Studios and are not covered by this
project's license.

Screenshots in the documentation and README show the game and therefore also contain Minecraft's own textures and
interface. Those parts belong to Mojang Studios and are used as gameplay screenshots under the Minecraft Usage
Guidelines; they are not covered by this project's license.
