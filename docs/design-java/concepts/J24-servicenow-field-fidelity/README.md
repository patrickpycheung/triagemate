# J24 — ServiceNow Field Fidelity (the incident's own fields must survive the parse)

**State**: 🟢 Built (SFF-1…SFF-5, 2026-08-05) — reference-field unwrapping, app-provenance
disclosure, unmatched-scope skip, the environment ladder, and the captured-response fixture
all landed; `mvn test` 166 / `mvn -Padk test` 222 green. The deferred alias-map question in
SFF-3 remains open and is explicitly non-blocking · **Complexity**: Moderate · **Priority**: HIGH
**Depends on**: J3 (gateway contracts), J5 (ServiceNow gateway), J2 (both engines consume `IncidentContext`)
**Amends**: J5 (`getIncident` field contract), J2 (FND-67's premise — see [Correction](#correction-to-fnd-67)), J14 (absorbs its `openedAt` finding's root cause)
**Source**: teammate field report — `docs/Siyad_Findings.md` §2 and §3, by **sajids4**
(siyad.sajid4@auspost.com.au), commit `6c550ab`, from a **live run against the real
AusPost ServiceNow instance** (INC0010010, 2026-08-05). Corroborated against the
application review of 2026-08-05 (which found the downstream symptoms but not this cause).

## Essence

**Every field the real ServiceNow instance actually returns must reach `IncidentContext`
with its value intact.** Today reference fields — `cmdb_ci`, `caller_id`,
`assignment_group` — arrive as JSON *objects* (`{"display_value": …, "link": …}`) and are
silently parsed to the **empty string**. The single most load-bearing field in the whole
application, the configuration item that names the affected system, is therefore blank on
every real incident. Everything downstream then derives the affected app from the ticket's
*subject line* instead, and the error propagates into the Sumo scope, the candidate
systems, and the Confluence query.

## Why this is a concept, not a one-line parse fix

Because the blank CI has already been **designed around** twice, and both workarounds were
built on the belief that the field was genuinely empty on real tickets.

- `IncidentSignals.from()` ([`IncidentSignals.java:97`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L97))
  falls back to `leadingPhrase(symptom)` when `configurationItem` is blank.
- FND-67 hardened `DeterministicDiagnosisEngine` against an *empty-named candidate system*
  ([`DeterministicDiagnosisEngine.java:301-312`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L301))
  after observing exactly this blank on the first real ticket.

Fix the parse alone and both fallbacks stay in place as dead-but-plausible code, and the
next person reading FND-67 is told a false thing about how real ServiceNow behaves. Fix the
fallbacks alone and the app keeps guessing a system it was handed. The two must move
together, which is one decision about *where the affected system comes from* — hence a card.

## Evidence — what the field report found

| # | What | Where | Failure |
|---|---|---|---|
| 1 | Reference fields parse to `""` | [`RealServiceNowGateway.java:271-274`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L271) (`text()`) | `cmdb_ci` arrives as `{"display_value":"Delivery Hazards","link":"…"}`; `JsonNode.asText()` on an `ObjectNode` returns `""`. Logged live: `servicenow.getIncident(INC0010010) → CI=, env=null` while the raw row plainly shows `display_value: "Delivery Hazards"`. **HIGH** |
| 2 | Affected app derived from the subject line | [`IncidentSignals.java:97`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L97) | With CI blank, `app` = `leadingPhrase("Hazards being recorded on handheld are not appearing…")` = **"Hazards being recorded on"** — a sentence fragment treated as a system name. **HIGH** |
| 3 | Sumo scope built from that fragment | [`DeterministicDiagnosisEngine.java:174-177`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L174) | Sent: `_sourceCategory=IDT/ITServices/Tomcat/hazards-being-recorded-on/prod/AppEvt_hazards-being-recorded-on`. Expected: `…/delivery-hazards/ptest/AppEvt_delivery-hazards`. Zero rows, every time. **HIGH** |
| 4 | Environment unresolved | same log line (`env=null`) | `u_environment` is absent on this instance, so `environmentCode` falls to the `prod` default while the real target is `ptest` — the second wrong component of the same scope. **MEDIUM** |

The report's own conclusion — *"The fix for item 2 may fix this issue as well"* — is correct:
#2, #3 and the app half of #4 are one bug. #4's environment half is not, and is treated
separately in SFF-4.

## Correction to FND-67

FND-67 records: *"The first real ServiceNow ticket had cmdb_ci = "" (empty, not null), so
the report shipped a candidate system with an EMPTY NAME."* The observation was accurate;
the **diagnosis was not**. The CMDB was not empty — the value was `"Delivery Hazards"` and
the parse dropped it. FND-67's blank-check is still worth keeping as a guard (a CI genuinely
can be unset), but its *rationale comment must be corrected* so nobody later concludes that
real ServiceNow instances routinely ship incidents with no configuration item. This is the
FND-8 class applied to our own documentation: a comment that narrates something that did not
happen.

## Design

### SFF-1 — `text()` unwraps a reference field; a new `refText()` is not optional

`text(node, field)` returns, in order: the scalar text if the node is a value node; the
`display_value` if the node is an object carrying one; `null` if the node is missing, null,
or an object without `display_value`. **Never the empty string for a present value.**

