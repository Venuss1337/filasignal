# Release acceptance

The checks a person runs before a FilaSignal release, in order. Automated checks come first, because the manual ones
assume a build that already passes them. Run it for every release.

**Priority:** **R** = required before a public release, **S** = should be done, **O** = optional.

## Recording evidence

Create `verification-results/manual-<date>/` (ignored by Git) and keep a `NOTES.md` there with one line per check:

```text
| Check | Result (pass / fail / skipped) | Evidence | Notes |
| 3.2 Clipboard round trip | pass | editor-clipboard.png | GUI scale 3 |
```

Put screenshots (F2 saves them to the instance's `screenshots/` folder) and copies of relevant logs in the same
folder. Record the commit (`git rev-parse --short HEAD`), operating system, Java version and, for visual checks, the
GUI scale and resolution. A check that was not performed is *skipped*, never *pass*.

## 0. Automated gate (R)

Set `JAVA_HOME` to a JDK 25 (for example `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-25.0.5+8"`), then from the
repository root:

```powershell
.\tools\verify\Verify-All.ps1 -Level full -Client
.\tools\verify\Start-CleanServer.ps1 -AcceptEula -Smoke   # or -ReuseEulaFrom <an eula.txt you accepted>
.\tools\benchmarks\Run-Benchmarks.ps1 -Profile normal
```

Expected: `verification-results/verify-<timestamp>/SUMMARY.md` says *all steps passed* (core, Fabric unit tests,
build, package, server GameTests, documentation site, client GameTests). The clean server's `SUMMARY.md` passes every
check. The benchmark summary says *All parts completed* with no faults outside `stress` and `saturated`.
`-AcceptEula` means you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) for that new test folder.

