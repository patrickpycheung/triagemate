# Phase 2 — DIVERGE ✅

Seven explorations, independent, no communication. Biases chosen to be non-overlapping —
each owns a question the others cannot answer. Brief: [exploration-brief.md](exploration-brief.md).

| Exploration | Owned question | Headline |
|---|---|---|
| [first-principles](explorations/first-principles/) | What *is* a cause claim? | TriageMate is a **witness-locator, not a diagnostician** — evidence is selection-driven, likelihood ratio ≈ 1 |
| [prior-art](explorations/prior-art/) | How does the industry word this? | Only vendors with a **causal substrate** say "the root cause". LLM RCA correctness **2.4–2.9/5** vs readability **3.5–4.6** |
| [risk-averse](explorations/risk-averse/) | How does this cause harm? | **GO-WITH-CONSTRAINTS.** FND-67 is only half-fixed → became FND-87 |
| [minimum-viable](explorations/minimum-viable/) | What's the cheapest honest version? | ~3h; it's an *insert into an existing step*, not a new pipeline |
| [technical-depth](explorations/technical-depth/) | What exactly goes in the contract? | `basis` is the load-bearing field — `evidenceRefs` prove traceability, not **support** |
| [user-centric](explorations/user-centric/) | What are the actual strings? | Make the resolution label **historical** — it survives being wrong |
| [claude-orchestrator](explorations/claude-orchestrator/) | Do the stated safety arguments still hold? | The guardrail bounds the **machine**; this feature makes the **human** the actuator |

## Spike executed

[verification-similar-incidents/](verification-similar-incidents/) — run automatically per
the DDS auto-spike rule, triggered when the most attractive design turned out to rest
entirely on `findSimilarIncidents`.

**🔬 NEGATIVE.** Production matches on the **first word** of the short description and
stamps every result with a hardcoded `similarity = 0.5`. The mock supplies realistic
values, so the demo cannot reveal it. → FND-84, FND-85, and the J27 prerequisite.

## Defects surfaced (filed to `/FOUND-ISSUES.md`)

| | Issue |
|---|---|
| FND-84 | hardcoded `0.5` rendered as "(50% similar)" onto real tickets |
| FND-85 | similar-incident matching on the description's first word only |
| FND-86 | non-UTF-8 byte makes plain `grep` silently skip the largest orchestration file |
| FND-87 | FND-67 self-poisoning still unfixed on the ADK path |

Three of the four are invisible to the mock profile — see P7 in
[../3-synthesize/patterns.md](../3-synthesize/patterns.md).

## Deliberate tensions

The biases were set to collide. All three designed collisions fired and are resolved in
[../3-synthesize/trade-offs.md](../3-synthesize/trade-offs.md): minimum-viable vs
risk-averse (C4), first-principles vs user-centric (C2), technical-depth vs
minimum-viable (C1).
