# J22 — Real Gateway Request Contracts (proved offline, not on stage)

**State**: 🔴 Designed, not built · **Complexity**: Simple ·
**Priority**: MEDIUM ·
**Depends on**: J3, J6 · **Amends**: J3 (Verification — "not per-gateway" is the
gap this closes), J6 (the real Confluence/GitLab request shapes it describes are
unproven) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini),
2 confirmed findings

## Essence
Every real connector's **HTTP request shape** — full path, encoding, auth header,
and the parse of a canned response including its empty and missing-field variants —
is pinned by an offline request-capture test that runs in the default `mvn test`.
The one guarantee: *no real-connector request contract is first executed on the
corp laptop during demo prep.* The GitLab project-id double-encoding defect below
exists **because** that guarantee was never made — it is the first thing the
guarantee catches.

## Why this is a concept, not a bug fix

The two findings look like "one bug + one nice-to-have". They are the same fact
read twice. `RealGitLabGateway` and `RealConfluenceGateway` are the only two
connectors with **no test of any kind** (`src/test/java/com/company/triage/gateway/real/`
holds `RealServiceNowGatewayTest`, `RealSumoGatewayTimeFormatTest` and the opt-in
`RealSumoGatewayLiveTest` — nothing else), and the `%252F` defect sits in one of
them, in a line no test has ever executed.

Fixing the encoding bug alone leaves the mechanism that produced it intact: a
dynamic value concatenated into a URI template, where the encoding is decided by
Spring's expansion rules rather than by anything this repo asserts. The same
mechanism sits three more times in the same two files (the Confluence `cql`, the
GitLab `search` term, the GitLab `path` filter). Patching one instance and moving
on reproduces the escape class the archive already records — defects reachable
only on the first real-connector run — and reproduces it in exactly the place
where a 404 is indistinguishable from bad credentials or corp-proxy interference.

So the card designs **one encoding rule + one testability rule + one fixed
assertion checklist**, and the bug fix falls out of the rule.

## Evidence — what the review found

