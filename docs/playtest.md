# Playtest list

The calls Claude made about how the game plays, feels, looks or sounds (decision-rights.md, 2026-10-28). Champ judges them in game; he doesn't need to read the specs first. Newest first. When he has played one, his verdict goes on its **Champ** line, and into `docs/decisions.md` when it changes something; his verdict overrides Claude's call.

Entry format:

```
### YYYY-MM-DD · <short name> (<spec id or D-number>)
- **Changed:** what is different now, in one or two plain lines.
- **Try it:** where and how (commands, gamerules, the setup to reach it).
- **Watch for:** what right looks and feels like, and what would mean it's wrong.
- **Not taken:** the main alternative and why it lost.
- **Knob:** the file and key that changes it, with the sensible range.
- **Champ:** (after playing)
```

## Waiting to be played

### 2026-10-29 · The feint bite (combat-beat-locked-strikes, D-132)
- **Changed:** tapping guard into a demon feint after it visibly broke costs you the deflect on the string's next strike (you can still block it with held guard or Step). Playable once the bite's build slice lands; until then a feint gives the plain 250 ms whiff lockout.
- **Try it:** dev client, fight the M1 demon; set `strings.feint_chance` in `m1_demon.json` to 0.08, then 0.25, and fight a few strings at each, once reading the glint and once just tapping on every beat.
- **Watch for:** a bite should feel earned (you tapped after you saw the shatter) and never random. Wrong: getting bitten when you're sure you stopped in time, especially at a lower frame rate (the red team's finding 1: the build slice adds the frame stamp before this is fair at 30 fps).
- **Not taken:** a longer whiff lockout for every whiff (it either missed feints or cascaded honest late taps through a string, `feint_lockout.py`).
- **Knob:** `combat_rules/default.json` `feint_bite_lockout_beats` 0–1 (0 = off), `feint_bite_notice_ms` 200–300; `m1_demon.json` `strings.feint_chance` 0.08–0.25.
- **Champ:**

### 2026-10-29 · The earlier feint break (combat-beat-locked-strikes, D-132)
- **Changed:** a feint's glint breaks 8 ticks (400 ms) before its would-be impact instead of 5, so the swipe's glint shows for 2 ticks before it splinters (the heavy's for 6). Lands with the bite.
- **Try it:** same fight; watch a feinted swipe's glint closely, with F1 off.
- **Watch for:** it should read as a glint that breaks. Wrong: a flicker you can't tell from noise. The fix is then a longer cue lead for every swipe, never a feint-only one (that would give the feint away before it breaks).
- **Not taken:** keeping the 5-tick break (the notice would leave no part of the window that can bite).
- **Knob:** `m1_demon.json` `strings.feint_break_ticks` 6–10; the swipe's `cue_lead_ticks` in `frame_data/m1_demon/swipe.json`.
- **Champ:**

### 2026-10-29 · The bite's clack (combat-beat-locked-strikes, D-132)
- **Changed:** a bite plays a short, dull clack on your blade, with a "Baited" subtitle. It uses no telegraph colour and stays off the cue channel. Lands with the bite.
- **Try it:** get bitten on purpose (tap after a feint shatters), with sound on, then with subtitles on and sound off.
- **Watch for:** you should know you were baited before the next strike lands as a block. Wrong: the clack muddying the next strike's glint sound or ting.
- **Not taken:** no sound (the block on the next strike would feel like a bug).
- **Knob:** the clack's sound event and volume in the sounds data (built in the slice).
- **Champ:**

## Played
(none yet)
