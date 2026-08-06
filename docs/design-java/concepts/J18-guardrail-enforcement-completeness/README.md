# J18 — Guardrail Enforcement Completeness (a bound is owned by the boundary, not by the caller)

**State**: 🟡 **Mostly built** (2026-08-06) — GEC-1/GEC-2 (allowlist moved to the gateway boundary, closing the find_recent_committers bypass), GEC-3 (encoded-query values sanitised) and GEC-4 (reflection-driven escape test) landed. **GEC-5 landed 2026-08-06** for the Sumo half — the model term is constrained where the query is built (a bare token, else dropped), so it can narrow within the app-composed scope but never widen it. The Confluence half was answered 2026-08-06 and could NOT be closed as a no-op: a trailing backslash escapes the closing quote the gateway adds, swallowing the AND type = page clause and silently dropping KQR-3's page filter. Fixed the same way as the quote. **All six rules built.** **GEC-6** is already covered by J13/ECI-4 (MAX_CODE_EVIDENCE) · **Complexity**: Moderate ·
**Priority**: MEDIUM ·
**Depends on**: J2 (agent + `BoundsCallback`), J5 (ServiceNow gateway), J6 (tools), J8 (guardrails) ·
**Amends**: J8 (the three-layer bounds inventory — one of its two claims is per-*tool*, not
per-*gateway*), J5 (ServiceNow query construction) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 2 confirmed findings
(+ 1 unverified tail item and 2 same-shape sites found by this card's own code read)

## Essence

Every value that a **model or a ticket** controls and that ends up **touching a real system**
is constrained *at the boundary it crosses* — not at whichever caller happened to reach that
boundary first. Today two bounds J8 claims are enforced by the app are enforced at exactly
**one call site each**, and a second call site to the same resource exists and is unguarded.
This card makes the enforcement point structural so that adding a caller cannot silently
re-open a closed hole.

## Why this is a concept, not a bug fix

Both confirmed findings are one-line patches if taken alone: add `gitLabAllowlist.contains(…)`
to one more method; strip `^` from one more string. Patched that way, the repo gains a **third**
hand-copied allowlist check and a **third** ad-hoc escape, and the next tool or gateway method
inherits neither.

The history in this repo says that is exactly what happens. FND-20 bounded the Sumo window;
FND-38 then had to bound the GitLab project because `search_code` had been missed; FND-40
fixed config-vs-behaviour drift for Sumo scopes and **missed the GitLab case, which FND-62
then fixed** (`DeterministicDiagnosisEngine.java:207-210` says so in a comment). FND-58
constrained the incident number and wrote an Escape note generalising the rule to "any input
that becomes part of a downstream query string" — and that generalisation was never applied to
the three other query strings this app builds. Four rounds, same defect, each closed at the
instance.

The concept is the missing rule: **the bound lives with the thing it protects**. A GitLab
project allowlist belongs to `GitLabGateway`, not to `TriageMateTools.searchCode`. A ServiceNow
encoded-query charset belongs to the code that builds `sysparm_query`, not to each of its five
callers. Decided together, the two findings and the three adjacent sites collapse into one
enforcement point per boundary plus one test per boundary that fails when a new caller appears.

## Evidence — what the review found

| # | What | Where | Sev | Failure scenario |
|---|---|---|---|---|
| 1 | `find_recent_committers` bypasses the GitLab project allowlist that FND-38 added to `search_code` | [`src/main/adk/java/com/company/triage/agent/TriageMateTools.java:177`](../../../../src/main/adk/java/com/company/triage/agent/TriageMateTools.java#L177) (check present at `:157`) | MEDIUM | With `triage.connectors.gitlab=real`, a poisoned incident tells the agent to call `find_recent_committers(project="security/secrets", filePath="vault.yml")`. The app issues `GET /api/v4/projects/security%2Fsecrets/repository/{tags,commits}` with its `PRIVATE-TOKEN`, returns committer names + emails from a project outside the allowlist, and those contacts land in `suggestedContacts` — which J5 auto-writes back into the ticket. |
| 2 | ServiceNow encoded-query injection via model/ticket-controlled tool arguments (FND-58 covered only the incident number) | [`RealServiceNowGateway.java:148`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L148) (`nameLIKE`+app) and [`:134`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L134) (`stateIN6,7^short_descriptionLIKE`+kw) | MEDIUM | A poisoned `cmdb_ci` or `short_description` of the form `x^ORname!=NULL^ORDERBYsys_created_on` flows unescaped into `sysparm_query`. `RestClient` percent-encodes it, but ServiceNow decodes `%5E` back to `^` and parses it **as a query operator**, broadening the read beyond the intended `nameLIKE` match. Reachable on the **default deterministic engine** — `DeterministicDiagnosisEngine.java:137` passes the raw `cmdb_ci`, and `:126` the raw `short_description`. |

**Verifier notes that sharpen these** (both HIGH confidence, both `real: true`):

- On #1: `BoundsCallback` allowlists **tool names only** and never arguments
  ([`BoundsCallback.java:54-59`](../../../../src/main/adk/java/com/company/triage/agent/BoundsCallback.java#L54)),
  so it does not close the gap; `MockGitLabGateway` has no check either. The instruction only
  *asks* for the ordering ("ONLY for … the file you already tied", `AdkDiagnosisEngine.java:114-116`)
  — advisory text, not enforcement. The write-back exfiltration tail is "somewhat speculative";
  the defect itself — a bound enforced on one of two GitLab tools — is exact.
- On #2: refutation attempts failed. URL-encoding is not a mitigation (documented glide
  decode-then-parse behaviour); `firstKeyword`'s whitespace split is not one either (a
  space-free payload passes intact). The **honest qualifier**: the primitive is GET-only,
  same-table, same-credential, and `rows()` sets `sysparm_limit=10`
  ([`RealServiceNowGateway.java:255-263`](../../../../src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java#L255)),
  so impact is a broadened read inside one table, never a write or a cross-table escalation.
  That is why it is MEDIUM and not HIGH.

**Narrowing #1 (this card's own read, not the review's).** The deterministic engine is **not**
affected: it searches only ranked allowlist entries (`DeterministicDiagnosisEngine.java:211-216`)
and then calls `gitLab.recentCommitters(h.project(), …)` with a project that came *back* from an
allowlisted search (`:466-467`). The hole is model-supplied arguments on the ADK path only. Do
not write the card's fix as if both engines were leaking.

**Two same-shape sites found by reading the code for this card** (⚠️ *not* review findings —
one-look verification needed before acting):

- [`LogSearchRequest.java:31-37`](../../../../src/main/java/com/company/triage/model/LogSearchRequest.java#L31)
  concatenates the model's free-text `query` straight onto `_sourceCategory=… and _index=…`.
  The record's own javadoc (`:6-10`) claims the app composes the scope and "guardrails … are
  enforced before this reaches the gateway" — but the one field the model fills sits in the
  same string as the scope clause. **Unverified premise**: whether Sumo parses a bare `or` in
  the search-expression position. If it does, the composed-scope guarantee is defeatable by
  the model.
- [`RealConfluenceGateway.java:45`](../../../../src/main/java/com/company/triage/gateway/real/RealConfluenceGateway.java#L45)
  builds `text ~ "<query>"` and strips `"` only. Lower risk than the other two (an operator
  cannot escape a quoted CQL literal without a quote), but a trailing backslash is a residual
  unknown and it is the same *shape*.

**Unverified tail item folded in here** (LOW, `DeterministicDiagnosisEngine.java:466`): uncapped
code-hit fan-out — up to ~20 `e-code` evidence entries and ~40 GitLab API calls in one run,
because every `CodeSearchResult` gets a `recentCommitters` lookup (2 HTTP calls each) with no
cap. Relevant to *this* card because it is the same guarantee seen as a quantity: the ADK path
has a call budget (`triage.agent.max-tool-calls: 10`, `application.yml:67`), and the
deterministic path has **none**.

## Design

### GEC-1 — The rule: a bound is owned by the boundary it protects

One sentence an implementer can apply without re-reading this card:

> If a value decides **which** real resource is touched, the check belongs to the gateway
> interface. If a value is **interpolated into a query language**, the check belongs to the
> code that builds that query. Never to the caller.

Consequence: a caller may add a *nicer* error, but may never be the only place a bound exists.
This is the same ownership argument J11/LT2 already made for `ToolRegistry` — the permitted-tool
set was moved out of `AdkDiagnosisEngine` into
[`guardrails/ToolRegistry`](../../../../src/main/java/com/company/triage/guardrails/ToolRegistry.java)
so that an observability component could never *grant* permission. J18 applies the same move to
argument-level bounds, and lands them in the same package for the same reason: `src/main/java/`
compiles under a bare `mvn test`, `src/main/adk/` does not.

**Rejected**: "just add the missing `contains()` call". It fixes finding #1 and leaves the
*third* GitLab caller (whoever adds one) to repeat the omission — which is the documented
history above, four times over.

### GEC-2 — GitLab project allowlist moves to the gateway boundary

Introduce `guardrails/AllowlistedGitLabGateway` — a decorator implementing `GitLabGateway`,
wrapping the mock or real implementation, wired in the gateway Spring config for **every**
connector mode. It rejects an out-of-allowlist `project` on **both** methods before delegating,
with FND-60's self-correcting message shape (`"project not allowlisted: X — must be exactly one
of: …"`), which `TriageMateTools.searchCode:157-160` already uses verbatim.

Two arguments settled here:

- **Decorator, not a check inside `RealGitLabGateway`.** Putting it in the real gateway would
  leave the mock path unguarded, and the demo's D2/offline story runs on mocks — the guardrail
  that gets *shown* would then be a different guardrail from the one that protects. A decorator
  holds identically in both modes and needs no duplicate.
- **`TriageMateTools.searchCode` keeps its check.** Deliberate duplication of *enforcement*, not
  of *definition*: both read the same `TriageProperties` list. The tool-level throw is what
  reaches the model in time to self-correct within its 10-call budget (FND-60's whole point);
  the gateway-level throw is the guarantee. The javadoc at `TriageMateTools.java:14-15` already
  states this split ("Bounds/allowlists (J8) are applied here and re-checked in
  `BoundsCallback`"); J18 makes it true for arguments too.

`TriageMateTools.findRecentCommitters:177-180` also gains the tool-level check, for the same
budget reason — but it is now the *convenience*, not the fix.

### GEC-3 — ServiceNow encoded queries become unrepresentable-if-unsafe

`RealServiceNowGateway` builds five encoded queries by string concatenation. Replace the
concatenation with a package-private `SnowQuery` helper in the same package:

```java
SnowQuery.and(SnowQuery.in("state", "6", "7"),
              SnowQuery.like("short_description", kw));   // → stateIN6,7^short_descriptionLIKE<safe>
```

`like`/`eq`/`in` sanitise the **value**; `and`/`orderBy` are the only way to emit a `^`. A caller
physically cannot concatenate an unsanitised value into a query, which is the difference between
this and "remember to escape".

**Escape is impossible, so choose strip or reject.** ServiceNow encoded queries have no escape
sequence for `^` inside a value — that is the fact that forces a decision. Options: reject the
value (throw), or strip to a conservative charset (`[A-Za-z0-9 ._/-]`).

**✅ Decided: one sanitiser, two dispositions, chosen by *who supplied the value*.**

| Value origin | Disposition | Why |
|---|---|---|
| **Model** tool argument (`find_ownership`'s `applicationName`) | **reject**, message names the charset | The model can retry inside its budget; silent stripping teaches it nothing (FND-60's rule). |
| **Ticket** text (`cmdb_ci`, `short_description` on the deterministic path) | **sanitise and continue** | The deterministic engine is D2, the demo's guaranteed fallback. A hard throw on real ticket content is precisely the FND-63 failure ("the fallback is only a fallback if it works on input the happy path never sees") — see J14. A ticket cannot retry. |

Both dispositions call the **same** predicate, so "safe" has exactly one definition.

**Honesty consequence (do not skip this).** `DeterministicDiagnosisEngine.java:140-141` traces
`servicenow.findOwnership(%s)` with the **raw** value. Once sanitisation can alter it, that line
would assert a query that was never sent — J8's flight-recorder claim and J11's honesty contract
both break. The trace must print the **sanitised** value, and when sanitisation changed anything,
say so (`servicenow.findOwnership(Order Portal) [value sanitised for query safety]`).

`addWorkNote`'s note text is **out of this rule** — it goes into a JSON body via `jsonString`
(FND-51), not into a query. Charset-restricting it would mangle advisory notes, which is the
app's entire payoff.

### GEC-4 — The escape test: a new caller cannot silently skip the bound

Per-boundary, one test whose *failure mode is a new caller*, not a new payload:

- **GitLab**: reflect over the `GitLabGateway` interface and assert that **every** method taking
  a `project` parameter is rejected by the decorator for an out-of-allowlist value. Adding a
  third method to the interface fails the test until it is guarded. This is the same idiom
  `PromptInjectionGuardrailTest:77-88` already uses (reflection over `ServiceNowGateway` to prove
  no reassign/close method exists) — reused rather than invented.
- **ServiceNow**: assert against the **outbound HTTP request**, not the helper. `MockRestServiceServer`
  is already the house style here (`RealServiceNowGatewayTest`), so: feed a `^`-bearing `cmdb_ci`
  through `findOwnership` and assert the captured `sysparm_query` contains no `^` beyond the ones
  the app itself emitted.

**Rejected**: a source-scanning test that greps for `"sysparm_query"` concatenation. Too clever,
and it fails for the wrong reasons on refactors. `SnowQuery` making the unsafe form
unrepresentable is the structural half; the HTTP assertion is the behavioural half.

### GEC-5 — Apply the same audit to the other two query languages

Same rule, two more boundaries — but they are **unverified** (see Evidence), so each starts with
a one-look check, not a patch:

- **Sumo** (`LogSearchRequest.toSumoQuery`): if a bare `or` parses in the search-expression
  position, the model's free-text `query` can widen the app-composed scope. Fix in the same
  place the query is built — `toSumoQuery()` — by constraining the model's term, not by asking
  the instruction nicely (FND-44 already records that prompt-only guardrails are prompt-only).
- **Confluence** (`RealConfluenceGateway:45`): confirm whether a backslash can terminate the
  quoted CQL literal. If it cannot, record that as a *closed* finding in this card rather than
  leaving it ambiguous — J8 already carries one deliberately-unenforced Confluence bound
  ("no space-scoping mechanism at all") and a second ambiguity there is worse than a documented
  no-op.

### GEC-6 — The deterministic engine gets a fan-out cap (⚠️ unverified tail item)

The ADK path is bounded by `max-tool-calls: 10`; the deterministic path has no call budget, so
`DeterministicDiagnosisEngine.java:466-467` issues 2 GitLab HTTP calls per code hit with no cap
(~40 in the tail item's estimate). **Decision: cap the committer fan-out at the top N code hits
(N = 5, matching the Confluence result cap of 5 already used in J6/J8), and trace the truncation**
— an untraced cap is the same honesty breach as an untraced sanitisation. Verify the ~20/~40
numbers against the file before implementing; the estimate is from an unverified LOW finding.

## Verification

The project baseline is **152 default / 201 adk**, both green. Every test below is additive.

| Guarantee | Test | Profile |
|---|---|---|
| GEC-2 — both GitLab methods reject an out-of-allowlist project | `AllowlistedGitLabGatewayTest` — `searchCode` and `recentCommitters` each throw with the FND-60 message; an allowlisted project passes through unchanged | bare `mvn test` (decorator lives in `src/main/java/`) |
| GEC-2 — the tool-level convenience check still fires | extend `TriageMateToolsSearchLogsTest` (which today holds `outOfAllowlistGitLabProjectIsRejected` for `search_code` only, `:141-147`) with the `find_recent_committers` twin | **`-Padk`** — `TriageMateTools` is in `src/main/adk/` |
| GEC-3 — sanitiser definition | `SnowQueryTest` — `^`, `=`, `!` and control characters never survive into a value; `and`/`in`/`like` compose the expected literal | bare |
| GEC-3 — disposition split | `RealServiceNowGatewayTest` (new cases) — a `^`-bearing `cmdb_ci` reaches the wire sanitised via `findOwnership`; `TriageMateTools.findOwnership` **throws** for the same input | bare + **`-Padk`** for the throw |
| GEC-3 — honesty | `DeterministicDiagnosisEngineTest` — the `servicenow.findOwnership(…)` trace line shows the sanitised value and flags that it was altered | bare |
| GEC-4 — escape test | `GitLabGatewayBoundsCoverageTest` — reflection over the interface; fails when an unguarded `project`-taking method is added | bare |
| GEC-6 — fan-out cap | `DeterministicDiagnosisEngineTest` — 12 code hits produce at most 5 committer lookups and a trace line saying so | bare |

`RealSumoGatewayLiveTest` makes a live network call and fails offline — that is an environment
failure, not a regression from this card.

## Out of scope

- **Whether the model should be trusted with tool arguments at all** (a per-step allowlist, an
  argument schema enforced by `BoundsCallback`) — J2/J8 already settled that there is one global
  tool-name allowlist and no per-step one (J8's FND-13 correction). This card bounds *values*,
  not the *shape* of the agent loop.
- **The duplicate `"e-code"` evidence id** at `DeterministicDiagnosisEngine.java:218` — every code
  hit reuses one id. Noticed while reading for GEC-6; it is an evidence-integrity defect and
  belongs to **J13-evidence-citation-integrity**.
- **`TriageProperties` accepting a null `allowed-projects`** (and the Sumo pattern/environment
  gaps), which defers failure from boot to the first tool call — **J20-startup-truth-and-validation**.
- **The instruction text that names the allowlisted values** — FND-60 shipped it
  (`AdkDiagnosisEngine.java:138-149`, `AdkAllowlistVisibilityTest`); keeping it in step with
  config is **J19-instruction-config-fidelity**.
- **Whether the app should be reachable off-host at all** (bind address, auth, CORS) —
  **J21-network-exposure-posture**.
- **Contract tests that prove the real gateways speak the API they claim** —
  **J22-real-gateway-contract-tests**.