| # | What | Where | Severity | Failure scenario |
|---|---|---|---|---|
| 1 | `searchCode`/`recentCommitters` pre-encode the project with `URLEncoder.encode` and then pass the result as a **URI template variable**, which is strictly encoded again — `%2F` → `%252F` | [`RealGitLabGateway.java:49`](../../../../src/main/java/com/company/triage/gateway/real/RealGitLabGateway.java#L49), [`:74`](../../../../src/main/java/com/company/triage/gateway/real/RealGitLabGateway.java#L74) | MEDIUM | `./run-deterministic-real.sh` — the script whose stated purpose is "prove the connectors genuinely work" (`run-deterministic-real.sh:4-6`) — 404s on **every** GitLab call: GitLab decodes once and looks up a project literally named `order-payments%2Fpayment-service`. The log↔code citation (J6's RC3 "wow moment") and the committer contacts vanish, first discovered live. |
| 2 | The two connectors whose real HTTP layer is entirely unexercised: zero unit, request-capture or live tests | [`RealConfluenceGateway.java:47`](../../../../src/main/java/com/company/triage/gateway/real/RealConfluenceGateway.java#L47), [`RealGitLabGateway.java:51`](../../../../src/main/java/com/company/triage/gateway/real/RealGitLabGateway.java#L51) | MEDIUM | A request-capture test would have caught finding 1 before it was written. Instead the first execution of either code path is a real run on the corp laptop, where a 404/400 reads identically to a credential or proxy problem. |

**The verifier sharpened both.** On 1: `RealGitLabGateway` builds its `RestClient`
with `RestClient.builder()` and no custom `UriBuilderFactory` anywhere in `src`, so
the default strict expansion applies; the sole allowlisted project
(`application.yml:166-167` → `order-payments/payment-service`) contains a slash, so
**every** real-mode GitLab call is affected, not an edge case. On 2: the
`MockRestServiceServer` pattern already exists in `RealServiceNowGatewayTest`, so
the missing tests are cheap and idiomatic rather than new infrastructure.

**One claim did not survive my own read, and the card corrects it.** Finding 2
says a `{` or `}` in Confluence CQL text "throws". It does throw — `build()` with
no arguments cannot resolve a stray placeholder — but
`RealConfluenceGateway.search`'s catch-all returns `List.of()` (`:60-62`), so the
observable behaviour is **silent zero knowledge results**, indistinguishable from
"no matching pages". That is worse than a throw, not better, and it is what the
test must assert against. The brace hazard reaches CQL from both engines: the ADK
path passes model-supplied text straight through (`TriageMateTools.java:101`), and
the deterministic path's `app` is raw subject text via `leadingPhrase`
(`IncidentSignals.java:223-228`, feeding `confluenceQuery()` at `:135-137`).
The same hazard on GitLab's `search` term is **ADK-path only** — the deterministic
term is regex-extracted `SCREAMING_SNAKE` (`DeterministicDiagnosisEngine.java:44`,
called at `:214`), which cannot contain a brace.

## Design

### RGC-1 — Caller-derived text is never concatenated into a URI template

**The rule**: any value that came from an incident, a model, a log line or config
is passed to the URI builder as a **URI variable**, never spliced into the
template string, and is never pre-encoded by hand. The builder's strict expansion
is the *single* encoder.

Applied:

- **GitLab project id** — delete both `URLEncoder.encode(project, UTF_8)` calls
  (`:49`, `:74`) and pass `project` raw to `.build(project)`. Strict expansion
  turns `/` into `%2F` exactly once, which is the correct GitLab
  URL-encoded-path-id form.
- **Confluence CQL** — `.queryParam("cql", "{cql}").build(cql)` instead of
  `.queryParam("cql", cql).build()`. A `{` in the value is then encoded as `%7B`
  and never re-read as a placeholder, and `+` is encoded as `%2B` instead of
  passing through to be decoded server-side as a space.
- **GitLab `search` term and `path` filter** — same treatment (`:53`, `:89`), for
  the ADK path's free-form term.

**Rejected — `build(true)` / `EncodingMode.NONE` / `VALUES_ONLY` on a custom
`DefaultUriBuilderFactory`.** All three work, and all three move the encoding
decision *out* of the call site into either a boolean flag or per-client
configuration a reader has to go find. Keeping strict `TEMPLATE_AND_VALUES` and
feeding it raw values means the call site reads exactly as it behaves, and one
rule covers all four instances instead of one rule per client.

**Rejected — stripping braces from query text.** Lossy: it silently changes what
was searched for, which is the FND-8 class this project treats as its worst
failure mode.

### RGC-2 — Every real gateway takes an injected `RestClient.Builder`

`RealServiceNowGateway` already does this, and its constructor comment says why
verbatim: "so tests can bind a `MockRestServiceServer` to it … FND-14 needed a
real regression test against actual HTTP request/response shapes, not just a unit
test of an extracted predicate" (`RealServiceNowGateway.java:50-55`). That is the
identical argument here, and it is the reason ServiceNow has five request-level
tests while Confluence and GitLab have none: the other three gateways call
`RestClient.builder()` inside their own constructors
(`RealConfluenceGateway.java:34`, `RealGitLabGateway.java:39`,
`RealSumoGateway.java:36`) and are therefore unbindable.

**Decision**: change all three constructors to take `RestClient.Builder`,
including Sumo — even though Sumo's contract test is not required by this card.
A uniform constructor shape is the thing that stops the next real gateway from
being written untestable; leaving one exception preserves the pattern that
produced this gap. Spring Boot autoconfigures a fresh prototype `RestClient.Builder`
per injection point, so this is a signature change with no wiring cost.

**Rejected — MockWebServer / WireMock.** A new test dependency, a real socket per
test, and a second HTTP-stubbing idiom in a repo that already has one that works.
`MockRestServiceServer` also fails the test on *unexpected* requests, which
`RealServiceNowGatewayTest#skipsWhenIdenticalNoteAlreadyExists` already relies on
to prove a call did **not** happen — worth keeping.

**Rejected — extract URL building into a static helper and unit-test that.**
Precisely what FND-14 rejected: the defect here lives in the *wiring* between
pre-encoding and template expansion, so a test of an extracted string function
would have passed while `%252F` still went out on the wire.

### RGC-3 — The fixed assertion checklist

Each real-gateway contract test asserts these five things, in this order, so a new
connector's test is a fill-in rather than a design exercise:

1. **Full request path and encoding.** GitLab: the captured path contains `%2F`
   **exactly once** and does not contain `%252F`. Confluence: a CQL value carrying
   `{`, `}`, `+`, a space and a quote produces a request at all, and one whose
   `cql` parameter round-trips to the intended string.
2. **Auth header shape.** `PRIVATE-TOKEN` for GitLab; `Authorization: Basic <b64>`
   for Confluence — the header *name and form*, never a real credential.
3. **Canned real-shaped payload parses** into the J4 model type
   (`CodeSearchResult`, `Contact`, `KnowledgeDoc`), including the two-step
   tag→commits flow (`RealGitLabGateway.java:77-94`), which has never run against
   any payload, real or fake.
4. **Empty-result variant** — `{"result":[]}` / `[]` yields an empty list, not an
   exception or a null.
5. **Missing-field variant** — a payload lacking `startline`, `committed_date`,
   `body.view.value` or `_links.webui` yields a well-formed object with defaults,
   not an NPE. (This is the J14 class recurring at the gateway seam: the real
   payload is the input shape the mock never produces.)

### RGC-4 — Contract tests pin today's failure semantics; they do not change them

The two gateways degrade differently and deliberately. `searchCode` has **no**
`try/catch` (`RealGitLabGateway.java:47-63`), so a 4xx propagates and aborts the
run; `recentCommitters` (`:121-123`) and both Confluence methods
(`RealConfluenceGateway.java:60-62`, `:102-104`) swallow to an empty list.

**Decision**: the tests assert the current behaviour of each, exactly as it is
today, and this card changes none of it. Whether the fallback engine should
survive one failing connector is a real question with a real answer — it is
**J14-fallback-real-input-robustness**'s, and deciding it twice in two cards is
how the two cards end up disagreeing. What J22 contributes is that after these
tests exist, J14's change is a visible diff in an assertion rather than an
undetected behaviour drift.

### RGC-5 — Offline, default profile, no live network

The contract tests run in plain `mvn test` (the gateways live in
`src/main/java`, not `src/main/adk`), require no credentials, and open no socket.
This is the deliberate split `RealSumoGatewayTimeFormatTest` already documents:
`RealSumoGatewayLiveTest` "is the one that found this, but it only runs with
credentials present; this keeps the format pinned for everyone else"
(`RealSumoGatewayTimeFormatTest.java:18-19`). Live tests find contracts; capture
tests *keep* them. A live test cannot serve this card's guarantee at all, because
the whole point is that the corp laptop's first real run is not the test.

## Verification

- `RealGitLabGatewayTest#projectIdIsEncodedExactlyOnce` — captures the
  `searchCode` request; asserts the path contains `%2F` once and `%252F` never.
  **This test fails on today's code** and is the regression proof for evidence
  row 1.
- `RealGitLabGatewayTest#recentCommittersEncodesTheProjectIdExactlyOnce` — same
  assertion on the tag and commits requests (`:79`, `:92`), which carry the second
  copy of the defect.
- `RealGitLabGatewayTest#parsesBlobSearchHitsIntoCodeSearchResults` /
  `#emptyBlobSearchYieldsNoHits` / `#missingStartlineDefaultsToZero` — RGC-3
  items 3–5 for the search path.
- `RealGitLabGatewayTest#tagThenCommitsFlowProducesDedupedContacts` /
  `#noTagsFallsBackToUnfilteredCommits` — the two-step flow and its empty-tag
  branch.
- `RealGitLabGatewayTest#sendsThePrivateTokenHeader` — RGC-3 item 2.
- `RealConfluenceGatewayTest#cqlWithBracesReachesTheServerInsteadOfSilentlyReturningNothing`
  — the corrected claim above: today a `{` in the incident-derived `app` yields
  zero pages with no error; after RGC-1 the request goes out with `%7B`.
- `RealConfluenceGatewayTest#cqlPlusSignSurvivesAsAPlusSign` — `+` is `%2B` on the
  wire, not a space.
- `RealConfluenceGatewayTest#sendsBasicAuthHeader` /
  `#parsesSearchResultsIntoKnowledgeDocs` / `#emptySearchYieldsNoDocs` /
  `#missingBodyViewYieldsAnEmptySnippet` /
  `#contributorsParsesAuthorAndLastEditor` /
  `#contributorsWithNoVersionByYieldsEmptyList` — RGC-3 items 2–5 for both
  Confluence methods.
- `RealGitLabGatewayTest#searchCodePropagatesA404` and
  `RealConfluenceGatewayTest#searchSwallowsA404IntoAnEmptyList` — RGC-4: today's
  asymmetric failure semantics, asserted so J14 changes them on purpose.

All of the above are default-profile tests: **none** requires `-Padk`, and none
should be added to the adk-only source set. Baseline before this card is 152
default / 201 adk, both green; these tests add to the default count only, and the
`-Padk` count moves by the same amount because that profile is a superset.

## Out of scope

- **Whether one failing connector should abort the fallback diagnosis** —
  J14-fallback-real-input-robustness. RGC-4 pins the current behaviour so that
  card's change is visible.
- **The evidence-id and citation consequences of a large GitLab hit set** —
  J13-evidence-citation-integrity owns the duplicate `e-code` id. *Adjacent and
  **unverified** (review tail, needs a one-look check before acting):
  `searchCode` sets no `per_page`, so GitLab's default first page (~20 blob
  matches) comes back and each becomes an evidence entry; a canned 20-hit payload
  in RGC-3 item 3 would make that visible, but capping it is J13's call, not
  this card's.*
- **Sumo's request contract.** RGC-2 makes `RealSumoGateway` bindable; writing its
  capture test is deliberately left out — it already has a format regression test
  and the only live test in the repo, so it is not the unexercised surface this
  card exists for.
- **ServiceNow.** `RealServiceNowGatewayTest` already implements this card's
  pattern; it is the precedent, not the work.
- **Real-connector credential/proxy setup on the corp laptop** —
  J15-port-contract-demo-runbook and J20-startup-truth-and-validation.
- **The `_sourceCategory` composition contract** (`application.yml:145`) and the
  doc drift it left behind — J18-guardrail-enforcement-completeness.
