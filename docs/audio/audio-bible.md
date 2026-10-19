# Audio bible

Sound carries half of this mod: the Pulse is a sound, every telegraph has a sound, and players must be able to answer a strike they can't see. Enforced by `tools/audio_lint.py`; judged by ear in playtests.

## 1. Identity
Taishō Japan, heard close: wood, cloth, breath, rain on tiles, temple bells, insects at dusk. Music from Japanese instruments (shamisen, shakuhachi, taiko, koto, biwa) with restrained modern low end. Silence is a tool: the world drops away when you're winded or Posture-broken.

## 2. The Pulse
- A heartbeat built from three layers: a low body thump, a soft taiko skin, and a breath swell in Total Concentration. The Breathing Style sets the tempo (Water slower); a Domain boss sets one tempo for everyone.
- It must sit under combat sound without masking cues: fundamental around 50–70 Hz, short decay, no reverb tail on the cue channel.
- Calibration (R6-05) shifts when the Pulse plays so it's heard on time; it never moves judgment.

## 3. The cue channel
- Telegraph sounds (ting, low drum, hiss, rising chime) and the Perfect Deflect bell play on a reserved channel that is never ducked, muffled, randomised or low-passed.
- Each cue sound is unique in pitch, envelope and timbre, so it reads with the eyes closed.
- **Every cue has a subtitle** ("Demon strike — deflect", "Unblockable — dodge", "Grab — Pommel or dodge", "Thread open"), and every other sound event has one too.

## 4. Combat sound
- **Strikes are layered:** a blade transient, the style's body (Water: a wet whoosh), and an impact chosen by what was hit, each pitched ±8%, plus a short reverb tail (GDD).
- **Low-pass when winded or Posture-broken:** world sound goes behind an OpenAL EFX low-pass for up to 2 s while your own breath and heartbeat stay close; the cue channel stays clear; a clean Recovery Breath snaps the world back (GDD).
- **Decapitation:** a held breath of silence for the hit-stop, then the cut, then the ash.

## 5. Music
- Boss music in stems (from M4): an exploration bed, a combat layer, and a phase or Thread layer, crossfaded on bar lines so they never fight the Pulse.
- An original score in the series' idiom (taiko, shakuhachi, shamisen, koto, biwa, a choir for bosses). Claude doesn't transcribe or recreate the anime's own tracks. AI music tools are fine for drafts, stems and loops.

## 6. Voice
- Original voices only. Claude won't clone or imitate the anime cast: they're real people, and that doesn't change for a private build.
- Fine: your own recordings, friends, VOICEVOX voices, AI voices designed from scratch (for example ElevenLabs voice design), and non-verbal breaths, shouts and grunts. Crow and NPC lines can be subtitles with non-verbal vocalisation until voices exist.

## 7. Where sounds come from
| Source | Good for |
|---|---|
| Your own recordings | anything; keep the raw takes in `art/source/audio/` |
| Sonniss GDC bundles | SFX layers (impacts, cloth, wood, weather) |
| Freesound | SFX, ambience |
| Japanese instrument libraries (Sonica, Impact Soundworks, Versus Audio) | music and stingers |
| AI tools (Stable Audio, ElevenLabs SFX, AI music generators) | drafts, layers, stems; check each tool lets you download what you make |
| VOICEVOX | NPC voices |

Run everything through `tools/audio_lint.py`; a mixed source is fine as long as it sounds like one world.

## 8. Technical rules (`tools/audio_lint.py`)
- OGG Vorbis only. Mono for anything positional (stereo sounds never attenuate with distance); stereo only for music and ambience marked `"stream": true` or under `sounds/music/` and `sounds/ambient/`.
- 44.1 or 48 kHz. Files over 20 s stream.
- Level (PROPOSED, tune against vanilla): short one-shots between −30 and −12 dBFS RMS (integrated loudness is undefined under its 400 ms gating window); non-streamed sounds of 3 s or more between −30 and −14 LUFS integrated; true peak ≤ −1 dBTP everywhere. The overall mix targets −24 LKFS ±2 (Sony ASWG-R001).
- Every file is referenced by `sounds.json` and every reference exists.

## 9. Vanilla sound (R7-01)
- **Kept vanilla keeps its sounds:** footsteps on stone, dirt and wood, doors, crafting, furnaces, farming. They're menial, and players read them without thinking.
- **Vanilla's music goes (PROPOSED); the score replaces it.** In 26.3, background music is data. It's the `minecraft:audio/background_music` environment attribute on dimension types and biomes, with `default` and `creative` tracks and their delays. The Taishō preset's biomes and the overworld point it at the mod's tracks. The title-screen music is replaced through a `sounds.json` override of `music.menu` with `"replace": true`.
- **Removed mobs' sounds are unreachable,** so nothing replaces them.
- **Cave ambience** (the `minecraft:audio/ambient_sounds` mood sound) stays until the preset has its own.
