# Phase 3 — SYNTHESIZE ✅

Seven independent reads (six exploration agents + the orchestrator), no communication
between them.

## The convergence

**All seven** arrived at the same structural conclusion by different routes: **the system
may cite a cause, never assert one.** It has no causal substrate — no topology graph, no
deploy-correlation ML — and its evidence is largely *selection-driven* (the Confluence
query is built from the ticket's own keywords, so a matching page is the search working,
not evidence). The one genuinely causal artifact available is a human's closed verdict on
a past incident.

Design consequence is structural, not stylistic: **no free-prose field**, so invention has
nowhere to live. This also satisfies the deterministic engine for free — it can only
select and template, and that is all the schema permits.

## Patterns

| | Pattern | Confidence |
|---|---|---|
| P1 | Cite a cause, never assert one | HIGH (7/7) |
| P2 | Abstention is the **common** path, not the edge case | HIGH (6/7) |
| P3 | Mitigation ≠ permanent fix — separate them | HIGH (6/7) |
| P4 | No percentages; the denominator is the hedge | HIGH (5/7, no dissent) |
| P5 | Safety must be structural (closed enum), not lexical (deny-list) | HIGH (4/7) |
| P6 | Plurality is both the hedge and the anti-anchor | MEDIUM (3/7) |
| P7 | **Mock-only verification is the escape layer** | — (emergent) |

→ [patterns.md](patterns.md)

## The conflicts

The disagreements were more valuable than the agreements. Six are recorded in
[trade-offs.md](trade-offs.md); five are resolved on evidence. **C3 is not ours to
settle** and goes to the operator: the convergent answer is *historical and attributed*
("how similar incidents **were** resolved") where the request was *predictive and
prescriptive* ("how **we can** likely resolve"). Defensible where the literal version is
not — but it is a different claim, and on a novel incident it says nothing.

## The number that governs the design

📚 Ahmed et al., ICSE 2023 (Microsoft, 44,340 incidents, graded by the engineers who
actually fixed them): LLM root-cause **correctness 2.40–2.88 / 5**; **readability
3.5–4.6**.

> It reads far better than it is right.

Every constraint in J26 follows from that asymmetry — and so does the pitch's sharpest
line.

## Verification

Structure matches all seven peer discovery workspaces. No missing READMEs.

**Accepted deviations** (DDS soft limits): four exploration READMEs run 505–688 words
against a 500-word guideline, and two `exploration.md` run 3074/3254 against 3000. The
overage is citations and verbatim example strings — the load-bearing content of the
prior-art and user-centric explorations specifically. Trimming would remove the evidence,
not the padding. Accepted rather than fixed.
