# J25 — Knowledge Query Relevance (a search the app issues must be a search a human would recognise)

**State**: 🟡 Mostly built — **KQR-2 landed 2026-08-06** (relevance floor; scores system and symptom separately and takes the better, after an exact-match first cut dropped the demo's own runbook). **KQR-4 landed 2026-08-06** — implemented ONCE, jointly with J14/FRI-5, via a thrown `GatewayUnavailableException`. **All four rules built.** (2026-08-05) — **KQR-1** (siteSearch + symptom-as-written) and
**KQR-3** (pages only) landed and are verified against the REAL AusPost instance with live
credentials. The root cause turned out to be the CQL OPERATOR, not the query text: `text ~`
has no relevance ranking, `siteSearch ~` is what backs Confluence's own UI search — which is
why the reporter's words worked in the UI and not in the app. **Still open**: KQR-2
(relevance floor before citing) and KQR-4 (failed vs empty) · **Complexity**: Moderate · **Priority**: MEDIUM
**Depends on**: J6 (knowledge tools), J3 (gateway contracts), J24 (supplies the affected-system term)
**Amends**: J6 (the Confluence query contract), J2 (`IncidentSignals.confluenceQuery`)
**Source**: teammate field report — `docs/Siyad_Findings.md` §4 (and §1's log), by **sajids4**
(siyad.sajid4@auspost.com.au), commit `6c550ab`, from a **live run against the real AusPost
Confluence** (2026-08-05).

## Essence

**When the app searches a knowledge base, it must issue a query whose results a human would
accept as relevant — and when it cannot, it must say the search found nothing useful rather
than citing whatever came back.** Today the Confluence query is a bag of every extracted
keyword plus the affected-system string, concatenated into one CQL `text ~ "…"` phrase. On
the live instance it returned five pages, **none related to the incident** — a PDF of a
Teradata data model, a Service Cloud navigation guide — and those became cited evidence in
the report.

## Why this is a concept, not a query tweak

Because "five irrelevant pages" and "zero pages" are *different failures with the same
remedy shape*, and only one of them is currently visible.

The report's §4 shows the relevance failure. Its §1 shows the same query returning a 404
(a separate defect, FND-83). But the code path treats both identically: `search()` catches
everything and returns `List.of()` ([`RealConfluenceGateway.java:60-62`](../../../../src/main/java/com/company/triage/gateway/real/RealConfluenceGateway.java#L60)),
so *"the search failed"*, *"the search found nothing"* and *"the search found five useless
things"* are indistinguishable downstream. Fixing the query string alone leaves the app
confidently citing junk the next time relevance drops; fixing the error signalling alone
leaves the query bad. The concept is **what a knowledge search promises**, which covers how
the query is built, how the results are judged, and what is said when they don't hold up.

## Evidence — what the field report found

| # | What | Where | Failure |
|---|---|---|---|
| 1 | Query is a keyword bag plus a sentence fragment | [`IncidentSignals.java:137`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L137) (`kw + " " + app`) | Issued live: `"hazards being recorded handheld appearing delivery application attached Hazards being recorded on"` — 12 terms, with the app fragment duplicating words already in the keyword list. **MEDIUM** |
| 2 | Irrelevant results are cited as evidence | [`DeterministicDiagnosisEngine.java:155-159`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L155) | All five returned pages became `e-kb-*` Evidence entries. A judge reading the report sees "Teradata Transportation and Logistics Data Model" cited as a runbook for a handheld-scanner incident. **MEDIUM** |
| 3 | Failure and emptiness are the same value | [`RealConfluenceGateway.java:60`](../../../../src/main/java/com/company/triage/gateway/real/RealConfluenceGateway.java#L60) | The §1 404 produced `0 page(s)` in the trace and a `missingInformation` line claiming *"No runbook or known-error page matched the symptom terms"* — asserting a successful empty search that never happened. **MEDIUM** (independently found by the 2026-08-05 review as a cross-gateway pattern) |

Note the corroboration: the app string in finding #1 is *"Hazards being recorded on"* — the
[J24](../J24-servicenow-field-fidelity/README.md) defect. **J24 is a prerequisite**: with the
CI parsed correctly the query's system term becomes "Delivery Hazards", which fixes part of
this by itself. J25 is what remains once that term is right.

## Design

### KQR-1 — use `siteSearch`, and send the symptom as written

> **REVISED BY MEASUREMENT, 2026-08-05.** What follows replaces this section's original
> design, which called for a structured two-part query of IDF-ranked keywords and explicitly
> **rejected** sending the raw subject line. Both halves of that reasoning were wrong, and
> the error is instructive: it assumed the problem was *which words we send*, when the
> problem was *which operator we send them to*.

Run against the real instance (INC0010010, top 8 titles scored for Delivery Hazards
relevance):

| CQL | results | relevant |
|---|---|---|
| `text ~ "<the app's own query>"` | 8 | **0** |
| `text ~ "Delivery Hazards"` | 8 | **0** |
| `text ~ "Delivery Hazards" AND type = page` | 8 | **0** |
| `siteSearch ~ "<the SAME app query>"` | 8 | **8** |
| `siteSearch ~ "Delivery Hazards"` | 8 | **8** |
| `siteSearch ~ "<the subject line>"` | 8 | **8** |

Row 4 settles it: the identical noisy string that returned nothing useful under `text`
returns entirely relevant results under `siteSearch`. `text ~` is a raw content match with
**no relevance ranking**; `siteSearch ~` is the operator backing Confluence's own UI search.
That is the whole explanation for the field report's central puzzle — the same words worked
in the UI and failed in the app.

**The rule**: `siteSearch ~ "<symptom as written>[ + app]" AND type = page`, with the app
appended only when it came from the CMDB (J24/SFF-2) and is not already in the text.

The subject line beats the keyword bag, measurably. With `siteSearch` doing the retrieval,
subject-line queries surfaced `INC2616763 - Hazards captured on handhelds not being saved` —
a **prior incident of the same fault** — plus `UC17.30 Hazards created by handheld for
facility not setup`, while the keyword bag returned only generic application documentation.
Keyword extraction discards exactly the connective structure ("not appearing in", "being
recorded on") that a relevance-ranked search uses to rank one hazard page above another. A
prior incident for the same symptom is the most useful single thing a triage can surface, and
only the unabridged form finds it.

Still rejected, and now for a tested reason: field-scoped conjuncts (`title ~ … AND text ~ …`).
`siteSearch` already ranks across title and body; splitting the query into conjuncts narrows
the result set without improving its ordering.

### KQR-2 — a result must clear a relevance floor to become evidence

Returned pages are scored on term overlap with the system + symptom terms (title weighted
above body). Pages below a configured floor are **not cited**. If nothing clears the floor,
the trace says so — *"5 page(s) returned, none matched the symptom terms"* — and
`missingInformation` records it honestly.

Rejected: citing everything and letting the reader judge. The report is advisory and
evidence-first; an evidence list padded with a Teradata appendix damages the credibility of
the entries that *are* real. Precision over recall, the same trade FND-67 already made for
contact extraction.

Rejected: asking the model to filter (ADK path only). It would not help the deterministic
engine, which is the fallback that must work with no LLM at all.

### KQR-3 — attachments and non-page content are excluded by default

Three of the five live results were file attachments (`att98078596`, `att97848644`,
`att97811000`) whose "snippet" is a filename. Add `type=page` to the CQL. Configurable, in
case a space keeps its runbooks as attached PDFs.

### KQR-4 — a failed search is not an empty search

`search()` distinguishes *failed* from *empty* — a typed result or a thrown gateway exception
the engine converts into a **failed** trace row and a `missingInformation` line naming the
system as unreachable. Today's blanket `catch → List.of()` is the specific mechanism that let
the §1 404 masquerade as a clean no-match for a full day.

This is the same rule [J14](../J14-fallback-real-input-robustness/README.md) applies to
per-connector degradation; J25 owns the Confluence instance of it because the relevance work
above changes the same call site. **Coordinate — do not implement twice.**

## Verification

| Guarantee | Test |
|---|---|
| KQR-1 | `RealConfluenceGatewayTest#searchUsesSiteSearchNotRawTextMatch` (offline, pins the operator) · `IncidentSignalsTest#confluenceQueryCombinesTheSymptomAsWrittenWithTheAffectedSystem` and `#anInferredAppIsNotAppendedToTheConfluenceQuery` · **live**: `RealConfluenceGatewayLiveTest#aDeliveryHazardsIncidentRetrievesDeliveryHazardsPages`, plus a control (`#evenTheOldNoisyQueryIsRelevantNowThatTheOperatorIsCorrect`) that fails if the fix ever silently degenerates into a query-text fix |
| KQR-2 | `DeterministicDiagnosisEngineTest#irrelevantPagesAreNotCited` — the five live titles from the report as the canned result set; asserts zero `e-kb-*` Evidence and the honest trace line |
| KQR-3 | `RealConfluenceGatewayTest#searchRequestsPagesOnly` (offline) · **live**: `RealConfluenceGatewayLiveTest#resultsArePagesNotAttachments` |
| KQR-4 | `RealConfluenceGatewayTest#httpErrorIsNotAnEmptyResult` — a canned 404 (the report's own response body) must not yield `List.of()` silently |

Baseline to hold: 152 default / 201 adk, both green.

## Out of scope

- **The `/wiki` base-URL 404** (report §1's root cause) → FND-83. KQR-4 makes that class of
  failure *visible*; FND-83 stops this specific one happening.
- **The CI parse that poisons the system term** → [J24](../J24-servicenow-field-fidelity/README.md), a prerequisite.
- **Evidence id uniqueness and citation rules** → [J13](../J13-evidence-citation-integrity/README.md).
- **Sumo and GitLab query construction** → J24 (SFF-3) and [J13](../J13-evidence-citation-integrity/README.md) respectively.
- **The ADK path's own query building** — the model composes its own `search_confluence`
  query from the instruction at [`AdkDiagnosisEngine.java:107-109`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L107).
  KQR-2/3/4 apply to it unchanged (they are server-side); KQR-1 does not.
