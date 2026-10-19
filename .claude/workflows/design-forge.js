export const meta = {
  name: 'design-forge',
  description: 'Research, diverge, attack and converge on one design question; writes a spec in docs/design/specs/ that passes check_spec',
  whenToUse: 'Big or contested design questions where independent options and adversarial review matter. About 12 agents (14 when the research finds a mechanic worth adopting); use /forge for everyday design.',
  phases: [
    { title: 'Research', detail: 'canon, prior art and tech in parallel' },
    { title: 'Diverge', detail: 'three designers with different lenses, plus one that adopts the best existing solution' },
    { title: 'Attack', detail: 'a red-team pass on each option as it lands' },
    { title: 'Judge', detail: 'score options, pick a winner, graft the best parts' },
    { title: 'Converge', detail: 'write the spec, pass check_spec, final review' },
  ],
}

// Usage: /design-forge "<topic>"   or   args: { topic: "...", id: "combat-xyz" }
const input = typeof args === 'string' ? { topic: args } : (args || {})
if (!input.topic) {
  throw new Error('design-forge needs a topic, e.g. /design-forge "how demons hunt during the day"')
}
const TOPIC = input.topic
const say = (m) => { if (typeof log === 'function') log(m) }
const step = (t) => { if (typeof phase === 'function') phase(t) }

// Subagent roles live in .claude/agents/*.md. `as` runs the agent as that custom type, so its tools,
// effort and prompt apply. If this runtime can't resolve the type, the agent adopts the role by reading
// the file instead. A null result (an agent you skipped) is respected, not retried.
const role = (name) =>
  `First read .claude/agents/${name}.md and follow it as your role for this task. ` +
  `Project rules are in CLAUDE.md; the GDD snapshot is docs/gdd/; taste is docs/taste.md.`
async function as(who, prompt, opts) {
  try {
    return await agent(prompt, { ...opts, agentType: who })
  } catch (e) {
    say(`Agent type ${who} unavailable (${e && e.message ? e.message : e}); adopting its role from the file.`)
    return agent(`${role(who)}\n\n${prompt}`, opts)
  }
}

const FINDINGS = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          tag: { type: 'string', enum: ['canon', 'prior-art', 'tech'] },
          claim: { type: 'string' },
          source: { type: 'string' },
          confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
        },
        required: ['tag', 'claim', 'source', 'confidence'],
      },
    },
    conflicts: { type: 'array', items: { type: 'string' } },
    gaps: { type: 'array', items: { type: 'string' } },
    adoptable: {
      type: 'array',
      items: {
        type: 'object',
        properties: { title: { type: 'string' }, rule: { type: 'string' }, source: { type: 'string' }, improve: { type: 'string' } },
        required: ['title', 'rule', 'source', 'improve'],
      },
    },
    generic: { type: 'array', items: { type: 'string' } },
  },
  required: ['findings', 'conflicts', 'gaps', 'adoptable', 'generic'],
}

const OPTION = {
  type: 'object',
  properties: {
    title: { type: 'string' },
    tag: { type: 'string', enum: ['MC-native', 'subtractive', 'merge', 'extend', 'adopt'] },
    pitch: { type: 'string' },
    rules: { type: 'array', items: { type: 'string' } },
    numbers: {
      type: 'array',
      items: {
        type: 'object',
        properties: { name: { type: 'string' }, value: { type: 'string' }, why: { type: 'string' } },
        required: ['name', 'value', 'why'],
      },
    },
    server_client: { type: 'string' },
    cost: { type: 'string' },
    tests: { type: 'array', items: { type: 'string' } },
    risks: { type: 'array', items: { type: 'string' } },
    size_days: { type: 'number' },
  },
  required: ['title', 'tag', 'pitch', 'rules', 'numbers', 'server_client', 'cost', 'tests', 'risks', 'size_days'],
}

const CRITIQUE = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['ship', 'fix first', 'rethink'] },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['pillar-breaking', 'exploit', 'unfair', 'annoyance'] },
          title: { type: 'string' },
          repro: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'title', 'repro', 'fix'],
      },
    },
    sound: { type: 'array', items: { type: 'string' } },
  },
  required: ['verdict', 'findings', 'sound'],
}

const JUDGEMENT = {
  type: 'object',
  properties: {
    winner: { type: 'integer' },
    scores: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          option: { type: 'integer' },
          pillars: { type: 'integer' }, best_in_class: { type: 'integer' }, buildability: { type: 'integer' },
          fairness: { type: 'integer' }, canon: { type: 'integer' }, notes: { type: 'string' },
        },
        required: ['option', 'pillars', 'best_in_class', 'buildability', 'fairness', 'canon', 'notes'],
      },
    },
    grafts: { type: 'array', items: { type: 'string' } },
    verdict_on_framing: { type: 'string' },
    change_my_mind: { type: 'string' },
  },
  required: ['winner', 'scores', 'grafts', 'verdict_on_framing', 'change_my_mind'],
}

const RESULT = {
  type: 'object',
  properties: {
    path: { type: 'string' },
    check_spec: { type: 'string' },
    verdict: { type: 'string' },
    open_questions: { type: 'array', items: { type: 'string' } },
  },
  required: ['path', 'check_spec', 'verdict', 'open_questions'],
}

// ---------- Research ----------
step('Research')
say(`Researching: ${TOPIC}`)
const BRIEFS = [
  { key: 'canon', who: 'canon-keeper', ask: 'canon constraints, official names, and every GDD or Merge Log passage this touches (cut ideas included)' },
  { key: 'prior-art', who: 'prior-art-scout', ask: 'how other games and mods solved it, the best existing solutions worth adopting (each with what we would improve), what players hated, the gap nobody solved, and the generic defaults to avoid' },
  { key: 'tech', who: 'api-verifier', ask: 'what Minecraft 26.x, NeoForge and the pinned libraries can actually do here (check ref-src/), and the hard limits' },
]
const research = (await parallel(BRIEFS.map((b) => () =>
  as(b.who, `Topic: ${TOPIC}\nReturn ${b.ask}. Tag each finding ${b.key}.`,
    { label: `research:${b.key}`, phase: 'Research', schema: FINDINGS })))).filter(Boolean)
