# Toolchain setup

How to install the tools in ADR 0002 so Claude can use them. Versions were pinned on 2026-10-18; change a pin only on purpose, and record why in the ADR. Commands are for Windows (PowerShell or Git Bash), with macOS and Linux notes where they differ.

**Order.** Do steps 1–6 before M1, in about half a day. Steps 7–9 can wait until their milestone. Step 11 checks everything.

| Step | Tool | When | Time |
|---|---|---|---|
| 1 | IntelliJ IDEA and its MCP server, plus the Claude Code plugin | M0 | 20 min |
| 2 | JetBrains Runtime 25 and hot reload | M0 | 15 min |
| 3 | MCTest (Claude plays the dev client) | M0 | 20 min |
| 4 | Dev-instance mods: spark, JEI, WorldEdit | M0 | 10 min |
| 5 | Aseprite and aseprite-mcp | M0 | 20 min |
| 6 | Datapack editing: misode and Spyglass | M0 | 5 min |
| 7 | RenderDoc (Vulkan frame captures) | M1, with kfx | 20 min |
| 8 | Two clients, a server and clumsy (latency test) | M1, R6-05 | 30 min |
| 9 | Blender Lab MCP (KHR-1 player rig) | M1, R6-02 | 20 min |
| 10 | Chunky and BlueMap (worldgen), LDLib2 spike | M5, or when Champ approves the spike | 20 min |
| 11 | Check that Claude can use each tool | after setup | 10 min |
| 12 | Code quality gates: Error Prone, NullAway, Spotless, dependency verification (nothing to install) | M1 (D-033) | 5 min |

---

## 1. IntelliJ IDEA's MCP server
1. Install IntelliJ IDEA 2026.2. The free core is enough; since 2025.3 the Community and Ultimate editions are one download.
2. Open the project and let Gradle sync. ModDevGradle generates an IntelliJ run configuration for each run in `build.gradle` (client, server, GameTest server); Claude starts them through the MCP server.
3. **Settings → Tools → MCP Server:** turn on **Enable MCP Server**, then use **Auto-Configure** for **Claude Code**. Leave **Brave mode** off, so run configurations and terminal commands still ask first.
4. In Claude Code, run `/ide` once. That installs the Claude Code JetBrains plugin (0.1.14-beta) and connects it, which adds `mcp__ide__getDiagnostics`.
5. Check: ask Claude to list the run configurations and the problems in one file.

The third-party "IDE Index MCP" and "Debugger MCP" plugins are optional; the built-in server already has diagnostics and debugger tools (ADR 0002).

## 2. JetBrains Runtime 25 and hot reload
1. Download JBR 25 (`jbrsdk`, 25.0.3-b496.62 or newer) from https://github.com/JetBrains/JetBrainsRuntime/releases. In IntelliJ, **File → Project Structure → SDK → Add JDK** and point it at the folder.
2. Make it the project SDK and the Gradle JVM (**Settings → Build Tools → Gradle → Gradle JVM**).
3. Add this to `build.gradle`. Other JVMs ignore the flag because of the first one:
   ```groovy
   neoForge {
       runs {
           configureEach {
               jvmArguments.addAll("-XX:+IgnoreUnrecognizedVMOptions", "-XX:+AllowEnhancedClassRedefinition")
           }
       }
   }
   ```
4. Debug `runClient` from IntelliJ. After an edit, **Build → Recompile** (Ctrl+Shift+F9) reloads the class. Method bodies, new fields and changed signatures reload; registries, mixins and anything that runs once at startup need a restart.

## 3. MCTest: Claude plays the dev client
MCTest is a dev-only mod plus an MCP server. Through it Claude:
- takes screenshots;
- presses keys, clicks GUIs, moves, mines and fights;
- runs commands and GameTests;
- reads logs and crash reports;
- freezes the world and steps it one tick at a time.

It binds to `127.0.0.1` only and never goes into the jar.

