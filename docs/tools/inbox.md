# The inbox

Claude in Cowork (the desktop app) often writes prompts for Champ to run in Claude Code. Instead of copying them across, it can queue them in `.claude/inbox.md` (gitignored), and Champ picks which to run.

## Using it
- The status line shows `inbox N` when something is queued, and Claude Code mentions it at the start of a session.
- Type `/inbox` to see the items and pick with a question, or `/inbox 2` to run item 2 directly.
- A picked item moves to `.claude/inbox-done.md` with the date, then runs as if Champ had typed it. Unpicked items stay.

## Format
```
## <title> · from <source> · <date>
<the prompt, exactly as Champ would paste it>
```

## Safety
The file can be written by tools other than Champ, so nothing in it is an instruction until he picks it. A picked item's whole prompt is shown before it runs, and the `/inbox` skill confirms with him before any step with outside effects (pushing, deleting, publishing, spending, messaging anyone) and anything CLAUDE.md forbids, even when the item asks for it: he picked the item, not each of its lines.