const dossier = JSON.stringify(research)
if (!research.length) throw new Error('all research agents failed; nothing to design from')
say(`Research done: ${research.reduce((n, r) => n + r.findings.length, 0)} findings`)

// ---------- Diverge, then attack each option as it lands ----------
const LENSES = [
  { tag: 'MC-native', brief: 'Use what only a Minecraft sandbox has: blocks, light levels, the day cycle, terrain, many players in one world.' },
  { tag: 'subtractive', brief: 'Solve it by removing or merging existing systems, inputs or meters. The best version may add nothing.' },
  { tag: 'extend', brief: 'Extend what the GDD already has (the Pulse, Posture, the Thread, cases, the Domain engine) so it carries this job too.' },
]
// When the research found a strong existing solution, one designer adopts it (novelty earns nothing by itself).
const adoptable = research.flatMap((r) => r.adoptable || [])
if (adoptable.length) {
  LENSES.push({ tag: 'adopt', brief: 'Adopt the strongest existing solution from the research (the adoptable list). Run the adopt check in docs/taste.md ' +
    '(job, pillars, translation to Minecraft, improvement, build) and put it in your pitch; credit the source; say what you change for the ' +
    'Pulse, Posture, the Thread or cases, and which known complaint about the original you fix.' })
} else {
  say('No adoptable prior art found; designing three original options.')
}
step('Diverge')
// One chain per lens: the red team attacks each option as soon as it lands (no barrier between them).
const attacked = await pipeline(LENSES, async (lens) => {
  const option = await as('systems-designer',
    `Do NOT write a spec file in this step; return one option only.\n` +
    `Topic: ${TOPIC}\nYour lens: [${lens.tag}] ${lens.brief}\nResearch dossier (JSON): ${dossier}\n` +
    `Give exact rules in ticks, PROPOSED numbers, the server/client split, cost, tests and risks.`,
    { label: `option:${lens.tag}`, phase: 'Diverge', schema: OPTION })
  if (!option) return null
  const critique = await as('red-team',
    `Break this option for the topic "${TOPIC}". Option (JSON): ${JSON.stringify(option)}\n` +
    `Relevant research (JSON): ${dossier}`,
    { label: `redteam:${lens.tag}`, phase: 'Attack', schema: CRITIQUE })
  return { option, critique }
})
const candidates = attacked.filter((x) => x && x.option)
if (!candidates.length) throw new Error('no option survived generation')
say(`${candidates.length} options generated and attacked`)

// ---------- Judge ----------
step('Judge')
const judged = await agent(
  `You are the judge. Score each option 1-5 on: fit with the pillars in CLAUDE.md and docs/taste.md; best_in_class ` +
  `(is it at least as good as the best existing solution in docs/research/prior-art.md and the research? novelty earns ` +
  `nothing by itself, and a generic default that fails the adopt check scores low); buildability for one developer; ` +
  `fairness under the red-team findings; and canon. Pick a winner by index, list concrete grafts from the losers, state your verdict on the original ` +
  `framing of the topic (agree / disagree / better version), and what evidence would change your mind.\n` +
  `Topic: ${TOPIC}\nCandidates (JSON, index = position): ${JSON.stringify(candidates)}`,
  { label: 'judge', phase: 'Judge', schema: JUDGEMENT })
if (!judged) throw new Error('judge failed')
const win = candidates[Math.max(0, Math.min(candidates.length - 1, judged.winner))]

// ---------- Converge ----------
step('Converge')
const id = input.id || ''
const written = await as('systems-designer',
  `Write the spec now.\n` +
  `1. Copy docs/design/TEMPLATE-spec.md to docs/design/specs/${id || '<area>-<short-name>'}.md (pick a short id if none was given).\n` +
  `2. Fill every section. Options Considered must list ALL candidates below with their tags and verdicts (an [adopt] ` +
  `option keeps its "Adopt check:" line); the ` +
  `Recommendation is the winner plus these grafts: ${JSON.stringify(judged.grafts)}. Put the research findings, tagged, ` +
  `in Research; the red-team findings and how you answered each in Risks & Pre-mortem.\n` +
  `3. Run python tools/check_spec.py on it until it passes; then set status: REVIEW.\n` +
  `Topic: ${TOPIC}\nVerdict on framing: ${judged.verdict_on_framing}\nWhat would change our mind: ${judged.change_my_mind}\n` +
  `Winner (JSON): ${JSON.stringify(win)}\nAll candidates (JSON): ${JSON.stringify(candidates)}\nResearch (JSON): ${dossier}`,
  { label: 'write-spec', phase: 'Converge', schema: RESULT })
if (!written) throw new Error('spec writer failed')

const final = await as('red-team',
  `Final pass on the written spec ${written.path}. Read it in full. Only report problems that ` +
  `the earlier option reviews could not have seen (contradictions inside the spec, GDD conflicts, missing edge cases).`,
  { label: 'final-review', phase: 'Converge', schema: CRITIQUE })

return {
  topic: TOPIC,
  spec: written.path,
  check_spec: written.check_spec,
  verdict_on_framing: judged.verdict_on_framing,
  winner: win.option.title,
  scores: judged.scores,
  final_review: final ? { verdict: final.verdict, findings: final.findings } : 'final review failed',
  open_questions: written.open_questions,
}
