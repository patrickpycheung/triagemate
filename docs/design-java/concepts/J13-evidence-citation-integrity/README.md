# J13 — Evidence & Citation Integrity (the citation contract made enforceable)

**State**: 🟢 **Built** — all six ECI rules. **ECI-6 landed 2026-08-06** (validation moved inside `runAgentAndParse`, so a contract violation now costs one repair turn instead of an FND-7 degrade; the loop this validator's javadoc always described is finally wired). ECI-1…ECI-4 were already built and tested. **ECI-5 landed 2026-08-06** — typed identifier fields populated only from a signal that determined that field, built after the log search so errorCode can carry the Sumo-derived token. **All six rules built.** `mvn test` 248 / `mvn -Padk test` 307 green · **Complexity**: Moderate · **Priority**: HIGH ·
**Depends on**: J4 (report contract), J2 (ADK engine), J3 (connector tools) ·
**Amends**: J4 (adds two enforceable rules to the validator and an id-uniqueness clause to
the contract), J8 (its bounds inventory claims a per-call result cap on `TriageMateTools`
that `search_code` does not have) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 3 confirmed
findings + 2 unverified tail items

## Essence
J4's central promise is *"every conclusion ties to `evidenceRefs`"* — the whole pitch is an
**explainable** first-pass diagnosis. Today that promise is enforced only against dangling
refs. This card makes it enforceable in the other three directions too: an `evidenceRef`
must resolve to **exactly one** evidence item, that item must actually be **about the thing
citing it**, every conclusion must cite **something**, and a typed report field must be
populated only when the extraction genuinely determined that type. Where the guarantee can be
checked mechanically, `DiagnosisReportValidator` checks it — for **both** engines.

## Why this is a concept, not five bug fixes

The five findings are all one defect wearing different clothes: **the report asserts a link
it did not verify.** Fixing them piecemeal produces contradictions, because three of them
change the *same three lines*:

- Fixing the duplicate `e-code` id (ECI-1) without fixing per-candidate refs (ECI-2) just
  turns one ambiguous ref into a ref that names an arbitrary one of three files.
- Fixing per-candidate refs (ECI-2) requires deciding what evidence a non-error logger gets —
  which is the same decision as "what does the 0.86 tier mean" (ECI-3), because both are
  answered by `DeterministicDiagnosisEngine.java:282-290`, one four-line loop.
- Fixing any of them without teaching the validator (ECI-6) reproduces the exact reason this
  shipped: the validator collects ids into a `Set`
  ([`DiagnosisReportValidator.java:43-44`](../../../../src/main/java/com/company/triage/model/DiagnosisReportValidator.java#L43-L44)),
  which **silently collapses duplicates**, so a report that violates id-uniqueness validates
  clean. A one-off code fix is a fix; a validator rule is a guarantee.

And the enforcement decision is genuinely shared: on the ADK path a validation failure at
[`AdkDiagnosisEngine.java:440`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L440)
throws *after* the agent loop has already finished, so **every new hard rule is a new way to
degrade to the fallback mid-demo**. That has to be decided once, for all the rules, not five
times (ECI-6).

## Evidence — what the review found

| # | Finding | Where | Severity | Failure |
|---|---|---|---|---|
| 1 | Every GitLab code hit gets the literal id `"e-code"` | [`DeterministicDiagnosisEngine.java:217-221`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L217-L221) | MEDIUM | Real GitLab returns the whole first page of blob matches (no `per_page` at [`RealGitLabGateway.java:50-55`](../../../../src/main/java/com/company/triage/gateway/real/RealGitLabGateway.java#L50-L55), so the API default ~20). An error token appears in the emitting file *plus* its unit test *plus* a README → three evidence entries share one id, and `evidenceRefs:["e-log","e-code"]` no longer identifies **which** file backs the citation. Validator passes. |
| 2 | Candidates cite `e-log` even when it is another system's line, or cite nothing at all | [`DeterministicDiagnosisEngine.java:181-188`, `:282-290`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L282-L290) | MEDIUM | Only the **first ERROR** line becomes evidence. Every log-derived candidate — including 0.45 candidates built from a *different* logger's WARN line — cites it. **This fires on the demo fixture**: [`MockSumoGateway.java:21-29`](../../../../src/main/java/com/company/triage/gateway/mock/MockSumoGateway.java#L21-L29) has `payment_service` ERROR + `order_api` ERROR/WARN, so the "Order Api" candidate cites the *payment_service* line on every run. Variant: a WARN-only window ⇒ `errorLine == null` ⇒ no Sumo evidence at all ⇒ candidates ship with `evidenceRefs: []` — uncited conclusions in a report whose selling point is citations. |
| 3 | The 0.86 "log↔code citation" tier never checks the code hit belongs to that system | [`DeterministicDiagnosisEngine.java:281`, `:284-287`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L281-L287) | MEDIUM | `codeBackedSystem` is computed at :281 and then used **only as a null check** — never compared to the candidate name. The allowlist sweep (`:211-216`) stops at the first project with a textual match, which need not be the logger's system. A generic token ("upstream timeout exceeded") found in `order-api`'s retry wrapper promotes **Payment Service** to 0.86 citing code in *another repo*, while the system that provably emits the line never appears as a candidate at all (candidates come only from loggers + CMDB). |
| 4 | Uncapped code-hit fan-out — ~20 `e-code` entries and ~40 GitLab calls per run · **unverified (tail)** | [`DeterministicDiagnosisEngine.java:466-470`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L466-L470), [`TriageMateTools.java:154-161`](../../../../src/main/adk/java/com/company/triage/agent/TriageMateTools.java#L154-L161) | LOW | Each hit drives a `recentCommitters` lookup = 2 REST calls, and the sources note renders one bullet per hit. Neither the engine nor `search_code` truncates. |
| 5 | `Identifiers` duplicates one extracted id into two typed fields and drops the error token · **unverified (tail)** | [`DeterministicDiagnosisEngine.java:119`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L119) | LOW | `new Identifiers(orderId, null, orderId)` — the same string is asserted to be both a correlation id and an order id, and `errorCode` is always `null` even when `errorToken` was extracted 60 lines later. |

**The verifier sharpened two of these.** On #1 it confirmed the fix is cheap *and* that
`e-code` is the sole violation: `e-sim-<number>` (`:128`) and `e-kb-<docId>` (`:157`) are
unique by their source key, and `e-incident`/`e-cmdb`/`e-log` are emitted once per report —
`e-code` is the one id minted inside a loop. On #2 it established reachability on the
**mock** path, not just the real one, which is what moves this off "real-connectors only".

**Two claims I checked myself and am narrowing:**

- **#4's worst case is latent, not current.** `RealGitLabGateway.searchCode` URL-encodes the
  project (`group%2Fname`) at
  [`:49`](../../../../src/main/java/com/company/triage/gateway/real/RealGitLabGateway.java#L49)
  and then passes it as a `RestClient` template variable at `:50-54`, which encodes again
  (`%252F`) — so real-mode code search most likely 404s today and the ~20-hit page is never
  reached. That is **J22's** defect, not this card's. The cap is therefore cheap insurance
  bought *before* J22 makes the fan-out reachable, not a fix for observed cost. Sizing the
  cap now is still right: J22 unblocking real search should not simultaneously ship a 40-call
  fallback.
- **J8's bounds inventory overstates one line.** It lists "per-call result caps" for
  `TriageMateTools`. True of `search_logs` (`sumoMaxResults`, `TriageMateTools.java:148`);
  **not** true of `search_code` (`:154-161`), which applies the allowlist and returns whatever
  the gateway returns. ECI-4 makes the claim true rather than editing it away.

## Design

### ECI-1 — Evidence ids are unique **by construction**, and the validator says so

**Rule**: within one report, `Evidence.id` is unique. Nothing enforces this by convention —
the loop-minted family gets an index.

**Mechanism**: `e-code-<n>`, 1-based in hit order (`e-code-1`, `e-code-2`, …). Candidates and
`recommendedNextAction` cite the *specific* hit they are talking about — which, for the
nextAction at `:356-359`, is already `codeHits.get(0)`, i.e. `e-code-1`.

⛔ **Rejected: content-derived ids** (`e-code-<hash of project/path>`). Stable-looking but
unreadable, and the identity a reader needs is positional ("the first hit, the one the
nextAction names"), not content-addressed. The existing families
(`e-sim-<number>`, `e-kb-<docId>`) already establish "suffix with whatever makes it unique";
an index is the honest suffix when the source key is a list position.

### ECI-2 — A candidate cites **its own** evidence, and never cites nothing

**Rule**: every candidate's `evidenceRefs` contain only evidence *about that candidate's
system*, and the list is never empty.

**Mechanism**: emit one Sumo `Evidence` per **distinct logger that contributed a candidate**,
not one per report. Id `e-log-<n>`, numbered in logger first-appearance order. Each logger's
evidence quotes **its own best line** — highest severity first (`ERROR > WARN > INFO`), first
occurrence within a tier — so a WARN-only window still produces cited evidence instead of
none. The candidate loop at `:282-290` then refs `e-log-<n>` for *its* logger.

The `errorToken` extraction (`:183`) stays keyed on the first ERROR line, unchanged: that is
a *search-term* decision, and step 6 legitimately searches for the most severe token in the
window. Citation and search-term selection were conflated in one `errorLine` variable; this
separates them.

⛔ **Rejected: keying the id on the logger name** (`e-log-payment_service`). Loggers are not
clean tokens — the real Sumo connector can return a `_sourceCategory`-shaped logger
(`prod/order-portal/payment-service`), which is J14's finding, and an id built from it would
be both ugly and collision-prone once J14 normalises those strings. A positional index is
stable under whatever J14 decides.

### ECI-3 — The 0.86 tier means what its comment says

**Rule**: a candidate is promoted to 0.86 **only when the cited code hit's system equals that
candidate's system** — i.e. `prettifySystem(hit.project()).equals(name)`. Otherwise the
existing ladder applies (0.70 for an ERROR logger, 0.45 otherwise). This makes the comment at
`:278-279` ("a system with a resolved log↔code citation ranks above one seen only in logs")
true instead of coincidentally true on the fixture. (The comment lives at `:276-279`.)

**And the code-owning system becomes a candidate.** When the hit's project matches no logger,
emit it as its own candidate citing `e-code-1` at **0.60**. The reasoning for that number is
the finding itself: "this repo contains the token's text" is *corroboration*, weaker than
"this system emitted the ERROR line" (0.70) — the match may be an incidental retry wrapper —
but clearly stronger than "this system logged a WARN" (0.45). Slotting it between the two is
the only placement consistent with the ladder already documented in the code.

**One consequential rendering follows.** `recommendedNextAction` at `:358` says the file
"emits '<token>' — the log line correlated to this incident." When the systems disagree that
sentence asserts a correlation this card just established we cannot make. Word that branch as
*"the token also appears at `<file>:<line>` in `<project>` — confirm whether it is the
emitter."* Same fix, same class (FND-8: do not narrate what did not happen), and it is one
`String` branch away from the confidence decision that produced it.

### ECI-4 — One configured cap on code-hit fan-out · addresses an **unverified** finding

**Rule**: at most `triage.gitlab.max-code-hits` code hits (default **3**) reach evidence,
contacts, or the model.

**Mechanism**: one property on `TriageProperties.gitlab()`, read by the two consumers —
`DeterministicDiagnosisEngine` truncates after the allowlist sweep, and
`TriageMateTools.searchCode` truncates before returning to the model. `RealGitLabGateway`
additionally passes it as `per_page` so the wire cost drops too, but the **contract is
enforced at the consumers**, so it holds for every gateway implementation including the mock
and any future one. Dedupe `recentCommitters` by `(project, filePath)` — with a cap of 3 the
saving is small, but two hits in one file at different lines is the common shape.

⛔ **Rejected: capping only in `RealGitLabGateway`.** That is the FND-40/FND-62 class this
project has fixed twice — a bound living in one implementation, so config and behaviour
disagree the moment a second implementation appears. One property, read by everyone who
consumes the list.

⚠️ **Verify before implementing** (this finding is unverified tail): confirm the fan-out
numbers against `RealGitLabGateway` *after* J22 settles whether real-mode search works at all.
The cap's sizing (3) is a judgement about report readability, which holds either way.

### ECI-5 — Typed identifier fields are populated only when the type was determined · addresses an **unverified** finding

**Rule**: `Identifiers.correlationId`, `.errorCode`, `.orderId` are each populated only from a
signal that actually determined *that* field. Everything else stays `null` — the record's own
javadoc already says "any field may be null", so `null` is the honest value, not a gap.

**Mechanism**: `IncidentSignals` already knows which pattern matched — `extractIdentifiers`
tries `DASHED_ID`, `UUID`, `HEX_TRACE` **in that order**
([`IncidentSignals.java:107`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L107))
and `primaryIdentifier` is `ids.get(0)` (`:101`), so the kind is derivable *there* and
nowhere else. Expose the matched kind from `IncidentSignals` rather than re-matching the
string in the engine — re-matching would be a second source of truth for the same
classification, which is precisely what FND-40 and FND-62 were about. Map `DASHED_ID →
orderId`, `UUID`/`HEX_TRACE → correlationId`.

**Build `Identifiers` after step 5, not at line 119.** `errorCode` should carry the extracted
`errorToken` (`:183`), which does not exist until the Sumo search has run. Moving the record's
construction below step 5 is the whole fix; nothing between `:119` and `:183` reads `ids`.

### ECI-6 — The validator learns the rules, and the ADK path repairs instead of degrading

**Two new hard rules** in `DiagnosisReportValidator`, applied to **both** engines:

1. **Duplicate `Evidence.id` is rejected** — `report.evidence().size()` vs the distinct-id
   count, naming the offending ids. Cheap, and it closes the exact hole that let #1 validate
   clean.
2. **A candidate with empty `evidenceRefs` is rejected** — J4's Rules text ("every conclusion
   ties to `evidenceRefs`") already implies it; the validator only ever checked *dangling*
   refs, never *absent* ones.

⛔ **Rejected: engine-asymmetric validation** (hard for deterministic, warn-only for ADK).
That is FND-39's defect by name — the validator was wired into ADK only, and the fix was to
apply it to both. Re-introducing asymmetry in the *consequence* re-opens it in a subtler form.

⛔ **Rejected: soft warnings for both.** The failure mode under review is a violation
*shipping silently*. A warning that no test asserts on is the same outcome with more logging.

**✅ DECIDED — a validation failure triggers the existing FND-42 repair turn, not a degrade.**
Today `runAgentAndParse` retries once on a **parse** failure
([`AdkDiagnosisEngine.java:686-703`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L686-L703))
while `validate` runs afterwards at `:440`, outside the retry — so a semantically-invalid
report is an immediate throw and an FND-7 degrade to the deterministic engine, on stage,
after the full ~37 s agent run. Move validation *inside* that method (parse → validate → on
either failure, one repair turn quoting the problem list) so the two failure modes share the
**one** retry the `maxToolCalls + 5` LLM budget was already sized for (`:681-682`). No new
budget, no second retry, and if the repair also fails the outcome is identical to today's.

This is not a new idea bolted on: `DiagnosisReportValidator`'s javadoc already explains that
it reports *every* violation rather than the first specifically so that **"a model retrying on
a single-line error message benefits from seeing the whole list at once"**
([`:29-31`](../../../../src/main/java/com/company/triage/model/DiagnosisReportValidator.java#L29-L31)).
The repair loop that sentence was written for was never wired. ECI-6 wires it.

**Blast radius on existing fixtures: zero.** The ~18 hand-built reports across the test suite
(`DiagnosisOrchestratorTest:52-54`, `DiagnosisReportNoteTest:21-23`,
`PromptInjectionGuardrailTest:134-136`, `DiagnosisControllerRunIdTest:55-57`, and siblings)
each use a single `Evidence("e-log", …)` with `evidenceRefs: ["e-log"]` — unique, non-empty,
and still valid under both new rules. Verified by grep; no fixture edits are required by
ECI-6 itself.

## Verification

- `DiagnosisReportValidatorTest` (currently 7 cases) gains: **duplicate ids rejected**,
  **candidate with empty `evidenceRefs` rejected**, and **a report whose candidates each cite
  a distinct existing id passes** — the third guards against a rule so strict it fails the
  happy path.
- `DeterministicDiagnosisEngineTest` — against the existing mock fixture, assert that
  (a) all `Evidence.id` values in the produced report are distinct, (b) the **Order Api**
  candidate's refs resolve to an evidence item whose summary quotes an `order_api` line, not
  the `payment_service` one (this is the demo-path regression from finding #2), and (c) no
  candidate has empty refs.
- New `EvidenceCitationIntegrityTest` — stub gateways only, no `-Padk` needed:
  - WARN-only Sumo window ⇒ every candidate still has ≥1 ref and Sumo evidence exists.
  - Code hit in a project whose `prettifySystem` differs from every logger ⇒ no candidate at
    0.86, the code-owning system appears at 0.60 citing `e-code-1`, and
    `recommendedNextAction` uses the "confirm whether it is the emitter" wording.
  - 10 code hits from the stub ⇒ exactly 3 `e-code-*` evidence items and exactly 3
    `recentCommitters` invocations (ECI-4).
- `IncidentSignalsTest` — a ticket carrying a UUID and no dashed id yields
  `correlationId` set, `orderId` null; a dashed id yields the reverse; `errorCode` is the
  Sumo-derived token, asserted end-to-end in `DeterministicEndToEndTest`.
- **Under `-Padk`**: a test that a report with a duplicate evidence id or an empty
  `evidenceRefs` triggers **one** repair turn and succeeds on the corrected response, and that
  a still-invalid repair throws exactly as today (ECI-6). This is the only part of the card
  that needs the adk profile.
- Baseline to hold: **152** default / **201** `-Padk`, both green. These additions are
  additive; ECI-6's fixture check above predicts no edits to existing assertions.

## Out of scope

- **Real-connector input shapes** — null `openedAt`, `_sourceCategory`-shaped loggers
  collapsing the candidate list, per-connector degradation → **J14-fallback-real-input-robustness**.
  ECI-2's per-logger evidence assumes loggers are distinguishable; J14 owns making them so.
- **Whether real-mode GitLab search works at all** (the suspected double-encoded project id,
  and the absence of any `RealGitLabGateway` test) → **J22-real-gateway-contract-tests**.
  ECI-4 caps a fan-out that J22 must first make reachable.
- **Allowlist enforcement on `find_recent_committers`** and encoded-query bounds →
  **J18-guardrail-enforcement-completeness**. ECI-4 caps *how many* committer lookups happen;
  J18 owns *whether each is allowed*.
- **What the instruction tells the model about evidence ids.** The prompt's
  "Every candidate/assignment must reference evidence ids you actually gathered"
  (`AdkDiagnosisEngine.java:176`) may need to name the two new rules so the model can satisfy
  them first-try instead of via the repair turn — that is instruction fidelity,
  **J19-instruction-config-fidelity**.
- **Rendering** — how the UI linkifies a resolved `evidenceRef`, and how the sources note
  orders evidence. J13 guarantees the refs are resolvable and honest; J7/J23 own what is drawn
  with them.
