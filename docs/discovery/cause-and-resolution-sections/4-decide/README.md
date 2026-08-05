# Phase 4 — DECIDE ✅

**Recommendation**: build it as **precedent citation, not causal assertion** — and fix the
similar-incident relevance defects first, because the whole feature is a rendering of that
one signal.

## Concepts for CDS

| ID | Concept | State | Note |
|----|---------|-------|------|
| **J26** | Precedent-grounded cause & resolution | 🔵 Proposed | The feature. ~6–8h full, ~3h flat MVP |
| **J27** | Similar-incident relevance & honesty | 🔵 Proposed | **Blocks J26.** Fixes FND-84/85 |

Plus one hard prerequisite that is a bug, not a concept: **FND-87** (ADK path still
self-poisons). With J26 in place its consequence upgrades from keyword drift to
**confidence laundering** — run 1's hypothesis becoming run 3's stated cause through a
circular chain that `evidenceRefs` validation cannot detect, because every link is a
genuine, correctly-cited artifact.

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