Rejected: adding `sysparm_exclude_reference_link=true` to the query and leaving `text()`
alone. It would work for `getIncident`, but it makes correctness depend on every future
caller remembering a query parameter — the same caller-owns-the-bound mistake
[J18](../J18-guardrail-enforcement-completeness/README.md) exists to eliminate. The parse is
the boundary; the boundary owns the rule.

Rejected: `asText("")` with a downstream blank-check. That is what the code effectively does
today, and it is why the value's absence looked like the CMDB's silence.

### SFF-2 — a blank CI means "the ticket does not say", and the report must say so

With SFF-1 in place, a blank `configurationItem` becomes rare and *meaningful*. The
`leadingPhrase` fallback stays — but it stops being invisible:

- `IncidentSignals` records **how** `app` was derived (`FROM_CMDB_CI` vs `FROM_SUBJECT_LINE`).
- When it is `FROM_SUBJECT_LINE`, the report adds a `missingInformation` line: *"The ticket
  has no configuration item; the affected system was inferred from the subject line."*
- The trace row for step 2 states which source was used.

This is the honesty contract applied to a *derivation*, not just a fetch: guessing is
allowed, guessing silently is not.

### SFF-3 — the Sumo scope names the system, or the search is skipped

`projectSlug` is only meaningful for a real system name. A slug derived from a sentence
fragment (`hazards-being-recorded-on`) can never match a real `_sourceCategory`, so issuing
it burns a call and reports "0 lines" as though the system had been searched and found
quiet — which is a false negative presented as evidence.

**Rule**: when `app` came `FROM_SUBJECT_LINE` *and* the derived slug matches no configured
project, skip the Sumo call, emit the trace row as **skipped with the reason**, and record
it in `missingInformation`. Same discipline as the existing `gitlab.searchCode → skipped
(no error token…)` row at [`DeterministicDiagnosisEngine.java:234`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L234).

**Operator decision — deferred, not blocking**: whether to add a configured
`app-name → project-slug` alias map (so "Delivery Hazards" reliably reaches
`delivery-hazards` even when the CMDB label and the Sumo slug disagree on wording). SFF-3
works without it; the map is a demo-quality improvement whose value depends on how the real
AusPost slugs are named, which only the operator can see.

### SFF-4 — environment resolution has a documented ladder

`u_environment` does not exist on the live instance, so `environmentCode` silently defaults
to `prod` — and quietly pointed the search at the wrong environment (`prod` for a `ptest`
system). The ladder becomes explicit and disclosed: `u_environment` → a configured
per-project default → the global default, with the trace naming which rung answered, and a
`missingInformation` line whenever the answer came from a default rather than the ticket.

Rejected: inferring the environment from the CI name. It reads as clever and fails silently
in exactly the cases it matters.

### SFF-5 — the field contract is pinned by a captured real response

`RealServiceNowGatewayTest` gains a `MockRestServiceServer` case whose canned body is
**the row from the field report verbatim** — objects for `cmdb_ci`/`caller_id`, empty-string
`assignment_group`, `subcategory: null`, `opened_at` in `yyyy-MM-dd HH:mm:ss` — asserting
`configurationItem() == "Delivery Hazards"` and `caller()` non-blank. Offline, in the default
profile. This is [J22](../J22-real-gateway-contract-tests/README.md)'s rule applied to the
one gateway that *did* have tests and still shipped this: the tests existed, but every
fixture was written by us, so they asserted our beliefs about ServiceNow rather than
ServiceNow's behaviour.

## Verification

| Guarantee | Test |
|---|---|
| SFF-1 | `RealServiceNowGatewayTest#referenceFieldsUnwrapToDisplayValue` — object-shaped `cmdb_ci` → `"Delivery Hazards"`; scalar-shaped → unchanged; absent → `null`, never `""` |
| SFF-2 | `IncidentSignalsTest#appProvenanceIsRecorded` (both branches) + a `DeterministicDiagnosisEngineTest` asserting the `missingInformation` line on the subject-line branch |
| SFF-3 | `DeterministicDiagnosisEngineTest#sumoSearchSkippedWhenAppWasInferredAndUnmatched` — asserts no `SumoGateway.search` call and a skipped trace row |
| SFF-4 | `IncidentSignalsTest#environmentLadder` — ticket value wins; per-project default next; global default last, each disclosed |
| SFF-5 | The captured-response case above, in default `mvn test` |

Baseline to hold: 152 default / 201 adk, both green.

## Out of scope

- **The Confluence 404 and query relevance** (report §1 and §4) → FND-83 and
  [J25](../J25-knowledge-query-relevance/README.md).
- **Null/display-format `openedAt`** → [J14](../J14-fallback-real-input-robustness/README.md)
  already owns it. J24 explains *why* the same class of bug exists (`parseTime` and `text()`
  were both written against a fixture, not an instance) but does not re-solve it.
- **`leadingPhrase`'s hyphen-splitting defect** → [J14](../J14-fallback-real-input-robustness/README.md).
  SFF-2 makes that path rarer; it does not make it correct.
- **Evidence-id and citation rules** → [J13](../J13-evidence-citation-integrity/README.md).
