# J7 — Demo UI & Ground-Truth Dataset

**State**: 🟡 Drafted · **Complexity**: Moderate · **Depends on**: J4 ·
**Carries**: RC6 (demo safety), S3′ fixture + seed-repo

## Essence
What the audience sees, and the curated data that makes it work offline and
convincingly. Scope to **one** demonstration application — do not cover the enterprise.

## Demo UI
- A single static page (served by Spring Boot) with a box: enter incident number →
  "Diagnose" → renders the J4 report: symptom, ranked candidates + confidence bars,
  suggested team, **evidence list with source badges + links**, contradicting
  evidence, missing info, next action, and the tool-call trace (J8) so viewers see
  it "really consulted all four sources."
- Plain HTML + fetch to `/api/diagnose/{n}`; no framework needed.

## Ground-truth dataset (the `mock` profile serves this)
For the one demo app, capture per incident: actual affected application, actual root
cause, correct assignment team, relevant Confluence page, relevant log evidence,
final resolution. Requirements (from the analysis):
- 3–10 historical incidents (≥2 "similar resolved" for the routing signal),
- 1–2 Confluence pages (a runbook / known-error doc),
- a known support team, one repository (**reuse `seed-repo/`**),
- a small set of Sumo logs (**reuse S3′ `sumo-fixture.json`**),
- at least one **known error + resolution** with a seeded distinctive log line.

## Demo scenario (locked narrative)
"A user reports an operation failed with a vague description. The copilot clarifies
the symptom, finds a similar resolved incident, locates the runbook, runs one narrow
Sumo query, correlates the log line to `seed-repo` source (file:line), suggests the
likely team — medium confidence — and posts an advisory work note. No reassignment
performed."

## Demo safety (RC6)
- Everything runs in `mock` profile with **no network** as the safe default.
- Fallbacks: any single tool failure degrades to "evidence omitted," never a crash.
- Pre-demo checklist; a recorded backup run.

## Offline evaluation (optional, strong)
Take resolved historical incidents, hide assignment + resolution, and check whether
the copilot reconstructs them (correct app + team in top-3).

## Verification
- Fresh checkout + `mvn spring-boot:run` → open page → diagnose the demo incident →
  full report with the log↔code citation, no network.
