# Open questions — require the corporate-network laptop

These cannot be answered from this machine (the live ServiceNow dev instance is only
reachable from the corp laptop; see the repo's ServiceNow integration guide). They are
recorded as ❓ Unknown rather than guessed.

## Q1 — Is `close_notes` actually populated on resolved incidents?

**Why it matters**: the entire grounded cause/resolution design rests on
`resolutionNotes`. If real resolved tickets mostly have empty `close_notes`, the
citation approach degrades to abstention on most incidents, and the `CHANGE` warrant
(recent commits touching the implicated file) becomes the load-bearing signal instead.

**How to answer** (read-only, safe, ~1 minute on the corp laptop):

```
GET /api/now/table/incident
    ?sysparm_query=stateIN6,7
    &sysparm_fields=number,close_code,close_notes
    &sysparm_limit=100
```

Report: what fraction have non-empty `close_notes`, and eyeball 10 for whether they
describe a *cause*, a *fix*, both, or neither ("closed - no response from user").

**Decision it unblocks**: whether resolution-by-precedent is the primary path or a
fallback.

## Q2 — What do real `close_code` values look like?

**Why it matters**: `close_code` is the *controlled-vocabulary* field, and
[claude-orchestrator](../explorations/claude-orchestrator/exploration.md) argues a
closed vocabulary is the structural defense against free-text remediation reaching an
engineer as a directive. That only works if the vocabulary is real and meaningful.

**How to answer**: the same query — collect the distinct `close_code` values and their
frequencies.

**Decision it unblocks**: whether the resolution section can be built from a safe enum
(preferred) or must quote free text (weaker, needs attribution + scanning).

## Q3 — Do incident short descriptions really start with generic words?

**Why it matters**: quantifies how badly `firstKeyword` degrades matching
(see [findings.md](findings.md)).

**How to answer**: from the same 100 rows, tally the first token.

**Decision it unblocks**: how urgently `findSimilarIncidents` must be fixed, and whether
a quick stopword list is sufficient or real scoring is required.

---

**Note on sequencing**: none of these block Phase 3 synthesis or the Phase 4 concept
extraction. They block *implementation confidence* on one branch of the design. The
recommendation should therefore be robust to both answers — i.e. it should degrade
gracefully to abstention if Q1 comes back mostly-empty.
