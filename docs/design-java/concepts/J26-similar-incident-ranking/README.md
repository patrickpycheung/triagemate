# J26 — Similar-incident retrieval & ranking

**State**: 🟢 Built (`worktree-hack-111`, merged to develop 2026-08-05) · **Complexity**: Moderate ·
**Priority**: HIGH · **Depends on**: J3 (connector tools), J5 (ServiceNow gateway) ·
**Amends**: J5 · **Source**: field report — *"ServiceNow's Find Similar Incidents always
returns zero hits"* (operator, live runs, 2026-08-05) → archived FND-84

> ## ⚠️ Card written after the fact, from the implementation
>
> This concept was implemented in `worktree-hack-111` directly from a field report; **no
> card existed when the code shipped**. This one was reconstructed on 2026-08-05 from the
> merged code and the FND-84 archive entry, because **J28 declares a dependency on J26** and
> a dangling dependency is a real defect.
>
> It therefore documents **what shipped**, and is deliberately thin on *why these weights*
> and *what alternatives were rejected* — that reasoning lives with whoever built it. Treat
> the ⛔-rejected sections as absent rather than empty. Verified against code, not assumed:
> `SimilarIncidentRanker.java:53-55`, `TriageProperties.java:94-114`, `application.yml:134`.

## Essence

`findSimilarIncidents` is called by both engines and described to the ADK model as *"the
strongest routing signal"* — and it returned **zero hits for every incident, always**.

The old retrieval was one encoded query:
`stateIN6,7^short_descriptionLIKE<first word of the subject line>`, with a hardcoded `0.5`
similarity stamped onto every row. Three faults, only the first of which showed as the
zero-hit symptom:

1. **The retrieval key was chosen by position, not information.** The first word of a real
   subject line is a sentence opener — "Unable", "Users", "Cannot".
2. **`cmdb_ci` — the field naming the affected system, and the strongest available match
   key — was never used.** Doubly dead before J24, whose reference-field parse bug returns
   `""` for it anyway.
3. **The score was fabricated.** `DeterministicDiagnosisEngine` renders it as
   `"%s (%.0f%% similar)"`, so every hit advertised **"50% similar"** in an advisory note
   posted onto a real ticket.

`stateIN6,7` additionally hardcoded out-of-the-box state values, so an instance with
customised states returned nothing regardless of keyword.

## Design (as built)

**Retrieve wide on the keys that carry signal, then rank locally.** Two overlapping passes —
same CI, most-recently-resolved first; then an OR-group over the ticket's distinctive symptom
terms — deduped and scored by `SimilarIncidentRanker`.

**Score** (`SimilarIncidentRanker.java:53-55`), floored and capped from config:

| Component | Weight |
|---|---|
| text Jaccard | **0.6** |
| CI match | **0.3** |
| category match | **0.1** |

Tokenising moved into `SymptomTokens`. A candidate matching nothing scores 0 and is dropped
whatever the floor.

**Configuration** — the hardcoded literals became `triage.servicenow.*` properties with
defensive defaults (`TriageProperties.java:112-114`): `resolvedStates` → `"6,7"`,
`similarityFloor` → `0.25`, `maxSimilar` → `5`.

## Why this matters beyond its own fix

Both engines were **reasoning from a permanently empty list** — and because empty is a
legitimate result, it never surfaced as an error. The trace said `→ 0 hits` and the report
simply routed on weaker evidence. That is the shape worth remembering: *a silent degradation
that looks exactly like a quiet day.*

**Escape layer: `mock-fidelity`.** `MockServiceNowGateway` supplies a realistic `0.91` and
hand-tuned genuinely-similar incidents, so none of the three faults was reachable from
`mvn test` or from the demo. Shared with FND-85/86/87 in the same batch — one missing
verification layer, not four unrelated mistakes.

## Verification

`SimilarIncidentRankerTest` (new) and `RealServiceNowGatewayTest` (extended) — see the
merged commits from `worktree-hack-111`.

## Out of scope

- **Rendering the similarity value.** J26 makes the score real; whether a percentage should
  be *shown* is J28's Evidence 3–5 (ServiceNow's own UI declines to, and `index.html:1207`
  records "No percentage, no progress bar" as direct product feedback).
- **`cmdb_ci` actually parsing** → **J24**. Until it lands, the 0.3 CI weight contributes
  nothing on real tickets, so ~30% of the ranking input is dead.
- **Quoting what those incidents were resolved with** → **J28**, which consumes this card's
  output as its `PRIOR_RESOLUTION` basis.
