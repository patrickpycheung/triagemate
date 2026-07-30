# C5 — Work-Note Post-Back — Design

Write-path fixed by spike **S1′** ([verification-s1/README.md](verification-s1/README.md)):
chat agent renders the draft note, user confirms, agent calls `post-worknote`
(`actionVerb: CREATE`). No autonomous headless write in the demo.

## Note format — two variants (G5)
The schema has a HIGH-confidence form and a DEGRADED form. `Responsible code:` and the
`Emitted by:` line are REQUIRED in the high form and OMITTED in the degraded form — so a
degraded note (no confirmed file:line) is still schema-valid. `Checked:` lists only the
sources actually consulted (drop Confluence if nothing was retrieved — G7).

**High-confidence:**
```
🔎 Triage hypothesis (AI-generated — review before acting)
Root cause (hypothesis): <one line>
Responsible code: <file>:<line>  (<GitLab master permalink @ ref from getSource>)
Evidence:
  • Log: "<exact matched log line>"  (Sumo, <timestamp>)
  • Emitted by: <file>:<line> — `<the log(...) statement>`
Checked: ticket · GitLab master · Sumo window[ · Confluence <doc>]
Confidence: high
```

**Degraded (no single confirmed emitter — R4 / C3):**
```
🔎 Triage hypothesis (LOW confidence — could not confirm a single emitting line)
Candidates: 1. <file>:<line> — <why>   2. <file>:<line> — <why>
Evidence: • Log: "<exact log line>" (Sumo, <timestamp>)
Checked: ticket · GitLab master · Sumo window
Confidence: low — recommend a human confirm before acting
```
The degraded shape is owned jointly with C3 mechanics (single source of truth: this file;
C3 links here).

## Confirm UX rule (from S1′ + ADM-1 decision)
The agent ALWAYS shows the draft in chat and waits for an explicit yes before writing.
Rationale: safer (human-in-loop on a real ticket write) and it makes the demo's final
beat legible. Decided inline (reversible, local) — recorded here.

## Write safety (G4 — the one irreversible side effect)
- `post-worknote` takes a `confirmed` boolean and **refuses to write unless
  `confirmed===true`** (belt to the prompt's braces — the guarantee isn't prompt-only).
- **Idempotency**: on a network error/timeout the action does NOT auto-retry — a timed-out
  write may already have landed. It reports "unknown outcome" and the agent surfaces that
  rather than re-posting. No blind cached-write fallback (that risks a duplicate work-note).

## Field choice
Write to the ServiceNow **work-note** (internal/technical audience), not the customer-
facing comment field. (ADM-1, recorded.)

Convergence: 🟢 Converged (spike-locked).
