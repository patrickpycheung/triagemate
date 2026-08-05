# Phase 4 — DECIDE ✅

**Recommendation**: build it as **precedent citation, not causal assertion** — and fix the
similar-incident relevance defects first, because the whole feature is a rendering of that
one signal.

## Concepts for CDS — *updated post-merge 2026-08-05*

| ID | Concept | State | Note |
|----|---------|-------|------|
| **J28** | Precedent-grounded cause & resolution | 🔵 Proposed | The feature. ~6–8h full, ~3h flat MVP |
| ~~J27~~ | ~~Similar-incident relevance~~ | ✅ **Shipped** | Landed independently as develop's **J26** (`SimilarIncidentRanker`) |
| — | ADK self-poisoning (was FND-87) | ✅ **Fixed** | Carded as **J27**, `fixed:14fa031` |

**Both blockers are gone.** The feature was renumbered J26 → **J28** because `J26` was
already claimed in code by the peer's ranking work; the similar-incident prerequisite was
found and fixed concurrently by `worktree-hack-111` from a live run while this DDS reached
it by reading the query construction; and the ADK self-poisoning prerequisite is fixed and
carded here.

**J28's path is clear.** Nothing blocks it.

→ Full detail: [concepts-extracted.md](concepts-extracted.md)

## Why this shape

Seven independent explorations converged: the app has **no causal substrate**, so it is
structurally in the hedged family of tools. Its one genuinely causal artifact is a human's
closed verdict on a past incident. So the design quotes that verdict with attribution
rather than generating a claim of its own — which is simultaneously the safest, the
cheapest, the most honest, and the only version the deterministic engine can produce.

Four rules make it hold: **quote never paraphrase** · **abstention is legal and expected**
· **no percentages, the denominator is the hedge** · **remediation verbs are a closed
enum**.

## The one open decision for the operator

**C3 — framing.** The request was predictive/prescriptive; the convergent answer is
historical/attributed:

| Asked for | Converged on |
|---|---|
| "What likely **caused** the issue" | "Why this may be happening" — ≥2 hedged hypotheses, or *not established* |
| "How **we can** likely resolve it" | "How similar incidents **were** resolved" — quoted, attributed |

Same plumbing either way, so it is a late, cheap wording change — but it is a materially
different claim and it is not ours to make.

## What we are choosing not to build

Ranked cause hypotheses with numeric confidence · a `rationale` free-text field · a
confidence bar (settled by product feedback at `index.html:1187`) · any remediation verb
that mutates state · paraphrase of `close_notes` into TriageMate's own voice.

## Honest expectation

**Excellent in the mock demo. Frequently abstaining in production** — real close notes are
mostly *"Issue resolved"*/*"Done"*, matching is first-word, similarity is fake. That is the
correct outcome for a system with no causal substrate, it is still worth building, and J27
is what moves the needle. It should be known before the demo, not discovered during it.
