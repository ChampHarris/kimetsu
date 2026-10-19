# Start here

This folder is the Claude Code kit for **Kimetsu: Breath & Blood**. It's the root of the mod's repository: Claude Code reads `CLAUDE.md` and the `.claude/` folder on its own, and the first session builds the Gradle project around them. There's no mod code yet. The design, the rules Claude works by, the tools and the generated vanilla-scope datapack are all here.

## 1. Set up the folder (10 minutes)
1. Unzip it where you'll keep the mod (on Windows, right-click → Extract All, or `Expand-Archive kimetsu-claude-kit.zip .` in PowerShell). You get one folder, `kimetsu-claude-kit`, with this file at its top; rename it if you like (for example `kimetsu`).
2. Install what the kit needs: Claude Code, Git (on Windows, Git for Windows: the hooks run in Git Bash), Python 3.10+ with `pip install pillow`, a JDK 25, Node 22+, ffmpeg and Git LFS. `README.md` → Install has the details.
3. In the folder: `git init`, `git lfs install`, then commit everything.
4. Fetch the vanilla data and check the scope: `python tools/refsrc.py --mcmeta 26.3`, then `python tools/vanilla_scope.py --check`. It should end with `vanilla scope OK at M1 and every milestone to M5`. (On Windows use `py -3` if `python` isn't found.)

## 2. Set up the toolchain (about half a day, before any code)
Follow `docs/tools/toolchain.md`, steps 1 to 6: IntelliJ's MCP server, JetBrains Runtime hot reload, MCTest (Claude plays the dev client and takes screenshots), the dev-instance mods, Aseprite with its MCP server, and datapack editing. RenderDoc, the two-client latency test and Blender wait until M1.

## 3. Open Claude Code in the folder
- Approve the two project MCP servers (`bbmodel`, `mctest`) when asked.
- `/hooks` should list five hooks, and `/output-style` should show "Kimetsu Lead".
- Then paste the prompts in `docs/prompts/first-session.md`, one message at a time: Session 0 checks the tools, Session 1 creates the project, Session 2 writes the M1 spec.

## 4. What's waiting on you
Claude labels its own calls PROPOSED and won't build on them until you decide:
- **Before any code:** R6-01 (Minecraft 26.3 on NeoForge, one hop to 26.4, then freeze) and R6-02 (the animation stack and the KHR-1 rig) in `docs/design/round6-revision.md`.
- **Round 7:** the ten items under "What Champ needs to decide" at the end of `docs/design/round7-revision.md`: what of vanilla goes and when, the fixes that keep carrots and string obtainable, a new world at M5, trade levels, night demons, the Corps Record titles, and the tools to install.
- **Specs:** only you set `status: APPROVED`, by editing the file yourself. The hooks stop Claude from doing it.

## 5. Where things are
| You want | Open |
|---|---|
| How Claude must work | `CLAUDE.md`, `docs/taste.md` |
| Where the project stands, what's next | `docs/state.md` |
| The design (read-only snapshot of the live GDD) | `docs/gdd/` |
| Proposed changes | `docs/design/round6-revision.md`, `docs/design/round7-revision.md` |
| Every decision and why | `docs/decisions.md` |
| What of vanilla stays | `art/vanilla-scope.json`; `python tools/vanilla_scope.py --stages` shows each milestone |
| Commands and the daily loop | `README.md` |
| The same doctrine for claude.ai chats | `docs/claude-ai-project-instructions.md` (paste it into the Project's instructions) |

## Not in the zip, on purpose
- `ref-src/`: Minecraft and NeoForge sources and vanilla data. `tools/refsrc.py` fetches them on your machine; they're gitignored and never committed.
- `art/previews/`: throwaway previews the art tools write.
