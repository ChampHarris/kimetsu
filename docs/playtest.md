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

### 2026-10-29 · The combat HUD (slice 5d-3b, D-141, D-142, D-143)
- **Changed:** a Pulse ring at the crosshair: a closing ring meets the beat circle on every beat, five square pips light with your Rhythm (blue at Mushin), and at assist 1 or more a gap opens at the top of the closing ring while it closes on a strike that will land on you. Breath is a blue arc over Water's crest beside the hotbar's left, its grey Recovery segment after the fill. Your Posture shows as a thin bar under the crosshair once it drops. A locked target's name, HP (dark crimson) and Posture (its guard stake grey) show at the top. Winded edges the screen in grey ink. A Waterfall Basin icon shows right of the crosshair while falling far enough to fire it. The pips sit 25 px out, two pixels clear of the closing ring at its widest (D-143); the cue diamond now starts 43 px up and closes to 33, a pixel above the top pip, on the crosshair's axis to half a screen pixel at every HUD scale. The radial's lit name is washi 2 instead of washi 1 (too near the cue white).
- **Try it:** fight the demon (`/kimetsu demon`), lock it with middle click, chip its guard, fire a form and Recovery Breath on the beat, then off it (Winded); jump off a 3-block ledge. Set assist to 1 in Kimetsu Settings for the notch. Claude's sheets show every part working on OpenGL and Vulkan, at GUI scale 2 and 4 and HUD scale 0.5 to 2 (D-142); the feel is yours.
- **Watch for:** the ring readable at a glance with sound off (the closing ring touching the beat circle is the beat); the HUD fading half a second, three seconds after your last combat press once you're out of a fight, and back at once on a press. Wrong: the ring or pips crowding the cue diamond, the grey segment lingering after a Recovery Breath, the HUD clutter between fights. Known: at HUD scale 2 chat covers the Breath arc's top while it shows, and above HUD scale 1 on a narrow window (16:10 or 4:3 at its largest GUI scale) the arc's left end runs off screen (D-143); in third person the ring sits over your own head.
- **Not taken:** the HUD hidden until the first exchange (the GDD read literally), and fading during quiet circling (the spec as written); Champ's rule keeps it while you're fighting (D-123).
- **Knob:** `assets/kimetsu/feel/default.json` `hud`: `fade_after_ms` (3000), `fade_out_ms` (500), `fade_in_ms` (0), `tint_fade_ms` (200), each 0–10000, F3+T reloads; `hud_scale` in Kimetsu Settings (0.5–2.0); the layout's sizes are code constants in `client/present/HudLayout.java` (the pips' radius, the cue's gap and travel, the Breath arc's place; `HudLayoutTest` checks them at every GUI and HUD scale) and `client/render/CombatHudLayer.java` (the rest).
- **Champ:**

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

### 2026-10-29 · The demon's poses, its blend onto a strike and back (slice 5d-3a, D-138, D-139)
- **Changed:** the stand-in demon now shows what the server decided: arms across its chest in its guard (its body turned to the guard's facing), thrown back with arms out when its Posture breaks, kneeling with its head down when it collapses, flat on its back when knocked down, arms locked forward in a clash, arms out clutching when it holds you, and its head snapping back on a clang. Each pose blends in over 3 ticks. As a strike starts, its body slides onto the strike's path over 3 ticks (it used to hop about a third of a block forward, D-139); after a strike its body slides back to where the game has it over 3 ticks instead of snapping, and its arm comes down across the whole recovery. In a string its body stays on the last strike's path until the next one locks, as the server moves it, so a string's next strike that locks a tick late (a swipe after a swipe on Water's beat) no longer steps (D-140).
- **Try it:** fight the demon (`/kimetsu demon`) with F5 on; let it lunge (the leap), chip its guard, let it grab you. `/kimetsu_dev pose <stance>` shows any pose at once (dev runs; `/tick freeze` holds it).
- **Watch for:** each pose readable at a glance from 4 blocks; a strike starting and the lunge ending without a hop or a slide you notice; a string's strikes following on from each other with no step, a Feinted one too. Wrong: a pose that reads as a glitch (limbs through the body, sunk in the floor), or a visible jump as a strike locks or ends.
- **Known limit:** online at a high ping the hop as a strike locks, and a pop as one is cancelled, can come back (the lock and cancel messages arrive late); single player is unaffected. It goes to build step 5's two-client check (D-140), so don't report it as new.
- **Not taken:** turning the head to its target ourselves (the server already turns it, so it would turn twice).
- **Knob:** `assets/kimetsu/feel/default.json` `pose_blend_ticks`, `blend_in_ticks` and `blend_back_ticks` (0–10, F3+T reloads; `blend_in_ticks` 0 brings back the hop); the poses' angles are code constants in `client/present/StandInPoses.java` (stand-in only, until the rig).
- **Champ:**

## Played
(none yet)
