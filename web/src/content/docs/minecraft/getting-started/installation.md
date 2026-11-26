---
title: Installation
description: Supported Minecraft and Fabric versions, what to install on a client and a dedicated server, and how to build the mod.
sidebar:
  order: 1
---

## Supported versions

| Component | Version |
| --- | --- |
| Minecraft: Java Edition | 26.3 only |
| Fabric Loader | 0.20.1 or newer |
| Fabric API | 0.166.2+26.3 or newer |
| Java | 25 or newer |

FilaSignal includes the other libraries it needs (the LibGui screen library and the FilaScript language core). It
does not run on Forge, NeoForge, Quilt-only setups, Bedrock Edition or other Minecraft versions.

## Download

Download `filasignal-1.0.0+mc26.3.jar` from the
[GitHub release](https://github.com/Venuss1337/filasignal/releases/latest), which also lists its SHA-256 checksum.
Download FilaSignal only from the official GitHub repository or the listings linked from it.

## Install on your computer (client)

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.3 with the official installer.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3 and the FilaSignal jar into the `mods` folder of
   your Minecraft directory.
3. Start Minecraft with the Fabric profile. The FilaSignal creative tab and the recipes appear in new and existing
   worlds.

## Install on a dedicated server

1. Install the Fabric server launcher for Minecraft 26.3.
2. Put Fabric API and the FilaSignal jar into the server's `mods` folder.
3. Start the server.

**Every player who joins needs FilaSignal installed too**, with the same version as the server. FilaSignal adds
blocks, items and screens, so a client without it, or with a different build, cannot join or will be disconnected. See
[multiplayer and servers](/minecraft/guides/multiplayer-and-servers/) for administration.

## Build it yourself

You need Git and a Java Development Kit 25.

```sh
git clone https://github.com/Venuss1337/filasignal.git
cd filasignal
./gradlew :filasignal-fabric:build
```

On Windows PowerShell, use `.\gradlew.bat`. The mod file is
`filasignal-fabric/build/libs/filasignal-<version>+mc26.3.jar`. Developers can also start a test client or server
from the repository with `./gradlew :filasignal-fabric:runClient` or `./gradlew :filasignal-fabric:runServer`.

## Updating and worlds

Programs, drives and storage are saved in versioned formats so that later versions can read them. Back up your world
before updating any mod. Removing FilaSignal from a world deletes its blocks and their data.

Next: [build your first automation](/minecraft/getting-started/first-automation/).
