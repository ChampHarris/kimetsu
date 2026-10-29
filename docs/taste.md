# Taste: what "best" means for this mod

Champ owns taste; this page records it so every session aims at the same target. Since 2026-10-28 Claude makes the taste calls too (a key layout, a colour, which form gets a finisher): decide by this page, record it in `docs/decisions.md` and put it in `docs/playtest.md`, so Champ judges it by playing and overrules it there (`docs/tools/decision-rights.md`). When it's craft (does it read, is it fair, does it run), decide with evidence.

## The bar
- **It should feel like the show, not like Minecraft with swords.** Weight, anticipation and a cut that lands on the beat; ink-wash trails; brushed form names; demons who were people. ufotable's hand-drawn effects and the series' quiet moments are the reference, not other mods.
- **Skill is the currency.** Timing beats stats. No grinding, no random substats, no gear treadmill, no power for sale. If an idea makes a player stronger without making them better, it's out.
- **Every system earns its place.** Fewer, deeper systems that touch each other (the Pulse drives offence, defence, Breath and boss identity) beat many shallow ones. Before adding a meter, verb or currency, prove an existing one can't do the job.
- **Readable under pressure.** A player answers any strike from sound or shape alone. Telegraph colours are reserved. Spectacle never hides a cue.
- **The world tells the story.** Crows, letters, cases, rumours, townsfolk who go missing. No quest-log screen, no exclamation marks.
- **Losing costs time, never progress,** and it should teach something.
- **It blends with the Minecraft it keeps.** Menial vanilla (ores, coal, torches, stone, crafting, farming) stays vanilla; everything else is the mod's own, in one consistent hand, at vanilla's 16 px and on vanilla-like ramps, so a cedar plank sits beside vanilla stone, dirt and foliage without a seam. Nothing jarring, nothing hyper-real (Champ, D-012, D-013).
- **Fair online.** Ping, frame rate and assist level shouldn't decide who wins a duel.
- **A bigger group still has a night's work.** Demons come every night, paced so six players fight as much as one, and never more than two strike the same player at once (R7-03).

## The adopt check
Novelty earns nothing by itself. When another game or mod already solved a problem well, adopt its solution if it passes all five:
1. **Job:** it solves the player problem better than the current rule or anything we'd invent.
2. **Pillars:** it serves skill-first play, readability and canon, and doesn't add a meter or verb that an existing one could carry.
3. **Translation:** it survives Minecraft: server authority at 20 ticks a second, latency, both cameras, block terrain, many players.
4. **Improvement:** we name what we change for this mod (the tie-in to the Pulse, Posture, the Thread or cases) and which known complaint about the original we fix.
5. **Build:** it fits the stack, has a test (JUnit, GameTest or a Lens scenario) and a size in days.

Passes all five: adopt it as the `[adopt]` option, credit it `[prior-art: <title>]`, and build our version. Fails one: say which, then fix it or drop it.

## Generic defaults (adopt only through the check)
From `docs/research/prior-art.md`, the genre's defaults, each worn out somewhere for a specific reason: R to cycle forms; a random element on first sword grip; opaque spins for clans or arts; fan-invented breathing styles (Frost, Alcohol); a rank ladder driven by kill counts; Muzan's blood drops as XP; protect-the-passengers Mugen Train with Akaza at the end; Infinity Castle as an XP grind; Final Selection gated by level; Sun Breathing as an "I win" unlock; detective-vision senses that mark the path; QTE finishers; a breathe key that just charges a gauge; plain on-beat damage multipliers; a mob that kills you simply gets a name.

The GDD already adopts three of them in versions that pass: Recovery Breath is Nioh's Ki Pulse tied to the Pulse and Rhythm, Downed players can be revived as well as executed, and Nemesis demons grow from their win (R6-22 takes them further).

## What great looks like here
- The heartbeat is the interface: you hear when the claw comes (beat-locked strikes).
- One input does several jobs (Inhale restores Breath, scores Rhythm and refunds a form).
- Cause and effect across systems (a wound slows Posture recovery, which opens the Thread; a missing townsperson becomes a demon, a case and a Last Light).
- Original content only where canon is silent, marked `[OC]`, and never contradicting what canon shows.

## How to push back well
Find the flaws first: before agreeing to an implementation, name at least two concrete ones (budget, desync, Mixin risk, a missing API), or say where you looked (D-018). Lead with the verdict and the one reason that matters most. Offer the better version in the same breath. Show the evidence (a sim table, a canon citation, a player complaint, a primer line). If Champ still prefers his version, build it his way and log the concern in `docs/decisions.md`.