Intermittent GameTest failures: run `.\tools\verify\Repeat-GameTests.ps1 -Runs 10 -Trace` and attach its
`SUMMARY.md`. See [testing](engineering/TESTING.md#intermittent-failures).

## 1. First-time player (R)

Use a **clean Minecraft instance**, not the development run directory, so you see what a new player sees.

1. Install Fabric Loader 0.20.1 for Minecraft 26.3 with the [official installer](https://fabricmc.net/use/installer/)
   into a new launcher profile with its own game directory.
2. Put Fabric API 0.166.2+26.3 and `filasignal-fabric/build/libs/filasignal-<version>+mc26.3.jar` in that profile's
   `mods` folder. LibGui and the FilaScript core are inside the FilaSignal JAR.
3. Start the game. Expected: it reaches the title screen, and the Mods list (if Mod Menu is present) or the log shows
   FilaSignal with its description.
4. Create a new creative world. Follow the site's *Installation* and *Your first automation* pages word for word,
   using only what the page says. Expected: the intruder alarm works at step 9. Note every place where the page and the
   game disagree or you had to guess.
5. Press **?** on the dashboard, a device form and the editor. Expected: the confirmation screen shows a link to the
   matching page, and the browser opens it. (Requires the site to be deployed, see section 7.)

## 2. Survival (R)

In a new survival world (`/gamemode survival` in the same world is acceptable if noted):

1. Open the recipe book and craft every FilaSignal block and item from it. Expected: each recipe unlocks once you have
   its key ingredient, and early hardware needs only Overworld materials.
2. Mine each block with a stone pickaxe, then with your hand. Expected: a pickaxe mines it at normal speed and it
   drops itself; by hand it is slow and drops nothing.
3. Store data in a Storage Server, then start breaking it. Expected: a warning that it holds data.
4. Insert a drive into a device, run a starter program and blow the device up with TNT. Expected: the drive drops as an
   item and keeps its program and data when inserted elsewhere.

## 3. Editor and interface (R for 3.1 to 3.4, S for the rest)

Test at GUI scales 2, 3 and 4 and *Auto*, and at the smallest window you can make.

1. Type, select with the mouse across wrapped lines, use Home/End, Ctrl+A/C/V/X/Z and Ctrl+S. Expected: the caret and
   selection match the text; Save & Apply runs.
2. Paste from another program, and copy from the editor into another program. Expected: identical text, including
   non-ASCII characters.
3. Type with an IME (for example Japanese or Chinese input). Expected: composition works and the text is committed.
4. Write an error, select it in the debug panel, then set a breakpoint, pause, step and resume. Expected: the
   selection jumps to the line; the panel stays inside the window at every scale.
5. Open completion menus. Expected: they stay inside the window and accept with Enter and Tab.
6. Open a device form and the dashboard with 30 or more devices on one network. Expected: both scroll and nothing is
   clipped.
7. Open the forms of the sensor, redstone controller, inventory module, weather station, alarm, access controller and
   bridge. Expected: every field is reachable and saves.
8. Restart a dedicated world and reopen the editor. Expected: the last saved source, a fresh revision.

## 4. Autonomous devices (R)

1. Insert a drive into an Entity Detector and load the *Visitor counter* starter. Walk in and out. Expected: the
   count rises; `/filasignal drives` lists the drive.
2. Put a second drive in an Alarm Module with the *Guard alarm* starter, listening to the detector. Expected: the
   alarm rings when someone enters.
3. From the computer's dashboard, open the detector's program remotely, change it and apply. Expected: the device
   runs the new revision.
4. Eject and reinsert the drive; save, quit and reload the world. Expected: the program and its storage survive.
5. Unload the area (walk 300 blocks away and wait), then return. Expected: the programs resume; nothing duplicates.

## 5. Visuals and sound (S)

1. Look at every block from 2, 10, 32 and 64 blocks, in daylight, at night and underground. Expected: recognizable
   faces, no missing textures (magenta-black), no z-fighting.
2. Display Module text: readable at 5 blocks, shrinks to fit long text.
3. The computer shows *off*, *running* and *error*; the Access Controller shows *open*; the alarm glows while ringing.
4. Listen to the alarm's four sounds and the Access Controller's granted and denied feedback. Expected: audible, not
   repeating every tick.
5. Measure FPS as in [benchmarks](engineering/BENCHMARKS.md#client).

## 6. Dedicated server with two players (R)

Start a clean server with the packaged JAR. `-AcceptEula` means you accept the EULA for this new folder:

```powershell
.\tools\verify\Start-CleanServer.ps1 -AcceptEula
```

It runs in the console; type `stop` to end it. The world is `filasignal-clean` inside the new
`verification-results/clean-server-<timestamp>/` folder.

**Clients.** The proper test is two authenticated Minecraft accounts, each with the packaged mod as in section 1,
connecting to `localhost` (or the server's LAN address). With one account, you can instead set `online-mode=false`
in that test folder's `server.properties` (never on a public server) and start two development clients in two
terminals: `.\gradlew.bat :filasignal-fabric:runClient --args="--username Owner"` and the same with
`--username Visitor`. Start the second after the first has finished building.

Call the players *owner* and *visitor*:

1. Owner places a computer, repeater, Entity Detector, Display Module, Control Panel and Access Controller and links
   them. The visitor tries to open each one's form and the computer. Expected: the visitor gets no form, no source and
   no device details.
2. Owner makes the Control Panel public. Expected: the visitor can press its buttons and sees only the buttons and
   status, not the access list or program state.
3. Owner adds the visitor to the Access Controller's list; the visitor uses it with a door. Expected: it opens for the
   visitor, and stays closed for a player who is not on the list.
4. Shared editing and debugging:
   - Owner grants the visitor trust. Both open the computer's editor and make different drafts. Owner saves first;
     the visitor then applies an older draft. Expected: a conflict message, the visitor's draft kept, the server
     source intact.
   - Both open the debug panel. A breakpoint set by one appears for the other; each can pause, step and resume.
   - Owner revokes trust while the visitor's editor and debug panel are open. Expected: the visitor's panel closes
     and Validate, Save, Reload and the execution controls are refused.
   - Remove the computer while an editor is open: protected operations fail. Disconnect with a draft open, reconnect
     and check the last saved source.
   - Apply a program with a persistent counter, stop the server and start it again. Expected: the source and the
     counter survive, and the editor receives a fresh revision.
5. While the visitor is not trusted, have them stand 40 blocks away and try the owner's devices; then trust them and
   repeat. Expected: refused while distant either way.
6. Watch the server console during all of the above. Expected: no errors or exceptions; refused requests are not
   spammed.

## 7. Documentation (R)

1. `cd web; bun install --frozen-lockfile; bun --bun run build`. Expected: the build and the site checker pass.
2. `bun --bun run preview` and open the printed address. Read the home page, installation, first automation and three
   tutorials. Expected: working navigation, search, light and dark themes, readable code and screenshots.
3. After GitHub Pages is enabled and `main` is deployed, open `https://venuss1337.github.io/filasignal/` and repeat a
   few pages and a `?` link from the game.

## 8. Final report

Summarize sections 0 to 7 in `NOTES.md`: which checks passed, failed or were skipped, and the issues found, each
classified as blocking or not. Link to it from the release pull request.