1. Install Node 22 or newer.
2. Add MCTest to `build.gradle` (the full block, including step 4's mods, is in [The build.gradle block](#the-buildgradle-block) below).
3. `.mcp.json` already registers `mctest-mcp@0.3.0`. Approve it when Claude Code asks. On native Windows, if it doesn't start, change that entry to `"command": "cmd", "args": ["/c", "npx", "-y", "mctest-mcp@0.3.0"]`, as for the Blockbench MCP.
4. Check: ask Claude to "launch the client with MCTest (task `runClient`) and take a screenshot". `mc_launch` requires a `loader` and defaults to multi-loader task names (`:neoforge:runClient`), so Claude passes `loader: "neoforge", task: "runClient"` for this single-project build (the same for `mc_server_launch` with `runServer`). Gradle output goes to `build/mctest-logs/`. Claude calls `mc_close_all` when it's done.

**How the kit uses it:**
- the GameTest suite through `mc_gametest_run`;
- Lens frames next to MCTest screenshots;
- `mc_time_freeze` with `mc_time_step` to check frame data tick by tick;
- the dedicated-server tools (`mc_server_*` on port 25598) for desync tests.

## 4. Dev-instance mods
spark (profiling), JEI (it shows whether removed items are hidden) and WorldEdit (test arenas) load only in the dev runs, through the same `localRuntime` configuration. Chunky joins the block at M5 for step 10: its deprecated `logoFile` property makes NeoForge stop every launch on a loading-warning screen, so it stays out until it's needed. They come from Modrinth's Maven, pinned by version ID, because version numbers repeat across loaders. The project's `build.gradle` already has this block (2026-10-19).

### The build.gradle block
```groovy
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }        // MCTest
    exclusiveContent {                                                // Modrinth Maven, only for maven.modrinth:*
        forRepository { maven { url = 'https://api.modrinth.com/maven' } }
        filter { includeGroup 'maven.modrinth' }
    }
}

// Dev runs only; never in the jar. Harmless if the build already declares localRuntime (the NeoForge MDK does).
configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    // Pinned 2026-10-18 (ADR 0002).
    localRuntime("com.coolerpromc.mctest:mctest-neoforge-26.3:26.3.0.3") { transitive = false }
    localRuntime("maven.modrinth:spark:HwHxykP9")        // spark 1.10.187 (NeoForge 26.x)
    localRuntime("maven.modrinth:jei:wSzwAzVB")          // JEI 31.16.0.84, beta
    localRuntime("maven.modrinth:worldedit:ByRNkO83")    // WorldEdit 7.4.6
    // At M5 (step 10): localRuntime("maven.modrinth:chunky:EyCqftOK")   // Chunky 1.5.4
}
```
Check: `./gradlew dependencies --configuration runtimeClasspath` lists them, and `./gradlew build` produces a jar without them.

## 5. Aseprite and aseprite-mcp
1. Buy and install Aseprite 1.3.18 ($19.99), or build it from source.
2. Install Python 3.13 or newer and `uv` (`winget install astral-sh.uv`; on macOS `brew install uv`).
3. Clone the server at its pinned commit:
   ```bash
   git clone https://github.com/diivi/aseprite-mcp C:/tools/aseprite-mcp
   git -C C:/tools/aseprite-mcp checkout 90d1696a7e41edff89bbd0823ae6a5f86c114bcc
   ```
4. Register it for your user. The server reads `ASEPRITE_PATH` from its environment and doesn't load a `.env` file:
   ```bash
   claude mcp add aseprite --scope user -e ASEPRITE_PATH="C:/Program Files/Aseprite/Aseprite.exe" -- uv --directory C:/tools/aseprite-mcp run -m aseprite_mcp
   ```
5. Check: ask Claude to make a 16×16 indexed sprite with the sugi ramp (`art/palettes/world/sugi.gpl`), export a preview and run `python tools/vanilla_style.py` on the PNG.

Its raw Lua tool runs any script in Aseprite. That's fine on your machine; don't point it at folders outside the project.

## 6. Datapack editing
- **misode's generators** (https://misode.github.io): choose **26.3** in the version menu. Use them for advancements, loot tables, recipes and worldgen, and for any datapack JSON that `vanilla_scope.py` doesn't generate.
- **Spyglass** (VS Code extension "Datapack Helper Plus", 4.14.0): checks datapack JSON as you type. It has no command-line validator, so CI runs the kit's checks instead (`vanilla_scope.py --check`, the GameTests).

## 7. RenderDoc
1. Install RenderDoc 1.46 from https://renderdoc.org.
2. In the dev run folder, set `earlyWindowControl = false` in `run/config/fml.toml`; NeoForge's early loading window and Vulkan capture don't mix.
3. Add a GPU-debug client run that names render passes and turns on Vulkan validation:
   ```groovy
   neoForge {
       runs {
           clientGpu {
               client()
               programArguments.addAll("--graphicsBackend", "vulkan", "--vulkanValidation", "--renderDebugLabels")
           }
       }
   }
   ```
4. Launch it from RenderDoc. Either:
   - point **Launch Application** at the `java` command line IntelliJ shows for `clientGpu` (in the run configuration, **Show command line**); or
   - launch `gradlew.bat clientGpu --no-daemon` with **Capture Child Processes** on.

   Check the log for `Using graphics backend Vulkan`.
5. Press F12 (RenderDoc's default) to capture. To show Claude a frame, save the frame or the texture as PNG from the Texture Viewer, plus the Event Browser list, and give Claude both.

## 8. Two clients, a dedicated server and clumsy (the M1 latency test, R6-05)
1. Add a second client run with its own folder, name and MCTest port:
   ```groovy
   neoForge {
       runs {
           client2 {
               client()
               gameDirectory = project.file('run-client2')
               programArguments.addAll("--username", "Dev2")
               systemProperty("mctest.port", "25600")
           }
       }
   }
   ```
   Dev clients are named `Dev` by default. NeoForge replaces `#` in `--username` with random digits in dev, so `Dev###` also works.
2. Register a second MCTest server for that client, for this project only:
   ```bash
   claude mcp add mctest2 --scope local -e MCTEST_PORT=25600 -- npx -y mctest-mcp@0.3.0
   ```
3. Start the dedicated server with `runServer`. The first time, accept the EULA in `run/eula.txt`. Set `online-mode=false` in `run/server.properties`, because dev clients have no Mojang session.
4. Install clumsy 0.3 (https://github.com/jagt/clumsy/releases; run it as administrator). Set the filter to `outbound and (tcp.DstPort == 25565 or tcp.SrcPort == 25565)`, then **Lag** 75 ms and **Drop** 1%. WinDivert counts every loopback packet as outbound, so on one machine each direction gets 75 ms: a 150 ms round trip. On Linux, use `tc qdisc add dev lo root netem delay 75ms loss 1%` instead.
5. Claude then drives both clients (`mctest`, `mctest2`) and the server (`mc_server_*`) and compares what each side saw.

## 9. Blender Lab MCP (official)
Needed for the KHR-1 player rig: PAL animations come from the Emotecraft Blender rig.
1. Install Blender 5.2. The MCP needs 5.1 or newer.
2. Install the add-on: drag `mcp-1.0.3.zip` from https://www.blender.org/lab/mcp-server/ into Blender twice. The first drop adds the Blender Lab repository, the second installs the add-on. Start its server from the add-on's preferences, or turn on auto-start.
3. Clone the MCP server at the same version and register it:
   ```bash
   git clone https://projects.blender.org/lab/blender_mcp.git C:/tools/blender_mcp
   git -C C:/tools/blender_mcp checkout v1.0.3
   claude mcp add blender --scope user -- uv --directory C:/tools/blender_mcp/mcp run blender-mcp
   ```
4. Don't also install the community "MCP for Blender" package; both install an executable called `blender-mcp`.
5. Blender warns that the server runs model-written Python "without any guards". Keep Blender's open files to the project's art folders.
6. Check: ask Claude to open the Emotecraft rig, pose the right arm and send a viewport screenshot.

## 10. Later
- **Chunky and BlueMap (M5, worldgen).** Chunky is in the build block. With it, `/chunky radius 2000` then `/chunky start` pregenerates the dev world. Then run BlueMap's standalone CLI (5.29, from its GitHub releases) on `run/saves/<world>` and follow its wiki for the first-run config. Its low-resolution tiles are PNG files Claude can read, so it can judge roads, towns and biome layout from above.
- **LDLib2 (spike, only if Champ approves it).** It's on Modrinth only, beta, one build behind its 26.2 line:
  ```groovy
  localRuntime("maven.modrinth:ldlib:CcNvp4yE")     // LDLib2 26.3.2.41, beta
  ```
  The spike is one Corps Record screen. If it's worth the dependency, it moves to `implementation` and gets a line in ADR 0001.

## 11. Check that Claude can use each tool
Run `claude mcp list`: `bbmodel`, `mctest` and, once added, `aseprite` and `blender` should show as connected. Then ask Claude for these and look at what it hands back:

| Tool | Ask Claude to | Proof |
|---|---|---|
| IntelliJ MCP | list problems in one Java file and run `runGameTestServer` | diagnostics and the test count |
| MCTest | launch the client, open a world, screenshot it, freeze time, step 5 ticks, screenshot again | two images, 5 ticks apart |
| spark | run `/spark profiler start --timeout 30` through MCTest | the profile link or summary |
| JEI | search JEI for "netherite" and "strength" | no results (removed items and potions are hidden) |
| aseprite-mcp | make the sugi sample and run the blend check | the preview and `vanilla_style.py` output |
| Blender Lab | screenshot the rig | the viewport image |
| RenderDoc | read a capture you saved | Claude names the passes from the debug labels |
| Effort check | answer a design request in a new session | the Effort question, four options, the recommended one first (`docs/tools/effort.md`) |
| Quality gates | run `./gradlew spotlessCheck build` | BUILD SUCCESSFUL; when one fails, it names the file and line, the check, or the artifact |

## 12. Code quality gates (D-033)
Gradle fetches every tool, so there's nothing to install. `gradle/quality.gradle` sets them up for the mod and `core/`, and CI's build job runs each as its own step.
- **Formatting:** run `./gradlew spotlessApply` before committing (palantir-java-format; `spotlessCheck` runs inside `check`). For matching formatting in the editor, install IntelliJ's palantir-java-format plugin and enable it for this project. Leave **Build and run using** on Gradle (D-021), so IntelliJ's builds run Error Prone too.
- **Nullness and bug patterns:** `./gradlew build` runs Error Prone and NullAway on every compile, and any javac or Error Prone warning fails it. Every package needs a `package-info.java` that marks it `@NullMarked`, as Minecraft's packages are (a package without one fails the build); mark what may be null `@Nullable` (JSpecify).
- **Dependencies:** every Gradle run checks each artifact it resolves against `gradle/verification-metadata.xml`. After adding or bumping a dependency, a plugin or the Gradle version, regenerate the file from an empty Gradle home (Git Bash), then read the diff:
  ```bash
  GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 help spotlessCheck build runGameTestServer
  ```
  - **The empty home is required.** A warm cache skips metadata files that a fresh machine downloads, such as a BOM's `.module` file. The first metadata, written from the normal cache, failed CI that way (`junit-bom-5.14.3.module`).
  - **The run downloads everything again,** about 1.8 GB in 8 minutes; delete the folder after. If Windows says files in it are busy, run `./gradlew --stop` with the same `GRADLE_USER_HOME`, then delete it.
  - **The tasks:** `help` resolves every configuration, including the three that fetch other operating systems' natives (`build.gradle`). The other tasks resolve what only happens while a task runs, the formatter included.
  - **Before committing,** check that each new entry is the artifact you meant to add.
  - **Stale entries:** regenerating only adds entries. Now and then, delete the file and regenerate it whole, then put the `<trusted-artifacts>` block back, to drop the stale ones.
- **Line endings:** Java and Gradle files are LF on every OS (`.gitattributes`, `.editorconfig`). If an editor wrote CRLF, `spotlessApply` fixes it.
