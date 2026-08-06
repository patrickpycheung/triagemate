# Found-issues archive

---

# Fixed 2026-08-05 — `worktree-hack-222`, from the `cause-and-resolution-sections` DDS

Both found by a design exploration rather than by a test or a live run, which is the
point: neither is reachable from the mock profile, and one of them was actively
corrupting the exploration that found it.

## FND-85 — a non-UTF-8 byte in `DeterministicDiagnosisEngine.java` makes plain `grep` silently skip the file · **MEDIUM**

**Where**: `src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java`
(`file` reports `data`, not `Java source`); the byte is in the trace string near `:163`.

**What**: because GNU `grep` classifies the file as binary, a repo-wide
`grep -rn "<symbol>" src/` **omits every match in the largest orchestration file in the
project** and exits 0 with no warning. `grep -a` finds them.

**Why it matters**: this is a silent-wrong-answer hazard for exactly the audit workflows
this repo relies on. It produced two demonstrated wrong conclusions in a single DDS
session: a repo-wide grep for `resolutionCode`/`resolutionNotes` returned only the record
declarations, leading both an exploration agent and the orchestrator to independently
conclude "nothing reads these fields" — when `resolutionCode` is read at `:160`. Any
dead-code sweep, rename, or impact analysis run against this repo is unsound until it is
fixed. Fix is trivial (replace the byte with its ASCII equivalent); the value is in
removing the trap. Found during DDS `cause-and-resolution-sections`.

- **Resolution**: fixed:a867de0 — raw NUL replaced with the Java escape `\0`, which
  compiles to the identical string, plus `SourceEncodingHygieneTest` (scoped to text
  sources; binary resources contain NUL bytes by nature and grep is right to skip those).
  Fails-before verified by reintroducing the byte: the guard named offset 47891, line 738.
- **Escape**: tooling-trust — no check asserted that the repo's own source is searchable.
  Every audit workflow here (dead-code sweeps, rename impact, `/found-issues-resolve`
  itself) assumes grep is complete, and grep failed silently with exit 0. The guard now
  makes that assumption enforced rather than hoped for.

## FND-86 — FND-67 self-poisoning is only fixed on the deterministic path; the ADK path still feeds on its own notes · **HIGH**

**Where**: `src/main/adk/java/com/company/triage/agent/TriageMateTools.java:76-77`
(`getIncident()` returns the raw `IncidentContext`), vs the only two filter call sites,
`src/main/java/com/company/triage/orchestration/IncidentSignals.java:101` and
`MentionedPeople.java:197` — both deterministic-path helpers.

**What**: FND-67 (documented in `DiagnosisReport.AI_NOTE_PREFIX`'s javadoc) is the bug
where a second diagnosis of the same incident read the first one's work notes as ordinary
human conversation and drifted. The fix was `isAiAuthoredNote`, applied at two
deterministic-path call sites. The ADK path was never covered:

1. `IncidentContext` carries `comments` and `workNotes` (`IncidentContext.java:17-18`).
2. `TriageMateTools.getIncident()` hands that record to the model **unfiltered** — contrast
   `findOwnership` (`:89-95`), which projects to a `Map` and deliberately drops fields.
3. `AdkDiagnosisEngine.instruction()` (`:102`) *directs* the model to read "conversation
   (comments / work notes): the caller's own follow-ups often…".
4. `grep -arn isAiAuthoredNote src/main/` returns no hit anywhere under `src/main/adk/`.

So with `triage.engine=adk` against a real instance, run 2 sees run 1's `[AI Triage · …]`
notes as ticket conversation — the exact condition FND-67 documents, on the path the demo
calls "the real thing working".

**Why it matters**: it is a regression of an already-diagnosed bug, live on the flagship
path, and it is invisible in the mock profile (mock notes go to the log, so no journal
accumulates to re-read). It also compounds badly with any future cause/resolution section:
today the drift is in keywords and contact names, but an *assertive* cause statement would
make run 1's hedged hypothesis into run 2's corroborating "human" evidence and run 3's
stated cause — a circular evidence chain that `evidenceRefs` validation cannot detect,
because every link is a genuine, correctly-cited artifact.

The fix belongs at the tool boundary (filter in `TriageMateTools`, not in the prompt), so
that it holds regardless of what the model chooses to do — the same "enforce at the
boundary, don't ask the model nicely" thesis as J18. Found during DDS
`cause-and-resolution-sections`; verified independently by the orchestrator.

- **Resolution**: fixed:14fa031 — `IncidentContext.withoutAiAuthoredNotes()` applied in
  `TriageMateTools.getIncident()`, i.e. at the tool boundary rather than in the prompt,
  so the guarantee holds however the model behaves. Carded as **J27**
  (`docs/design-java/concepts/J27-adk-journal-filter/`). Regression:
  `TriageMateToolsJournalFilterTest` (adk profile) + `IncidentContextAiNoteFilterTest`;
  fails-before verified by reverting the one-line call (2 failures). Both profiles green,
  266 tests.
- **Escape**: mock-fidelity — the mock profile writes notes to the log, so no journal ever
  accumulates to be re-read and the bug is unreachable in `mvn test` and in the demo. The
  original FND-67 fix was also verified only on the deterministic path, so 'fixed' was
  recorded for a bug that was half-fixed. Same escape layer as FND-84/84a/85a.


---

# Duplicates of `develop`'s FND-84 — filed independently by `worktree-hack-222`, 2026-08-05

Both entries below were filed from the `cause-and-resolution-sections` DDS in
`worktree-hack-222`, at the same time as `worktree-hack-111` was independently finding and
**fixing** the same underlying defect from the opposite direction: hack-111 from a live run
("Find Similar Incidents always returns zero hits"), hack-222 from reading the query
construction during a design exploration. Neither worktree could see the other — the
session-start `wt-looker` digest showed hack-111 with zero commits ahead and no in-flight
status, and its merge to develop landed mid-run.

They are recorded rather than discarded because the *convergence* is the finding: the same
defect was reachable from a live symptom and from a static read within hours of each other,
and the two descriptions cover different faces of it (hack-111 identified the unused
`cmdb_ci` and the hardcoded `stateIN6,7`; hack-222 identified that the mock profile
structurally conceals the whole class).

- **Resolution**: duplicate:FND-84 (fixed on develop — `SimilarIncidentRanker`,
  `SymptomTokens`, config-driven `resolved-states`/`similarity-floor`/`max-similar`).
  Verified fixed post-merge: the hardcoded `0.5` is gone and `SimilarIncidentRanker.rank()`
  computes a real score.
- **Escape**: mock-fidelity — no test exercises the real gateway's *data quality*. The mock
  supplies a realistic `0.91` and hand-tuned matching incidents, so every defect in this
  class is invisible to `mvn test` and to the demo. Identical escape layer to FND-85/FND-86
  in the same batch; three of four found-issues from this DDS share it.

## FND-84a (dup) — every similar-incident line reports a fabricated "% similar" against real data · **HIGH**

**Where**: `src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java:158-161`
renders `r.similarity() * 100`; `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:140`
supplies that value as the literal `0.5` for every row.

**What**: the real gateway hardcodes `similarity = 0.5` on every `ResolvedIncident` it
returns — there is no similarity computation in production at all. The deterministic
engine formats it as a percentage into an `Evidence` summary, so against a live instance
**every** similar-incident line reads:

```
INC0011455 (50% similar) resolved by Payments Platform Support: Resolved - Known Error
```

That evidence line is posted to the real ticket in the "Sources consulted" work note.

**Why it matters**: it is a fabricated statistic presented as a computed match score, on
a customer-retained incident record, by a system whose entire pitch is that every claim
is auditable and one click from its evidence. A reader has no way to tell that the number
is a constant — and "50%" reads as a real, if weak, computed match. `MockServiceNowGateway.java:101`
supplies a genuine-looking `0.91`, so the demo path never shows the problem: the defect is
invisible from the stage and only appears against live data. Minimum fix: suppress the
percentage when the value is the sentinel. Real fix: compute a similarity, or stop
claiming one. Found during DDS `cause-and-resolution-sections`.

## FND-85a (dup) — `findSimilarIncidents` matches on the first word of the description only · **HIGH**

**Where**: `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:129-142`
and `:314-316`.

**What**: `firstKeyword(s)` returns `s.split("\\s+")[0]` — the first whitespace-delimited
token — and the query is `stateIN6,7^short_descriptionLIKE<that token>`. For the
project's own canonical example, *"User receives HTTP 403 when submitting an order"*, the
production query is `short_descriptionLIKE User`. Incident short descriptions
overwhelmingly begin with "User", "Unable", "Cannot", "Users", "Error", so on a real queue
the filter is close to a no-op. The method's own comment concedes `// Naive keyword match`.

**Why it matters**: "similar past incidents" is load-bearing evidence — it drives the
`e-sim-*` evidence rows and the assignment-group suggestion, and it is the natural source
for any future cause/resolution section. Returning near-arbitrary resolved tickets while
labelling them "similar" (see FND-84) lends borrowed credibility to noise. Compounding:
the mock returns hand-tuned genuinely-similar incidents, so this is another defect the
demo structurally cannot reveal. Found during DDS `cause-and-resolution-sections`.



Append-only record of resolved `FND-*` entries from `/FOUND-ISSUES.md`, newest first.

Each entry keeps its original text plus two lines added at resolution time:

- **Resolution** — `fixed:<sha>` (fixed directly), `promoted:<card path>` (now tracked
  by a design card), or `accepted:<card path>` (evaluated and deliberately not built —
  low probability/impact for this app's actual scope; documented as an accepted
  limitation in the owning card rather than left as an open backlog item).
- **Escape** — which **process layer** should have caught it. Not what was wrong in the
  code: what was wrong in how we work. A future retrospective clusters on this field, so
  it is written while the context is fresh.

Drained 2026-07-30 by `/found-issues-resolve`. Re-run 2026-07-31 (FND-45..51,
7 code fixes + 2 already-archived closures); backlog empty again. A second
`/doc-test cds` pass the same day raised FND-55..58; FND-57/58 fixed
(TriageProperties refactor). FND-59 raised and fixed the same day, found by a
code-review walkthrough of the ServiceNow→Confluence data flow, not by
`/doc-test`. FND-60/61 likewise, from validating the ADK agent's tool-context
design (does the agent get what it needs to construct good queries?), and
FND-62/63 from asking the same question of the DETERMINISTIC path; FND-64 from
reviewing name extraction across all four providers. **FND-66..69 (2026-08-01) came
from the first runs against REAL ServiceNow + Confluence** — none were reachable
offline, and three of them had to be fixed before the J11 latency spike could
measure anything at all. `/found-issues-resolve` run the same day tested the "deferred
pending design decision" premise on FND-55/56 and found both decidable now
(see their Resolution notes) — backlog empty again.

---


## Resolved 2026-08-05 — application review + teammate field report

Thirteen entries from the 2026-08-05 whole-application review (FND-70…82) plus one
field-reported by sajids4 (FND-83). All fixed the same day: five during the review
session itself, the remaining nine by `/found-issues-resolve`. Every behaviour change
carries a fails-before/passes-after regression test; both profiles green at 163/219.

## FND-83 — Confluence `base-url` must not include `/wiki`, and nothing says so · **HIGH** · *field-reported*

**Where**: `src/main/java/com/company/triage/gateway/real/RealConfluenceGateway.java:47`
and `:78` (the code supplies the `/wiki` prefix itself) vs
`secrets.properties.example:19` (`triage.integrations.confluence.base-url=`, blank, no hint).

**Reported by**: sajids4 (siyad.sajid4@auspost.com.au), `docs/Siyad_Findings.md` §1, commit
`6c550ab` — from a live run against real Confluence, 2026-08-04.

**What**: `RealConfluenceGateway` hardcodes `/wiki/rest/api/...` into every request path, so
the configured base URL must be the bare site (`https://auspost.atlassian.net`). The natural
value to paste — the one in every browser address bar and the one Atlassian's own docs show —
is `https://auspost.atlassian.net/wiki`, which produces `/wiki/wiki/rest/api/content/search`
and a 404. The example file gives no clue either way.

**Why it matters**: it cost a teammate a debugging session on the live instance, and the
failure is maximally misleading — the 404 body is a full Confluence "Page Not Found" HTML
page, so the log shows a wall of markup rather than "your base URL is wrong". Worse, the
gateway's blanket `catch → List.of()` turns it into `0 page(s)` in the trace and a
`missingInformation` line asserting *"No runbook or known-error page matched the symptom
terms"* — a clean-looking empty search that never happened. On stage this reads as
"Confluence had nothing", not "Confluence was never reached".

**Fix**: three small parts, all specified.
1. Strip a trailing `/wiki` (and any trailing slash) from the configured base URL in the
   constructor — the two spellings must both work, because both are what people will paste.
2. Document the contract in `secrets.properties.example` next to the key: *"site root, no
   `/wiki` suffix — the app adds it"*.
3. Reference it from `docs/integrations/` alongside the other connector setup notes.

The *visibility* half — making a failed search distinguishable from an empty one — is
deliberately not here; it is a design change owned by
[J25](docs/design-java/concepts/J25-knowledge-query-relevance/README.md) (KQR-4), and this
entry should be fixed without waiting for it.

- **Resolution**: fixed:0dd4005 (2026-08-05, during the application-review session)
- **Escape**: integration-contract-undocumented — the gateway supplies the `/wiki` context
  path itself, making the base-URL a contract with the config; nothing stated it, no test
  covered this gateway at all, and the blanket `catch -> List.of()` turned the resulting 404
  into a clean-looking empty search. Found only by a teammate on a live instance.

## FND-70 — J6/J8/DETERMINISTIC-FLOW/SUMOLOGIC still document the removed `triage.sumo.allowed-scopes` guardrail · **MEDIUM**

**Where**: `docs/design-java/concepts/J8-guardrails-observability/README.md:29`, plus the
Sumo sections of `J6-knowledge-tools/README.md`, `docs/design-java/DETERMINISTIC-FLOW.md`
and `docs/integrations/SUMOLOGIC.md:19`.

**What**: commit `92574ce` replaced the literal Sumo scope allowlist with an
app-composed `_sourceCategory` (allowed-environments + slug regex + configured pattern).
Four docs still describe the removed key as a live guardrail.

**Why it matters**: J8 is *the* card whose purpose is "what bounds are enforced, and
where". Anyone auditing the guardrail inventory — teammate, judge, or the next
`/doc-test` pass — is told the app enforces a scope allowlist it does not have. The real
bound is different, not weaker, but the doc names the wrong mechanism entirely.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: convention-propagation — a config-key removal (92574ce) changed the enforced guardrail but no step required the four docs that name it to be re-derived from the code.

## FND-71 — README "Verify it works" claims 3/5 passing tests against an actual 152/201 · **LOW**

**Where**: `README.md:157`

**What**: the README's own verification step promises `mvn test → 3 tests pass` and
`mvn -Padk test → 5 tests pass`. Actual: ~152 and ~201.

**Why it matters**: FND-30 already established that hand-maintained counts drift, and
fixed J10 + STATUS.md — but the root README, the first thing a judge or teammate reads
and the doc that *invites* the verification, was never included. Someone following it
sees 152 where 3 was promised and concludes the docs are unmaintained, which is the
opposite of what a "Verify it works" section is for. Apply FND-30's policy: name
approximate counts with an "as of" date, or defer to `mvn` output.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: doc-freshness — FND-30 established the drifting-counts policy and fixed J10 + STATUS.md, but the fix was applied to the files that had drifted, not to every file carrying a count.

## FND-72 — J1/J11 response-contract docs omit the sixth `DiagnosisResult` component `connectors` · **LOW**

**Where**: `docs/design-java/concepts/J1-spring-boot-orchestrator/README.md:89`, and
J11's LT7 section.

**What**: J1 documents the response as `report + trace + engine + writebackPosted`,
amended by J11 to add `steps`. The code has since added a sixth component,
`Map<String,String> connectors` (`DiagnosisResult.java:48-54`, TASK-016), feeding the
LT7 provenance chips. Additive and wire-compatible, but unrecorded.

**Why it matters**: J1 is the card an integrator reads for the `POST /api/diagnose`
contract, and it under-reports the shape at the one place it explicitly enumerates it —
the same "cards lag implementation" class FND-9…32 clustered on. One sentence each.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: card-lag — a response field shipped (TASK-016) without the contract card that enumerates the response shape being part of the change's definition of done.

## FND-73 — `secrets.properties.example` instructs a `snow-live` profile that no longer exists · **MEDIUM**

**Where**: `secrets.properties.example:8` and `:13` (also
`TRIAGEMATE_APPLICATION_REVIEW.md:339`, which calls it "the recommended combination").

**What**: the example file every new-machine setup copies tells the user to run with
`--spring.profiles.active=snow-live`. `application.yml:211` defines exactly one profile,
`real`; `snow-live` was consciously replaced by per-key `triage.connectors.*` overrides.
Spring silently accepts unknown active profiles.

**Why it matters**: the presenter sets up the corp laptop from this file, fills in real
ServiceNow credentials, follows its instruction — and every connector stays mock
(`matchIfMissing=true`). The two "advisory comments" go to the in-memory mock while the
presenter believes they landed on a real ticket. Silent, and exactly backwards from the
failure mode you want. Fix the two references, or re-add a `snow-live` profile document
(servicenow=real, rest mock — the combination the review doc recommends).

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: setup-path-untested — the example file is the new-machine entry point and nothing exercises it; a profile rename left it pointing at a profile that Spring accepts silently.

## FND-74 — Connector mode compared case-insensitively for bean selection but strictly for the provenance chip · **MEDIUM**

**Where**: `src/main/java/com/company/triage/config/ConnectorModeProvider.java:37`

**What**: `@ConditionalOnProperty` matches `havingValue="real"` case-insensitively, so
`triage.connectors.servicenow=Real` constructs `RealServiceNowGateway`.
`ConnectorModeProvider` stores the raw string and the LT7 chip compares it strictly, so
anything but exactly lowercase `real` renders as `fixtures`.

**Why it matters**: a capitalised value posts two advisory comments to a live,
customer-visible ticket while the UI asserts the run used fixtures. That is the FND-8
honesty-contract class — the UI claiming something that did not happen — in its most
consequential direction. Normalize (trim + lowercase) at the one comparison point, and
validate against `{mock,real}` so a typo fails fast instead of silently meaning mock.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: mock-only-testing — the reported mode and the wired bean were never asserted against each other, so a case-sensitivity mismatch between them was invisible offline.

## FND-75 — `generatedAt` is model-fabricated and parse-fragile · **MEDIUM**

**Where**: `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java:160`
(prompt schema) → the parse at `:688`.

**What**: the report's `generatedAt` is asked of the model rather than stamped by the
server. `DeterministicDiagnosisEngine.java:371` already stamps `OffsetDateTime.now()`.

**Why it matters**: two costs, one honesty and one operational. (a) A model-invented
generation timestamp flows into the API response and the ServiceNow work note. (b) A
common LLM timestamp shape (`2026-08-05 14:32:10` — no `T`, no offset) throws
`InvalidFormatException` and burns the single FND-42 repair retry — ~8s of stage time and
a Copilot call — on a field that carries no model judgment at all. Remove it from the
prompt schema and stamp after parse.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: contract-boundary — a field the server owns was placed in the model's schema, so nothing tested the case where the model answers it badly.

## FND-76 — An `Error` escaping `runOnce` leaves the coalescing future forever incomplete · **LOW**

**Where**: `src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java:199`
(owner completion) and `:213` (untimed `existing.get()`).

**What**: the owner completes `mine` on normal return or `catch (RuntimeException)`, while
the `finally` removes the map entry unconditionally. A `Throwable` raised on the owner
thread leaves the future incomplete forever, and `awaitExisting` blocks untimed.

**Why it matters**: verified narrower than first stated — `callWithTimeout` wraps engine
`Error`s into `RuntimeException`, so the exposed window is only a `Throwable` on the owner
thread *outside* the engine future (OOM/StackOverflow during trace assembly, work-note
construction, or result construction). But `IncidentPoller` is confirmed single-threaded,
so one hung waiter permanently kills polling for the process lifetime — no WARN, no
recovery. Complete the future unconditionally (`finally { if (!mine.isDone())
mine.completeExceptionally(...) }`), and optionally bound `awaitExisting`.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: exception-taxonomy — the coalescing design reasoned about RuntimeException and never about Throwable, and no test raised an Error on the owner thread.

## FND-77 — `collector.markDone()` is skipped on every failure path · **LOW**

**Where**: `src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java:280`

**What**: `runOnce()` reaches `markDone()` only on success, so a `runId`-registered buffer
for a failed run reads `done=false` until the 5-minute TTL evicts it — violating the
TASK-011 invariant that `done` agrees with the POST outcome.

**Why it matters**: verified that the claimed UI harm is **not** reachable with the
shipped client (`index.html` stops polling in the POST's `finally`, which fires on
rejection; a coalesced waiter's POST also fails and stops its poll). So this is contract
accuracy, not a live bug — worth two lines of `try/finally` because the invariant is
load-bearing for J16's registry work and for any non-browser poller.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: invariant-untested — TASK-011 stated done agrees with the POST outcome but the assertion only ever ran on the success path.

## FND-78 — Trace finish line over-counts denied attempts as observed tool calls · **LOW**

**Where**: `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java:441`

**What**: `bounds.used()` increments on every allowlisted attempt including those denied
for exceeding the budget, and the finish line reports it as "tool call(s) observed".

**Why it matters**: with `max-tool-calls=10` and a chatty model attempting 12, the trace's
last line reads "12 tool call(s) observed" — an operator or judge reading it against the
stated budget of 10 sees the J8 leash apparently violated when it actually held. The
per-attempt DENIED rows are emitted correctly, so the trace stays reconcilable; only the
summary is wrong. Report executed and denied separately.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: observability-semantics — the counter's meaning (attempts) and the label's claim (executed calls) diverged, and no test read the finish line against the budget.

## FND-79 — `unfence()` misses prose-before-fence and same-line fences · **LOW** · *unverified*

**Where**: `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java:722`

**What**: the fence strip only fires when the response *starts* with a backtick and only
when a newline follows the opening fence. Two shapes pass through untouched:
`Here is the report:\n```json\n{...}` (lead-in sentence) and a fence with no newline.
`AdkUnfenceTest`'s six cases cover neither.

**Why it matters**: FND-66 established that instructions alone do not suppress trained-in
formatting behaviour, and measured the cost of each miss at ~8s plus one Copilot call for
the repair retry — while stacking the run one failure away from
`DEGRADED_TO_DETERMINISTIC`. A prose lead-in is the same class of behaviour as the fencing
FND-66 fixed. Add a last-resort `{`…`}` substring extraction *after* the strict strip, so
well-behaved responses stay untouched.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: adversarial-input-gap — FND-66 fixed the one observed fence shape and the test set froze there; the neighbouring shapes were never enumerated.

## FND-80 — Enter key does not trigger Diagnose; `run()`'s catch path bypasses `esc()` · **LOW** · *unverified*

**Where**: `src/main/resources/static/index.html:402` (the input row) and `:511` (catch).

**What**: the incident input and button sit in a bare `div.row` with no form and no
keydown handler, so Enter does nothing. Separately, the catch branch interpolates `${e}`
raw while the sibling error path two lines up uses `esc(msg)`.

**Why it matters**: on stage the presenter types the number and hits Enter — the universal
reflex — and gets nothing, then hunts for the button. The button-disabled guard already
covers double-submit, so a `<form onsubmit>` is safe. The `${e}` is not exploitable today
(browser-generated text only), but it is the single interpolation on the page that breaks
an otherwise uniform escaping discipline, and it becomes load-bearing the moment anything
server-derived is thrown inside that `try`.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: ui-affordance-untested — no test or rehearsal step covers keyboard submission, and the escaping discipline was enforced per-path rather than per-value.

## FND-81 — `bin/setup-copilot-api.sh` is tracked without the executable bit · **LOW** · *unverified*

**Where**: `bin/setup-copilot-api.sh` (tracked mode `100644`; every sibling script is
`100755`).

**What**: `run-adk.sh` points users at `./bin/setup-copilot-api.sh` in four separate
error/help paths (lines 10, 63, 83, 94).

**Why it matters**: this is specifically the first-time corp-laptop Nexus/OAuth setup
script — the one context guaranteed to run from a fresh clone. The user types exactly
what the error message told them and gets `Permission denied`. It works on this dev
machine only because the working-tree copy was chmod'd locally; the tracked mode is what
the demo laptop gets. `git update-index --chmod=+x`.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: file-mode-untested — the working tree copy was chmod'd locally, so the tracked mode diverged from what a fresh clone gets and nothing checks tracked modes.

## FND-82 — Dead `.gitignore` negation for the Maven wrapper jar · **LOW** · *unverified*

**Where**: `.gitignore:22-23` (`.mvn/` then `!.mvn/wrapper/maven-wrapper.jar`), with
`*.jar` at `:43`.

**What**: git cannot re-include a file whose parent directory is excluded, and `*.jar`
would re-ignore it anyway. The negation is dead in both directions. No wrapper is
currently committed.

**Why it matters**: the run scripts hard-require a system Maven (`command -v mvn` →
"sudo apt install"), which on the locked-down corp demo laptop may need admin rights the
presenter does not have. The moment someone adds the wrapper to de-risk exactly that,
`git add .mvn` silently skips the jar, and the fresh clone on the demo laptop fails with
`Could not find or load main class ...MavenWrapperMain`. The negation's presence suggests
this was already intended once. Either fix the pattern set or delete the dead line so
nobody trusts it.

- **Resolution**: fixed:0dd4005 (2026-08-05, `/found-issues-resolve`)
- **Escape**: silent-noop-rule — a gitignore negation that cannot fire produces no error, and nothing verified the rule's effect with git check-ignore.


## FND-55 — FND-34's HTTP timeout pre-empts FND-15's engine timeout; the 504 was mostly unreachable · **MEDIUM**

**Where**: `application.yml` (`spring.http.client.read-timeout: 20s`),
`DiagnosisOrchestrator` (`timeout-ms: 90000`), `DiagnosisApiExceptionHandler`.
**What**: the two bounds overlap and the HTTP one wins. `read-timeout` applies to
`RealServiceNowGateway`'s injected builder — *including* `getIncident` inside
`engine.diagnose()` — so a hung real ServiceNow fails at ~20s with `ResourceAccessException`,
not at 90s with `DiagnosisTimeoutException`. That type isn't mapped, so it surfaces as a bare
500. The documented 504 is reachable only via Confluence/Sumo/GitLab (own unconfigured
`RestClient`, no HTTP timeout) or an ADK run genuinely exceeding 90s. Found by scenario
simulation.
- **Resolution**: fixed:6d468f3 — originally deferred as "wants a policy choice better made
  with the J11 spike's real-latency data." `/found-issues-resolve` tested that premise: the
  spike informs whether 90s/20s are the *right numeric values* (a separate, still-open tuning
  question), not what status code a timeout should return — those are independent, and the
  latter doesn't need the spike at all. `DiagnosisApiExceptionHandler` now maps
  `ResourceAccessException` to the same 504 as `DiagnosisTimeoutException`. Regression test:
  `DiagnosisApiExceptionHandlerTest#serviceNowConnectionTimeoutMapsTo504NotBare500`.
- **Escape**: design review — a "which policy to pick" deferral should distinguish the parts
  of the decision that need new data from the parts that don't; bundling a status-code
  correctness fix with a numeric-tuning question left a real bug parked behind an
  unrelated blocker.

## FND-56 — The K1 C6 warning was config-triggered, not capability-triggered · **LOW**

**Where**: `IncidentPoller` constructor (FND-45's check).
**What**: it reads the `triage.engine` string without checking that an ADK bean exists, so on
a non-`-Padk` build with `engine=adk` it claims "unattended, programmatic LLM use" for a run
that will never contact a model — while J1's FND-49 warning simultaneously says the opposite
(deterministic only). Both fire; only one is true. Found by two reviews.
- **Resolution**: fixed:6d468f3 — originally deferred pending FND-57's single validator, which
  is now built (`075d893`). `DiagnosisOrchestrator.isAdkActuallyActive()` exposes the same
  `engine != fallbackEngine` bean-identity check FND-49 already uses internally;
  `IncidentPoller` now asks that instead of reading `props.engine()` directly. Regression test:
  `IncidentPollerTest#noC6WarningWhenConfigSaysAdkButNoAdkBeanIsActuallyActive` (config says
  `adk`, no ADK bean actually wired — asserts no warning, the case that was previously wrong).
- **Escape**: implementation review — two independent checks answering the same underlying
  question ("is ADK actually the active engine?") from two different signals (config vs bean
  identity) will eventually disagree; the fix should share one source of truth, not duplicate
  the check.

## FND-69 — `max-tool-calls` permitted a run that `timeout-ms` would kill · **MEDIUM**

**Where**: `application.yml` — `triage.agent.max-tool-calls: 10` vs
`triage.orchestrator.timeout-ms: 90000`.
**What**: the 90s wall clock was self-documented as "a safer guess, not a measurement". The
LT4 spike measured it over 3 runs: **8.0s ± 2.6s per tool call** and **13.1s** for the final
report, i.e. `N × 8.0 + 13.1`. A full 10-call run — which the tool budget explicitly allows —
lands at **92.7s ± 8.2**, so the agent could be killed by its own timeout and degrade to
deterministic mid-run. On stage that is indistinguishable from the model failing, which is exactly the
FND-8 confusion the degraded-run banner exists to prevent. The two bounds contradicted each
other and nothing had ever checked them against one another.
- **Resolution**: fixed:HEAD — `timeout-ms` 90000 → **120000**, which puts the 10-call worst
  case 3.3 sd clear and the realistic 8-call case 5.9 sd clear. Raised the clock rather than cutting the tool budget to ~7: the budget is a J8
  **safety** bound (how much the model may do) and the timeout a **liveness** bound (how long
  we wait); trading away investigation depth — the documented flow uses up to 8 of the 8
  registered tools — to fix a liveness number is the wrong lever. Note the squeeze is
  real-connector-only: on the mock path used for the stage demo, tool execution is ~0 and
  10 calls ≈ 74s, comfortably inside even the old 90s.
- **Escape**: measurement — two numeric bounds on the same operation (budget × per-unit cost
  vs total wall clock) should be checked against each other the moment either is set. Both
  were plausible in isolation; the product was never computed until real latency existed.

## FND-68 — Six ADK schema WARNs per run, right before the agent starts · **LOW**

**Where**: startup/agent-build logging, `com.google.adk.tools.FunctionCallingUtils`.
**What**: ADK logs `Type java.time.ZoneOffset is recursive. Omitting from schema.` once per
recursive type per tool while building tool schemas — six WARN lines every run, caused by
`OffsetDateTime` in our tool signatures. Genuinely harmless (only the JSON-schema *hint* is
omitted; arguments still serialize, and `AdkLiveRoundTripTest` proves the round trip), but
they scroll past immediately before the agent starts — precisely when a human is watching the
console for a real problem.
- **Resolution**: fixed:HEAD — logger set to `ERROR`. Verified against the second real run:
  the app log went from 79 lines with six WARNs to 16 clean lines, zero WARN, zero ERROR.
- **Escape**: demo review — third-party log noise on the happy path is a stage liability even
  when it is technically harmless; the time to silence a known-benign WARN is before someone
  is squinting at it live.

## FND-67 — Name extraction on a real ticket: three non-people, and the caller missed · **MEDIUM**

**Where**: `MentionedPeople`, `IncidentSignals`, `DeterministicDiagnosisEngine`.
**What**: the first real ServiceNow ticket (`INC0010005`, "Delivery Hazards") produced four
defects at once in J9's contact list:
- **"AI Triage"** suggested as a person — read out of **our own work-note header**. Once
  FND-61 made the gateway read the journal, the app began feeding on itself: a re-diagnosis
  saw the previous run's notes as ordinary ticket conversation, so keywords, identifiers and
  names were partly drawn from its own prior output, drifting further from the human's actual
  words on every re-run.
- **"Delivery Hazards"** — the ticket's own subject line, i.e. the thing that is broken.
- **"Option Selected"** — a ServiceNow form label. Real tickets are full of Title Case form
  vocabulary with person-name shape.
- **`caller_id` ignored entirely** — the human who raised the ticket, the single most reliable
  contact on it, while far weaker prose matches were surfaced.
- **Resolution**: fixed:HEAD — (1) own notes filtered structurally in both consumers via
  `DiagnosisReport.AI_NOTE_PREFIX`/`isAiAuthoredNote`; (2) the shortDescription is passed as a
  known system name, since the TITLE names what broke — deliberately NOT a denylist of
  domain words, which only ever fixes the ticket in front of you; (3) generic ticket-form
  vocabulary added to the denylist, which does generalise; (4) the caller is always a contact,
  plus a `Steve Taylor (taylors)` cue tier, since ServiceNow renders people that way constantly
  and a parenthesised username is near-proof of a person. Regression tests use the real
  ticket's exact prose.
  Same run also fixed: `cmdb_ci` was **empty rather than null**, so the report shipped a
  candidateSystem with a **blank name** at 0.30 (a blank row on stage) and `IncidentSignals`
  put a whole sentence in `app`, which went verbatim into the Confluence query and the
  allowlist ranking. Blank-checked; the subject-line fallback is capped to a leading phrase.
- **Escape**: fixture realism — every name-extraction test used curated mock prose written to
  exercise the happy path. One real ticket produced three false positives and one false
  negative immediately. A self-referential feedback loop in particular is invisible to any
  test whose fixture the app did not previously write to.

## FND-66 — The agent could not return a parseable report against a real model · **HIGH**

**Where**: `AdkDiagnosisEngine` — the instruction's schema block and JSON parsing.
**What**: the first genuine agentic run against a real Copilot-served model failed twice and
degraded to deterministic, so the LT4 latency spike measured nothing:
1. The model wrapped its JSON in a ```` ```json ```` fence →
   `JsonParseException: Unexpected character ('`')`. The instruction already said "no prose"
   and the FND-42 repair prompt already said "no markdown code fences". It fenced anyway.
2. The repair retry then died on
   `Cannot deserialize value of type double from String "HIGH"` — **our** bug. The schema
   block showed `suggestedAssignment.confidence` as `"LOW|MEDIUM|HIGH"` while
   `candidateSystems[].confidence` directly above it carried no type hint at all, so the model
   reasonably assumed two identically-named sibling fields held the same kind of value. One is
   a 0.0–1.0 double.
- **Resolution**: fixed:HEAD — `unfence()` strips a code fence before parsing (a prompt is a
  request; this is the enforcement, and it saves burning the single repair retry — ~8s of
  stage time and a Copilot call — on something fixable locally in microseconds), and the
  instruction now states both `confidence` kinds explicitly. Both pinned by tests
  (`AdkUnfenceTest`, plus instruction assertions in `AdkAllowlistVisibilityTest`). The very
  next real run succeeded: `engine=ADK`, valid J4 report, 44s.
- **Escape**: contract review — an output schema shown to a model must be unambiguous about
  TYPES, not just field names, and two same-named fields of different types side by side is a
  trap we set ourselves. `FakeOpenAiServer` returns well-formed unfenced JSON by construction,
  so no offline test could ever have caught either half of this.

## FND-64 — J9 read names only from API metadata; ServiceNow contributed none at all · **MEDIUM**

**Where**: `DeterministicDiagnosisEngine.gatherContacts`, `AdkDiagnosisEngine.INSTRUCTION`.
**What**: "who should I talk to?" was answered from Confluence page author/last-editor and
GitLab recent committers — API metadata only. **ServiceNow contributed no names whatsoever**,
even though the ticket is where a human has already written down who else is involved: the
author of each comment/work note, and anyone they name in one ("escalated after speaking with
Priya Nair"). Someone already engaged with *this* incident is a better contact than someone who
edited a runbook months ago. Confluence **page bodies** were likewise never read for names, only
page metadata — a runbook's "Escalation contact: …" is frequently more relevant than whoever
last fixed a typo on it. (Sumo is correctly excluded: log lines carry no identity.)
- **Resolution**: fixed:01c02cf — new `MentionedPeople` extracts from ServiceNow prose +
  journal authors and from Confluence page bodies, in three precision tiers (emails/@handles;
  cue-phrase names like "spoke with X"; bare capitalised pairs filtered against a static
  system-vocabulary denylist **and** the system names on this specific incident — "Payment
  Service"/"Order Portal" have person-name shape and are the likeliest false positives). The
  ADK instruction now names all three sources explicitly and forbids listing a team/service as
  a contact. `MentionedPeopleTest` (7 cases, mostly about what must NOT be extracted).
- **Latent bug this exposed**: the contact merge key was handle-else-name, which fails whenever
  the same person arrives with different identifier completeness — now the normal case, since a
  prose mention has no handle while the API record does. The demo showed it at once: Priya Nair
  and Marcus Chen each listed twice. Now keyed on normalised full name, keeping whichever record
  carries the handle, and ranked by *number* of corroborating sources instead of a boolean
  "contains a +" that could not distinguish 2 sources from 3.
- **Escape**: design review — when a concept says "gather X from our sources", enumerate the
  sources against the *providers* and check each one contributes; J9 was built from the two
  providers that expose X as structured metadata, and the two where X only exists in free text
  were never revisited. Same shape as FND-61 (a field consumed but never fetched), one level up.

## FND-62 — The deterministic engine's platform queries were targeted at the demo fixture, not the incident · **HIGH**

**Where**: `DeterministicDiagnosisEngine` steps 2, 5 and 6.
**What**: "deterministic" had been read as "hardcoded". Three of the four platform calls were
aimed at the one seeded incident: identifier extraction was a single regex for the demo's exact
`INC-ORD-\d+` shape (everything else fell through to the literal `"error"` as the Sumo query);
the Sumo scope was always `allowedScopes.get(0)` regardless of the incident; and the GitLab
project was the literal `"order-payments/payment-service"`, bypassing
`triage.gitlab.allowed-projects` entirely — FND-40's exact two-sources-of-truth class, fixed
there for Sumo scopes and missed here. This engine is also the FND-7 fallback, so "an incident
other than the demo one" is precisely when it runs for real. Found by reviewing the
deterministic path's query construction after the same review of the ADK path (FND-60).
- **Resolution**: fixed:c30ce3e — new `IncidentSignals` derives identifiers (dashed ids, UUIDs,
  hex trace ids, most-specific first, excluding the incident's own number), keywords (function
  words removed, domain words kept — `error`/`order`/`payment` are what a runbook search needs),
  and platform targeting. Targeting **ranks the configured allowlist by name overlap with the
  affected app, then sweeps it in that order**: ranking alone is a guess, and the demo is
  exactly where the guess is wrong (CI says "Order Portal", the failure is downstream in Payment
  Service — unguessable from the ticket, which is the point of the diagnosis). Sweeping is
  affordable here in a way it is not for the ADK path: the allowlist is small and config-bounded
  and there is no per-call LLM budget. `IncidentSignalsTest` (7 cases).
- **Escape**: design review — "deterministic" is not a licence to hardcode. Any value passed to
  an external system should trace to an input or to config; a literal in a call argument is the
  smell. The demo fixture passing is not evidence the logic generalises, because the fixture is
  what the literal was written against.

## FND-63 — The fallback engine could not actually serve as the fallback · **HIGH**

**Where**: `DeterministicDiagnosisEngine` step 8 (report assembly).
**What**: `candidateSystems` and their `evidenceRefs` were hardcoded, and two of the refs
(`e-kb-KB001234`, `e-sim-INC0011902`) were **literal ids from the seeded demo fixture**. For any
other incident those `Evidence` entries don't exist, so `DiagnosisReportValidator`'s
dangling-evidenceRef rule threw — meaning `DiagnosisOrchestrator` would degrade to this engine
under FND-7 and then get a **500** out of it, defeating the fallback precisely when it was
needed. The narrative fields were hardcoded prose about checkout/payment reconciliation too:
correct for the demo, outright fabrication for anything else (the FND-8 class). Invisible to
every existing test because they all used the seeded incident, for which the literal ids
resolve.
- **Resolution**: fixed:c30ce3e — candidates derive from the signals that actually name a system
  (log emitters, prettified, plus the CMDB owner), ranked by whether a log↔code citation
  resolved; refs are filtered against evidence gathered in this run, so dangling is impossible
  by construction. `reportedSymptom`, `affectedFunction`, `contradictingEvidence`,
  `missingInformation` and `recommendedNextAction` all derive from the ticket and the run.
  Additionally the **ticket itself is now cited as evidence** (`e-incident`) — it never was,
  which left an incident whose other four sources all return empty with zero evidence and hence
  no valid J4 report at all (the contract requires ≥1); "here is what the ticket says and
  nothing corroborated it" is an honest triage outcome, failing to produce a report is not.
  Regression test `DeterministicDiagnosisEngineTest#producesAValidReportForAnIncidentUnrelatedToTheSeededScenario`.
- **Escape**: test design — a fallback path needs at least one test that exercises it with input
  the happy path never sees. Every test here used the one seeded incident, so a report hardcoded
  to that incident's evidence ids looked correct indefinitely. Same root as FND-47/FND-61
  (mock-shaped testing hiding a real-input failure), one layer up.

## FND-60 — The J8 allowlisted scopes/projects were invisible to the agent that must supply them · **HIGH**

**Where**: `AdkDiagnosisEngine.INSTRUCTION`, `TriageMateTools.searchLogs`/`searchCode`.
**What**: both tools hard-throw on a value outside their allowlist (J8, FND-20/38), but the
allowlisted values appeared **nowhere the model could see them** — not in the instruction, not
in any `@Schema` description, and there is no discovery tool. The model had to guess the exact
strings, and the incident's own fields don't contain them: the demo incident's `cmdb_ci` is
"Order Portal" while the allowlisted project is `order-payments/payment-service`, underivable
from one another. Every wrong guess burned one of the 10 J8 tool calls on a guaranteed
exception, so on the live-agent path steps 5–6 (logs → code, i.e. the log↔code citation that
is the demo's centrepiece) were likely to be lost entirely. Invisible to the offline suite
because `DeterministicDiagnosisEngine` passes the allowlisted values as literals and never
guesses. Found by validating the agent's tool-context design, not by `/doc-test`.
- **Resolution**: fixed:7d8f9ef — `INSTRUCTION` is now built per-instance from the same
  `TriageProperties` the tools enforce (one source, so enforcement and disclosure cannot drift
  into "rejected for a value we never disclosed"), naming the accepted values verbatim.
  Rejection messages name them too, so a model that still gets it wrong can self-correct within
  its remaining budget. Tool `@Schema` descriptions are compile-time constants and cannot carry
  runtime config, which is why the instruction rather than the tool surface is the fix site.
  Regression test `AdkAllowlistVisibilityTest` (three cases, incl. one asserting the instruction
  tracks *configured* allowlists rather than hardcoded defaults).
- **Escape**: design review — a guardrail that rejects model-supplied values needs a matching
  answer to "how does the model learn the valid ones?", checked in the same pass that adds the
  guardrail. FND-20/38 added the enforcement and never asked the disclosure question.

## FND-61 — The real gateway dropped the ticket conversation the mock supplied · **MEDIUM**

**Where**: `RealServiceNowGateway.getIncident`.
**What**: `comments` and `workNotes` were hardcoded to `List.of()` while
`MockServiceNowGateway` populated them (`"Caller: 'it worked yesterday, now some checkouts
error out'"`). So the demo showed the agent reasoning over the caller's follow-ups — often the
timing and scope detail the `description` field omits — and a real instance silently dropped
exactly that signal, with the model none the wiser. Same mock-only-testing blind spot as
FND-47's `u_environment`, and the same shape: a field read downstream but never actually
fetched.
- **Resolution**: fixed:7d8f9ef — journal entries live in `sys_journal_field`, not on the
  incident row, so they need their own query per field; added (reusing the table
  `alreadyPosted()` already reads for idempotency), oldest-first, prefixed with the author.
  Best-effort: a journal failure degrades to "no comments" rather than failing the diagnosis,
  since the triage is still useful without the conversation. `reassignmentHistory` remains
  empty — it needs `sys_audit`, a different table; noted rather than silently left looking
  wired. Regression test
  `RealServiceNowGatewayTest#getIncidentReadsTheTicketConversationFromTheJournal`.
- **Escape**: integration review — whenever a mock populates a field its real counterpart
  doesn't, the demo proves a capability production lacks; mock/real field-parity for every
  `Real*Gateway` is the standing check (this is the second instance, after FND-47).

## FND-59 — DeterministicDiagnosisEngine's Confluence query was a hardcoded literal, not derived from the incident · **MEDIUM**

**Where**: `DeterministicDiagnosisEngine.diagnose()`, the Confluence knowledge-search step.
**What**: `confluence.search("checkout order payment reconcile 500")` — a fixed string, sent
for every incident regardless of its actual symptom text. It only ever looked correct because
it happens to match `MockConfluenceGateway`'s keyword check for the one seeded demo incident
(J7). `IncidentContext.shortDescription()`/`.description()` are already extracted earlier in
the same method (for the order-ID regex) but never reached the Confluence call. Under
`connectors.confluence=real` against any incident that isn't the exact demo one, this would
have silently searched the demo's keywords instead of the real symptom — wrong knowledge-search
results, no error, nothing in the trace to suggest it.
- **Resolution**: fixed:18a4df9 — the query is now built from the incident's own
  `shortDescription` + `configurationItem` (`buildConfluenceQuery`), matching how the engine
  already derives `orderId`/Sumo `scope`/`window` from real incident fields rather than
  literals. The trace line now logs the actual query sent
  (`confluence.search(query="...")`), closing the "nothing to suggest it" part too. Regression
  test `DeterministicDiagnosisEngineTest#confluenceQueryIsDerivedFromTheIncidentNotHardcoded`
  uses a spy gateway (delegating to `MockConfluenceGateway` for the return value, so downstream
  evidence/justification wiring stays intact) to assert the captured query contains
  incident-derived terms and none of the old literal's ("reconcile", "discount", "500").
  Verified live against a running app: trace shows `confluence.search(query="Orders sometimes
  don't go through at checkout Order Portal")`.
- **Escape**: code review — any string literal passed as a *search query* argument (as opposed
  to a config value or a label) should prompt "where should this actually come from?" at write
  time; a literal that happens to satisfy the one demo fixture is exactly the shape that passes
  every existing test while being wrong for anything else.

---

## FND-57 — Config validation is fragmented across three constructors · **LOW**

**Where**: `DiagnosisOrchestrator`, `IncidentPoller`, `RealServiceNowGateway` constructors.
**What**: three components each re-read raw config and validate independently; each check only
runs if its own bean happens to exist. A typo'd `write-field` boots clean all week in mock and
throws for the first time on stage under `snow-live`. `triage.engine` is never validated as an
enum at all, so `agent`/`llm`/`Adk ` (trailing space) silently yield deterministic with **no**
warning — reopening the exact FND-49 class it was added to close. Found by all three
architecture perspectives.
- **Resolution**: fixed:075d893 — consolidated every `triage.*` `@Value` binding (across
  `DiagnosisOrchestrator`, `IncidentPoller`, `RealServiceNowGateway`,
  `DeterministicDiagnosisEngine`, `AdkDiagnosisEngine`) into one `@Validated
  @ConfigurationProperties(prefix = "triage")` record, `TriageProperties`. `triage.engine`
  now binds to a real enum (fails startup on an unrecognised value); `servicenow.writeField`
  is a `@Pattern` validated unconditionally at boot via Spring Bean Validation, regardless of
  which connector mode is active — closing the "boots clean in mock" gap specifically.
  FND-49's own WARN is unchanged (still catches its own, complementary case). See J1's
  README for the full shape and the `interval-ms`/`.enabled` scoping decision.
- **Escape**: architecture review — config validation scattered across N constructors, each
  gated by its own bean's conditional activation, is a shape that should be caught the moment
  a second `@Value`-with-a-manual-check constructor appears next to a first; the fix (one
  `@ConfigurationProperties` type) is the standard Spring Boot answer and should have been the
  first design, not a later consolidation.

## FND-58 — No format validation on the incident-number path variable · **LOW**

**Where**: `DiagnosisController`.
**What**: no `@Pattern`; `run()` only trims/uppercases. `POST /api/diagnose/banana` is
accepted and reaches the gateway, where it becomes part of a ServiceNow encoded query. FND-54
now makes the mock reject it cleanly, so the demo path is safe, but the contract gap is real
for `connectors=real`. Found by two reviews.
- **Resolution**: fixed:075d893 — `DiagnosisController` is now `@Validated` with
  `@Pattern(regexp = "INC\\d{6,10}")` on the `incidentNumber` path variable, and
  `DiagnosisApiExceptionHandler` gained a `HandlerMethodValidationException` → 400 mapping
  with the same `{"error": "..."}` shape as the other three types.
- **Escape**: API contract review — any `@PathVariable` that becomes part of a downstream
  query string against a real system needs a format constraint checked in the same pass as
  the route is added, not deferred until a connector-mode-specific review finds the gap.

## FND-45 — The C6 unattended-use gate was documented but not enforced in code · **MEDIUM**

**Where**: `application.yml`, `IncidentPoller.java`.
**What**: `poll.enabled=true` + `triage.engine=adk` is unattended, programmatic LLM use,
which the C6 ToS ruling gates — previously stated only in J10's prose.
- **Resolution**: fixed:4a956e5 — `IncidentPoller` now WARNs at startup unless
  `triage.trigger.poll.unattended-llm-ack=true` is explicitly set. Deliberately a warning,
  not a hard failure. `IncidentPollerTest#warnsWhenPollingWithAdkEngineAndNoAck`,
  `#noWarningWhenAckIsSetOrEngineIsDeterministic`.
- **Escape**: implementation review — a documented safety gate should always be checked
  for a code-level enforcement point at the time it's written, not left as prose alone;
  this is the same pattern as FND-8/16/25/38.

## FND-46 — D2's "offline, cannot fail" safety claim was broader than the code supports · **LOW**

**Where**: `orchestrator-vs-copilot-cli/4-decide/concepts-extracted.md` (D2).
**What**: the claim is true of the launcher configuration the runbook actually uses, not
of the engine in general (connector mode is independent of engine choice).
- **Resolution**: fixed:54f0d1b — narrowed the DDS decision doc's wording;
  `DEMO-RUNBOOK.md` already had this scoped correctly.
- **Escape**: doc review — a "the fallback can't fail" claim should always be traced to
  exactly which launch configuration makes that true, not stated as an engine property.

## FND-47 — `RealServiceNowGateway` read `u_environment` but never requested it · **MEDIUM**

**Where**: `RealServiceNowGateway.java` (`sysparm_fields`).
**What**: `IncidentContext.environment` was always null against a real instance; invisible
to mock-only tests.
- **Resolution**: fixed:54f0d1b — added `u_environment` to the field list.
  `RealServiceNowGatewayTest#getIncidentRequestsAndParsesEnvironment`.
- **Escape**: test coverage — the mock gateway has no field-selection to get wrong, so this
  class of bug is structurally invisible to mock-only tests; needs a real-instance smoke
  test or a field-list-completeness check as a standing practice for any `Real*Gateway`.

## FND-48 — No API error contract; a bad incident number showed a JS TypeError on stage · **MEDIUM**

**Where**: `src/main/java/com/company/triage/api/`, `index.html`.
**What**: the three exceptions the API throws all fell through to Spring's default error
body (no `report` field), and `render()` dereferenced it unconditionally — live demo risk.
- **Resolution**: fixed:54f0d1b — `DiagnosisApiExceptionHandler`
  (`@RestControllerAdvice`) maps `IllegalStateException`/`DiagnosisTimeoutException`/
  `DiagnosisReportInvalidException` to 404/504/502 with a JSON body; `index.html` checks
  `res.ok` before rendering. `DiagnosisApiExceptionHandlerTest` (3 cases).
- **Escape**: implementation review — an API with no `@ControllerAdvice` at all should have
  been caught by a basic "does every exception path have a test" check before this reached
  demo-readiness.

## FND-49 — `triage.engine=adk` without `-Padk` silently ran deterministic, unannounced · **MEDIUM**

**Where**: `DiagnosisOrchestrator.java`.
**What**: the misconfiguration path was uncovered while the FND-7 runtime-failure path was
covered — the same failure class (narrating a live model over a scripted run) via a
different route.
- **Resolution**: fixed:54f0d1b — logs a WARN naming the mismatch at startup.
  Deliberately not fail-fast.
- **Escape**: implementation review — FND-8's fix covered the *runtime* failure but not the
  *configuration* failure of the same shape; a fix for one instance of a failure class
  should prompt checking for sibling instances, not just the one reported.

## FND-50 — FND-37's normalization was incomplete: K1 bypassed it · **LOW**

**Where**: `DiagnosisController.java` vs `IncidentPoller.java`.
**What**: FND-37 (2026-07-30) normalized the incident number in the controller only, so K1
and K3 could still fail to coalesce on a case difference — a gap in my own fix from the
day before.
- **Resolution**: fixed:54f0d1b — moved normalization into `DiagnosisOrchestrator.run()`
  itself, so every trigger normalizes identically.
  `DiagnosisOrchestratorTest#differentlyCasedIncidentNumbersStillCoalesce`.
- **Escape**: implementation review — a cross-cutting fix (normalize before the coalescing
  map) should be applied at the single rendezvous point, not at each caller; FND-31's own
  card already states K3/K1 "both call run() — the one place their calls meet", which
  should have been the tell.

## FND-51 — `triage.servicenow.write-field` was interpolated into PATCH JSON unvalidated · **LOW**

**Where**: `RealServiceNowGateway.java`.
**What**: no restriction to `work_notes`/`comments`; hand-rolled JSON escaping missed `\r`
and tab.
- **Resolution**: fixed:54f0d1b — construction fails fast outside the two-value set;
  escaping now via Jackson. `RealServiceNowGatewayTest#rejectsAnUnrecognisedWriteField`,
  `#workNoteWithCarriageReturnAndTabIsValidJson`.
- **Escape**: implementation review — any config value interpolated into a request body
  should be validated against its known-good set at construction, not trusted; and
  hand-rolled escaping of anything JSON should be flagged in review when a JSON library is
  already a dependency.

---

## FND-43 — Poller cursor can skip a batch-limit's worth of same-timestamp incidents · **LOW**

**Where**: `IncidentPoller.pollOnce()`.
**What**: if more than `triage.trigger.poll.batch-limit` (default 10) incidents share the
exact same `sys_created_on` second, the cursor's "unbroken handled prefix" advance could
move past ones never actually fetched. Independently re-discovered by two architecture
reviews in the 2026-07-31 `/doc-test cds` run without knowing it was already logged,
raising confidence it is real rather than theoretical.
- **Resolution**: accepted:docs/design-java/concepts/J10-incident-poller/README.md — K1
  is off by default and unused by the demo; the trigger needs K1 enabled AND a true
  same-second creation burst, low probability for hackathon-scale traffic.
- **Escape**: design review — a correctness invariant depending on an upstream ordering
  guarantee (query-level tie-breaking) should have been checked against second-resolution
  timestamps at the time J10 was designed.

## FND-44 — Several ADK guardrails are prompt-only, not code-enforced · **LOW**

**Where**: `AdkDiagnosisEngine`'s `INSTRUCTION` — "ONE bounded Sumo Logic search",
citation provenance.
**What**: asked of the model via the system prompt, not structurally enforced the way the
Sumo/GitLab allowlists are (FND-20/38).
- **Resolution**: accepted:docs/design-java/concepts/J8-guardrails-observability/README.md
  — the actual safety boundary (advisory-only, no destructive tools) is unaffected either
  way; this is investigation-time efficiency, not risk to real systems, for an app that
  processes only an internal ServiceNow queue.
- **Escape**: n/a — correctly logged and now correctly closed as accepted, not escaped.

---

## FND-42 — No in-engine repair retry on malformed ADK JSON · **LOW**

**Where**: `AdkDiagnosisEngine`.
**What**: originally logged as deferred (a design decision, not a bug — the existing
fail-fast + FND-7 fallback behavior was correct). Applying the ADM-1 method (`/decide`,
2026-07-30): reversible, local, no charter touch, and the change is a strict superset
of existing behavior (same fallback if the retry also fails) — recognized as a safe,
low-cost win and built rather than left logged.
**What changed**: on a parse failure, one repair message is now sent on the SAME ADK
session/runner, so the retry re-prompts with the parse error rather than
re-investigating. `RunConfig`'s LLM-call headroom bumped `+4`→`+5` to cover the extra
round trip.
- **Resolution**: fixed:ae06eb7 — `AdkLiveRoundTripTest#malformedFinalResponseGetsOneRepairRetryThenSucceeds`
- **Escape**: n/a — this was correctly logged rather than escaped; the decision record
  is the point of interest, not a process gap.

---

## FND-33 — get_incident/find_similar_incidents took a model-suppliable incidentNumber · **HIGH**

**Where**: `TriageMateTools.getIncident`/`findSimilarIncidents`.
**What**: both tools accepted a free-form `incidentNumber` argument like any other
tool param, unbound to the incident this run was actually asked to diagnose — nothing
stopped the model from fetching (and effectively diagnosing) a different incident.
Found by the third `/doc-test cds` re-verification pass (direct Codex architecture
review).
- **Resolution**: fixed:e50ec13
- **Escape**: design review — the identity-binding gap wasn't caught in the original
  J2/J3 CDS convergence because bounds review focused on WHICH systems/scopes are
  reachable (allowlists), not WHICH incident a call operates on; needs to be an
  explicit architecture-review checklist item for any tool-calling agent design.

## FND-34 — Writeback + poller HTTP calls had no timeout at all · **HIGH**

**Where**: `DiagnosisOrchestrator.runOnce()`'s two `addWorkNote` calls,
`IncidentPoller.pollOnce()`'s `findIncidentsCreatedSince` call.
**What**: FND-15's wall-clock timeout only wraps `engine.diagnose()`. These calls run
directly on the caller's thread (K3's HTTP thread, K1's single scheduler thread) with
no bound, and `RestClient.Builder` had no configured timeout — a network partition
could hang either thread forever. Found by the third `/doc-test cds` re-verification
pass (direct Codex architecture review).
- **Resolution**: fixed:e50ec13
- **Escape**: implementation review — FND-15's own fix should have prompted the
  question "does this timeout cover every blocking call this run makes, or just one
  of them?"; narrow-scoped fixes for a broader-sounding problem name are an easy trap.

## FND-35 — J2 claimed a "repair retry" that was never implemented · **MEDIUM**

**Where**: `docs/design-java/concepts/J2-adk-agent-loop/README.md`,
`AdkDiagnosisEngine.parse()`.
**What**: J2's Design and Verification sections both claimed "one repair retry" on
malformed JSON; `parse()` actually throws immediately — the log line there ("one
repair retry recommended") was a recommendation in a log message, not implemented
behavior. Found by the third `/doc-test cds` re-verification pass (Phase 3 scenario
simulation).
- **Resolution**: fixed:e50ec13 (doc corrected; the retry itself logged as FND-42,
  a design decision, not built here)
- **Escape**: doc review — an aspirational MVP claim that was never actually built
  survived two prior `/doc-test cds` passes; scenario simulation (Phase 3, tracing a
  concrete malformed-JSON case end-to-end) is what finally caught it, suggesting
  conflict/architecture review alone under-covers "does the code do what the doc
  says for this exact case."

## FND-36 — writebackPosted reported config-intent, not actual outcome · **HIGH**

**Where**: `DiagnosisOrchestrator.runOnce()`.
**What**: `writebackPosted` was set from `writebackEnabled` (the config flag), not
whether the writeback actually succeeded. If the second `addWorkNote` call threw
after the first succeeded, the exception propagated out of `run()` uncaught — losing
the whole diagnosis result (already-produced report, first comment already posted)
and reporting nothing, rather than surfacing a truthful partial-failure. Found by the
third `/doc-test cds` re-verification pass (direct Codex architecture review).
- **Resolution**: fixed:e50ec13 — `DiagnosisOrchestratorTest#partialWritebackFailureIsDisclosedNotLost`
- **Escape**: implementation review — this is the second time a `writebackPosted`-class
  field diverged from ground truth (see FND-25); "does this field reflect an actual
  outcome or an intended one" deserves a standing checklist item for any disclosed
  status field, not just a one-time fix.

## FND-37 — Incident number wasn't normalized before FND-31's coalescing map · **LOW**

**Where**: `DiagnosisController.diagnose()`.
**What**: `"INC0012345"`, `"inc0012345"`, and `" INC0012345 "` coalesced as three
different keys, defeating FND-31's whole purpose for a caller that didn't type the
number identically. Found by the third `/doc-test cds` re-verification pass (direct
Codex architecture review).
- **Resolution**: fixed:e50ec13
- **Escape**: implementation review — a normalization step at an untrusted input
  boundary is an easy checklist item that was simply missed when FND-31 was built.

## FND-38 — GitLab project allowlist was documented, not enforced · **MEDIUM**

**Where**: `TriageMateTools.searchCode`, J6/J8 docs.
**What**: J6 and J8 both claimed an "allowlisted GitLab project" bound, matching the
Sumo-scope pattern (FND-20) — but `search_code` accepted any model-supplied project
string unchecked. Found independently three times in the third `/doc-test cds`
re-verification pass: Phase 2 (Codex/Gemini-style CDS validation agent), the direct
Codex architecture review, and J8's own internal self-contradiction (its "three
layers" section didn't list this as one of them).
- **Resolution**: fixed:e50ec13 — `TriageMateToolsSearchLogsTest#outOfAllowlistGitLabProjectIsRejected`
- **Escape**: doc review — same root cause as FND-20 (a bound stated in prose without
  a corresponding enforcement check); worth a standing rule that any claimed
  allowlist/bound gets grepped for its enforcement site before the doc ships.

## FND-39 — DiagnosisReportValidator only wired into the ADK engine · **LOW**

**Where**: `DeterministicDiagnosisEngine.diagnose()`.
**What**: FND-17's J4 semantic validator ran only on ADK-produced reports, not the
deterministic engine's — an asymmetric-trust gap, even though the deterministic
engine's hand-assembled report can't currently violate the contract. Found
independently twice in the third `/doc-test cds` re-verification pass (direct Codex
architecture review and a Claude architecture-review subagent).
- **Resolution**: fixed:e50ec13
- **Escape**: implementation review — when FND-17 built the validator, "wire it into
  every path that produces a `DiagnosisReport`" should have been the default framing,
  not "wire it into the path we're currently working on."

## FND-40 — Sumo scope allowlist duplicated across two engines · **LOW**

**Where**: `DeterministicDiagnosisEngine`, `TriageMateTools`.
**What**: `DeterministicDiagnosisEngine` hardcoded its own copy of the Sumo scope
list instead of reading `triage.sumo.allowed-scopes` like `TriageMateTools` does — the
two lists agreed only by coincidence of identical defaults. Found by the third
`/doc-test cds` re-verification pass (Claude architecture-review subagent).
- **Resolution**: fixed:e50ec13
- **Escape**: implementation review — a hardcoded literal that duplicates a value
  already expressed as config elsewhere is a common single-source-of-truth miss;
  worth a grep-for-duplicate-literals pass before closing out a config-driven bound.

## FND-41 — IncidentPoller trusted gateway ordering without validating it · **LOW**

**Where**: `IncidentPoller.pollOnce()`.
**What**: the cursor-advance logic assumes `found` is oldest-first, relying entirely
on `RealServiceNowGateway`'s `ORDERBYsys_created_on` query with no defensive check —
an out-of-order batch (a different gateway implementation, a future query change)
would silently corrupt the "unbroken handled prefix" invariant this class exists to
guarantee. Found by the third `/doc-test cds` re-verification pass (direct Codex
architecture review).
- **Resolution**: fixed:e50ec13
- **Escape**: implementation review — a correctness invariant that depends on an
  upstream ordering guarantee should defend itself rather than trust the guarantee
  holds forever; this is a general pattern worth a standing note in J10.

---

## FND-32 — `docs/design-java/STATUS.md` is stale in its own header and counts · **LOW**

**Where**: `STATUS.md:3` ("Phase: CDS Round 1 — concepts J1–J8 drafted") vs its table
showing J1–J10 all Built; `STATUS.md:21` ("Tests now 3/3 default and 5/5 `-Padk`") vs actual
16 default / 24 `-Padk`.

**Found by**: Phase 2 (both agents).

- **Resolution**: fixed:this-commit (STATUS.md phase line and test counts corrected; noted counts drift by construction per FND-30)
- **Escape**: doc-maintenance — the workspace-level status file is the most likely place to go stale precisely because no single concept card's owner is responsible for it.

---

## FND-30 — J10's test count is wrong · **LOW**

**Where**: `J10/README.md:96` ("10 unit tests in `IncidentPollerTest`").

**What**: there were 9 at the time of writing. A hand-maintained count in a doc drifts by
construction — better to state what is covered than how many.

**Found by**: Phase 2 (agent B).

- **Resolution**: fixed:this-commit (J10 no longer hardcodes a test count; points at mvn test as the source of truth)
- **Escape**: self-inflicted, structural — any hand-maintained count in a doc will drift the next time a test is added; the fix is not maintaining the number, not maintaining it more carefully.

---

## FND-29 — J3 documents `search(String cql)`; the code takes a plain query · **LOW**

**Where**: `J3/README.md:19` vs `ConfluenceGateway` and the `search_confluence` ADK schema
(`query`), and `MockConfluenceGateway` (keyword matching, not CQL).

**Found by**: Phase 2 (agent A).

- **Resolution**: fixed:this-commit (J3 corrected: search(String query), plain keyword match, not CQL)
- **Escape**: design-vs-build divergence — CQL was the original design intent; the build implemented simpler keyword matching and the interface sketch kept the old signature/terminology.

---

## FND-28 — J2 carries a stale ADK-version risk line · **LOW**

**Where**: `J2/README.md:95` ("ADK v0.8.0 API drift → pin the version") vs `J2:72-77`
("ADK-Java is GA 1.x — **not** the 0.8.0 the older docs show") and `pom.xml` (`1.7.0`).

**What**: the risk section contradicts the same card's own version note.

**Found by**: Phase 2 (agent A).

- **Resolution**: fixed:this-commit (stale ADK v0.8.0 risk line struck through in J2, with the resolution noted)
- **Escape**: doc-maintenance — a risk note was never removed after the spike that resolved it (JS-1, confirming ADK 1.7.0) landed, even though the same card's Versions section already stated the resolution a few lines above it.

---

## FND-27 — `triage.agent.max-tool-calls` is undeclared and unnamed · **LOW**

**Where**: `AdkDiagnosisEngine.java:95` (`@Value("${triage.agent.max-tool-calls:10}")`) vs
`application.yml` and J1/J2/J8.

**What**: the key that bounds the agent's tool budget appears in no config file and in no
card — it exists only as an inline default. Every other `triage.*` key is declared in
`application.yml` with a comment.

**Found by**: Phase 2 (agent A).

- **Resolution**: fixed:this-commit (triage.agent.max-tool-calls now declared in application.yml with a comment, referenced from J2)
- **Escape**: doc-maintenance — a value only ever existed as a Spring @Value inline default; nothing requires config keys to be declared where every other triage.* key lives.

---

## FND-26 — `seed-repo/` path is wrong · **LOW**

**Where**: `J6/README.md:35`, `J7/README.md:28`.

**What**: cited as repo-root `seed-repo/`; it lives at
`docs/design/concepts/log-code-reasoning/verification-s3/seed-repo/` (moved into `rovo/` and
the suspended CDS during the pivot).

**Found by**: Phase 2 (agent B).

- **Resolution**: fixed:this-commit (J6/J7 point at the real seed-repo path under the suspended Rovo CDS)
- **Escape**: doc-drift — the pivot (PIVOT.md, 2026-07-23) moved this path and the docs referencing it were not swept.

---

## FND-25 — J7 doesn't match the UI it describes, in three ways · **MEDIUM**

**Where**: `J7/README.md:10-20` vs `src/main/resources/static/index.html`.

**What**: J7 omits the "Who to talk to" card (J9) and the degraded-run banner, both present;
claims evidence entries render "source badges **+ links**" when the evidence card shows
source + summary only (links appear solely in the sources-comment block); and the
"Posted to ServiceNow — automatically" card renders **unconditionally**, reconstructed
client-side from the report rather than from `toSourcesNote()`/`toDiagnosisNote()`.

**Why it matters**: the last one is a correctness bug, not drift — with
`triage.writeback.enabled=false` (J5's own toggle) the UI still tells the audience comments
were posted when none were.

**Found by**: Phase 2 (agent B), Claude conflict.

- **Resolution**: fixed:this-commit (DiagnosisResult.writebackPosted field, set by the orchestrator after the real write decision; UI reads it instead of assuming; verified live true/false; plus J7 doc gaps for the contacts card, degraded banner, and evidence links now rendered)
- **Escape**: test-coverage — a UI claiming something the backend didn't guarantee had no test watching the seam between them; the false-claim case (writeback disabled) was never exercised.

---

## FND-24 — J1 offers an inbound ServiceNow webhook that J10 proves impossible · **MEDIUM**

**Where**: `J1/README.md:13` and the comment in
`src/main/java/com/company/triage/api/DiagnosisController.java:9` vs `J10/README.md`.

**What**: both call "a ServiceNow Business-Rule webhook to this same endpoint" the planned
upgrade requiring no code change. J10's entire premise — and the reason outbound polling was
chosen — is that ServiceNow **cannot reach** the corp-network laptop. It is not a stretch
goal; it is ruled out on this network.

**Why it matters**: it presents a dead end as the roadmap, in both a card and a code comment.
Valid only for a future non-corporate environment, which should be said explicitly.

**Found by**: Codex (HIGH), Claude conflict (HIGH).

- **Resolution**: fixed:this-commit (J1 doc + DiagnosisController.java comment both corrected: no inbound webhook is possible or planned)
- **Escape**: cross-card contradiction — J1 was written before the J10/K1 DDS concluded ServiceNow cannot reach the corp laptop at all; the webhook aspiration was never revisited once that constraint was discovered.

---

## FND-23 — Docs say the endpoint returns `DiagnosisReport`; it returns `DiagnosisResult` · **MEDIUM**

**Where**: `J1/README.md:12,40` and `J4/README.md` ("the single source") vs
`src/main/java/com/company/triage/api/DiagnosisController.java:23`.

**What**: the endpoint returns the `DiagnosisResult` wrapper (`report` + `trace` + `engine`),
which is what J7's UI actually consumes. J1's sketch returns the bare report, and J4 — the
declared single source — contains no `trace` or `engine`.

**Why it matters**: J4 should stay the single source for *diagnostic content* while
`DiagnosisResult` is documented as the API envelope carrying execution metadata. Right now
neither card describes the actual response shape. Also: J1 says "202/200"; only 200 is
returned.

**Found by**: Phase 2 (agent A), Codex (HIGH), Claude conflict.

- **Resolution**: fixed:this-commit (J1/J4 corrected: endpoint returns DiagnosisResult not DiagnosisReport, 200 only)
- **Escape**: design-vs-build divergence — the FND-8 fix (adding DiagnosisResult as the API envelope) changed the actual response shape and no card was updated to match.

---

## FND-22 — J1's declared dependencies are wrong and imply a cycle · **MEDIUM**

**Where**: `J1/README.md:3` (`Depends on: J3, J4`) vs its own Design section (uses J2, J5,
J8) and `J2/README.md:3` (`Depends on: J1`).

**What**: J1 omits J2/J5/J8; J2 declares J1 → J1↔J2 reads as circular.

**Why it matters**: resolvable by naming the direction properly — J1 owns the
`DiagnosisEngine` interface, J2 implements it — but as written the graph is unusable for
dependency-ordered planning.

**Found by**: Codex conflict.

- **Resolution**: fixed:this-commit (J1 depends-on corrected to J3,J4,J5,J8; J1 does not depend on J2 - J2 depends on J1's interface)
- **Escape**: design-review — the apparent J1<->J2 cycle was never resolved because nobody asked 'which one owns the interface' at CDS time; it just shipped ambiguous.

---

## FND-21 — Cross-reference gaps: cards don't mention what depends on them · **MEDIUM**

**Where**: `J1`, `J5` (never mention J10 though J10 declares both as dependencies);
`J6`, `J7`, `J2` (don't reciprocate J9); `J3/README.md:13-19` (interface sketch omits
`findIncidentsCreatedSince`, `contributors`, `recentCommitters` — all of which exist and one
of which is J10's entire trigger).

**What**: dependency edges are declared one-directionally, so reading a card doesn't reveal
who relies on it.

**Why it matters**: J3 presents its interface as the complete typed contract; a reader
implementing a new gateway from it would miss three methods.

**Found by**: Phase 2 (both agents), Claude conflict.

- **Resolution**: fixed:this-commit (J1/J5/J6/J7/J2/J3 cross-reference J9/J10 and each other correctly)
- **Escape**: process — new cards (J9, J10) were added without a pass updating the cards they depend on/extend to reference back; dependency edges were only ever recorded one-directionally.

---

## FND-20 — J6 says the app supplies the log time window; the model does · **MEDIUM**

**Where**: `J6/README.md:21-22` vs
`src/main/adk/java/com/company/triage/agent/TriageMateTools.java` (`search_logs` takes
`fromIso`/`toIso` from the model) and `application.yml:53`.

**What**: J6 claims "the app supplies a fixed time window / max number of searches" as a
bound. The window is a model-supplied parameter, the result cap is hardcoded `20`, and
`triage.sumo.max-results` is **dead config** — declared and never read.

**Why it matters**: a bound the model chooses is not a bound. Same class as the allowlist
finding fixed in `b8b2dd0`, on a different parameter.

**Found by**: Phase 2 (agent B).

- **Resolution**: fixed:this-commit (window clamped to triage.sumo.max-window-minutes anchored on the end time; triage.sumo.max-results actually read, not hardcoded 20; 5 tests)
- **Escape**: dead-config — a key was declared in application.yml (aspirational) but the code path that should have read it was never wired up; nothing detects a config key nobody reads.

---

## FND-19 — J8 claims prompt-injection verification; no such test exists · **MEDIUM**

**Where**: `J8/README.md:41` vs `src/test/` + `src/adk-test/` (six test classes, none
injection-related).

**What**: J8 is marked 🟢 Built and asserts untrusted input (incident text, comments, wiki
pages, log messages, source) is treated as data, never instructions. Nothing tests it.

**Why it matters**: it is the one J8 guardrail with no mechanical backing at all — the
others at least exist in code. **Note for whoever fixes this**: this repo has a pre-commit
guard that blocks raw injection payloads in source; such a test must load payloads from an
allowlisted fixture (`**/fixtures/**`, `*.payloads.jsonl`), not inline them. It already
fired once during this session.

**Found by**: Phase 2 (agent B), Claude conflict.

- **Resolution**: fixed:this-commit (PromptInjectionGuardrailTest — architectural guarantee proven: no reassign/close/priority method exists at all, and 5 fixture payloads never change write behaviour)
- **Escape**: test-coverage — a guardrail claim with no real LLM available offline to red-team was left permanently untested rather than reframed to what could actually be proven mechanically.

---

## FND-18 — J8's trace spec doesn't match what is traced · **MEDIUM**

**Where**: `J8/README.md:31-33` vs `DiagnosisOrchestrator.java:60` and the engines' traces.

**What**: three mismatches. J8 says the per-run trace records "human accept/reject" (a field
of the confirm gate removed 2026-07-23); says a "structured JSON log per run" while the code
emits one plain-text line; and claims query params (sans secrets) and the model id are
captured — the trace records tool names only.

**Found by**: Phase 2 (agent B), Claude conflict.

- **Resolution**: fixed:this-commit (J8 corrected: no human accept/reject field, one plain-text trace line not structured JSON, tool names only)
- **Escape**: aspirational-claim-as-fact — an MVP observability spec written before implementation, never reconciled against what was actually built, compounded by the 2026-07-23 confirm-gate removal that made 'human accept/reject' actively wrong.

---

## FND-17 — J4 claims a report validator that does not exist · **MEDIUM**

**Where**: `J4/README.md:53,69-70` ("a validator the agent's final step must satisfy",
"validator rejects … empty `candidateSystems` or a dangling `evidenceRef`").

**What**: no validator exists in `src/main` or `src/main/adk`. `AdkDiagnosisEngine.parse()`
does a Jackson `readValue` and throws on malformed JSON — it does not check the documented
semantic rules (non-empty candidates, every `evidenceRef` resolving).

**Why it matters**: J4's rules are the contract the ADK prompt is supposed to be held to.
Unvalidated, a model can return schema-shaped JSON with dangling refs and the UI renders it.

**Found by**: Phase 2 (agent A).

- **Resolution**: fixed:this-commit (DiagnosisReportValidator implemented, wired into AdkDiagnosisEngine.parse(); 7 tests)
- **Escape**: aspirational-claim-as-fact — J4's Rules/Verification sections stated a validator existed since the card was first written; nobody checked the claim against the code until this pass, and it also caught a real dangling-ref bug in the test fixture itself.

---

## FND-13 — J2 specifies a six-step `SequentialAgent` with per-step allowlists; the code is one flat `LlmAgent` · **MEDIUM**

**Where**: `J2/README.md:58` (and the macro-flow section) vs
`src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java` (`LlmAgent.builder()`,
all eight tools registered at once).

**What**: J2 describes `SequentialAgent` with sub-steps (`understand → identifyCandidates →
knowledge → logs? → code? → report`), each an `LlmAgent` limited to that step's tools. The
implementation is a single `LlmAgent` holding every tool.

**Why it matters**: this is now the *only* remaining overstatement in the J8 guardrail story.
The allowlist fixed in `b8b2dd0` is **global** (all eight tools, all the time); J2 claims a
**per-step** allowlist, which is a stronger property the code does not have. Either narrow
the doc or implement staged agents — but the doc should not claim the stronger one.

**Found by**: Phase 2 (agent B), Claude conflict (HIGH).

- **Resolution**: fixed:this-commit (J2 corrected: one flat LlmAgent with a global allowlist, not per-step SequentialAgent)
- **Escape**: design-vs-build divergence — same class as FND-12: the sketch specified staged sub-agents, the build simplified to one agent, the card kept the original design.

---

## FND-12 — Docs name classes that were never written · **MEDIUM**

**Where**: `J1/README.md:27-29`, `J2/README.md:94`.

**What**: `IncidentUnderstandingService`, `ReportService`, `AdkAgentConfig`, `AiConfig`,
`Allowlists` do not exist. What exists: `AdkModelFactory`, `AdkDiagnosisEngine`,
`BoundsCallback`, `TriageMateTools`, `IntegrationProperties`, `DeterministicDiagnosisEngine`,
`IncidentPoller`. Symptom clarification lives inside the engines, not a separate service.

**Why it matters**: these read as a map of the codebase. Following it wastes a reader's time
and hides the components that do exist (notably both engines and the poller).

**Found by**: Phase 2 (both agents), Claude conflict.

- **Resolution**: fixed:this-commit (J1/J2 no longer name IncidentUnderstandingService/ReportService/AdkAgentConfig/AiConfig/Allowlists; state what exists instead)
- **Escape**: design-vs-build divergence — these were the CDS-time design sketch; implementation consolidated responsibilities differently and nobody reconciled the card.

---

## FND-11 — Docs locate the app in `app/`, which does not exist · **MEDIUM**

**Where**: `docs/design-java/STATUS.md:10` ("Implementation in `app/`"),
`J5/README.md:43` ("Runbook: `app/README.md`").

**What**: there is no `app/` directory. `pom.xml` and `src/` are at the repo root (moved in
`23778f4`, "flatten Java app to repo root"); the runbook is
`docs/design-java/DEMO-RUNBOOK.md`.

**Found by**: Phase 2 (both agents), Claude conflict.

- **Resolution**: fixed:this-commit (STATUS/J5 point at repo root + DEMO-RUNBOOK.md)
- **Escape**: doc-drift — a structural refactor (23778f4, flatten app/ to repo root) landed without a sweep of the docs that named the old path.

---

## FND-10 — Docs say Spring `@Profile(mock|real)`; the code uses `@ConditionalOnProperty` · **MEDIUM**

**Where**: `J1/README.md:30`, `J3/README.md:25,28`, `docs/design-java/STATUS.md:18` vs all
eight gateways in `src/main/java/com/company/triage/gateway/` and `application.yml`.

**What**: no gateway carries `@Profile`, and there is **no `mock` profile at all**.
Selection is per-connector: `@ConditionalOnProperty(name="triage.connectors.<x>",
havingValue="mock"|"real")`. That is strictly better than profiles (mix real ServiceNow with
mock evidence — which the demo actually relies on), so the code is right and the docs are
wrong.

**Why it matters**: someone following the docs would run `-Dspring.profiles.active=mock`
and get no gateways at all. The workspace README was already corrected in `b8b2dd0`; these
three remain.

**Found by**: Phase 2 (both agents), Claude conflict.

- **Resolution**: fixed:this-commit (J1/J3/STATUS corrected to @ConditionalOnProperty)
- **Escape**: doc-drift — the actual selection mechanism changed during implementation (a real improvement: per-connector mixing) and the cards describing it were never updated to match.

---

## FND-9 — Every concept header still says 🟡 Drafted; STATUS says 🟢 Built · **MEDIUM**

**Where**: `docs/design-java/concepts/J{1,2,3,5,6,7,8}/README.md:3` vs
`docs/design-java/STATUS.md` concept table.

**What**: seven of ten cards claim `State: 🟡 Drafted` while STATUS marks them 🟢 Built and
working code + passing tests exist. J9/J10 are correct, so this is the older cards not
being touched after implementation.

**Why it matters**: the header is the first thing read. "Drafted" invites someone to
redesign a built, tested component.

**Found by**: Phase 2 (both agents).

- **Resolution**: fixed:this-commit (all 7 headers Drafted -> Built)
- **Escape**: doc-maintenance — a card's State header is set once at CDS time and never revisited after implementation lands; nothing prompts an update.

---

## FND-16 — The UI detects degradation by regex over the trace, not the `engine` field · **MEDIUM**

**Where**: `src/main/resources/static/index.html:83` vs
`src/main/java/com/company/triage/orchestration/DiagnosisResult.java`.

**What**: the degraded-run banner matches `/degraded to the deterministic engine/` against
trace strings. `DiagnosisResult.engine` + `degraded()` exist for exactly this and are ignored
by the UI.

**Why it matters**: mine, from the FND-8 fix — I added the field *and* the banner in the same
session and wired the banner to the string. FND-8's own resolution says string-matching a
trace "is not a contract"; the UI is currently the counter-example. Rewording the trace line
would silently break the banner.

**Found by**: Claude conflict.

- **Resolution**: fixed:this-commit (index.html reads data.engine === 'DEGRADED_TO_DETERMINISTIC' directly; verified against a real degraded ADK response, and confirmed the old string-match would have gone silently blank after rewording the trace line)
- **Escape**: self-inflicted, caught the same session — the field and the banner were added together, and nothing forced them to agree with each other. A field that exists specifically to replace a string-match should be used by the FIRST thing that needs the distinction, not retrofitted after.

---

## FND-14 — J5 claims `addWorkNote` is idempotent; only the mock actually dedupes · **MEDIUM**

**Where**: `J5/README.md:34,50` vs
`src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:104-115` and
`MockServiceNowGateway:105`.

**What**: `MockServiceNowGateway` skips an identical note. `RealServiceNowGateway.addWorkNote`
PATCHes unconditionally — no "does an identical AI note already exist?" check.

**Why it matters**: J5 cites this idempotency as a guardrail, and the J10 poller's FND-1
write-up lists it as the *fourth* layer against duplicate work. Against a real instance that
layer is absent — a retried or re-triggered run posts duplicate advisory comments onto a
real customer-visible ticket. This is the entry I'd fix first: it is the only one where a
claimed safety layer is missing on the **real** connector rather than in prose.

**Found by**: Phase 2 (agent A).

- **Resolution**: fixed:this-commit (RealServiceNowGateway checks sys_journal_field for an exact-match existing entry before PATCHing; RealServiceNowGatewayTest via MockRestServiceServer)
- **Escape**: test-coverage — the real connector's HTTP behavior had no regression test at all; only the mock's dedupe was ever exercised, so the two connectors silently diverged on a claimed safety property.

---

## FND-15 — J1 claims a wall-clock timeout and max-tool-calls it does not enforce · **MEDIUM**

**Where**: `J1/README.md:19-20,50` ("Enforces a hard wall-clock timeout + max-tool-calls",
"honored (inject a slow mock)") vs
`src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java`.

**What**: the orchestrator enforces neither. It only measures elapsed time for a log line.
Max-tool-calls exists solely in `-Padk` `BoundsCallback`, so **the default deterministic
path has no tool bound at all**, and no timeout exists anywhere in `src/main`. The stated
verification ("inject a slow mock") was never performed.

**Why it matters**: a hung gateway hangs the request indefinitely — including on the K1
poller's thread, where nobody is watching. The deterministic path being unbounded is
tolerable (no LLM, fixed work) but is not what J1 says.

**Found by**: Phase 2 (both agents), Claude conflict.

- **Resolution**: fixed:this-commit (every engine call now runs on a virtual thread bounded by triage.orchestrator.timeout-ms=45000; a timeout on the primary feeds the normal FND-7 fallback)
- **Escape**: contract-drift — J1 stated a guarantee ('honored, inject a slow mock') that was never actually tested, so the gap between claim and code was invisible until doc-vs-code validation looked for the promised test and found none.

---

## FND-31 — The manual endpoint bypasses the poller's in-flight/completed state · **MEDIUM**

**Where**: `src/main/java/com/company/triage/api/DiagnosisController.java` →
`DiagnosisOrchestrator.run()` vs
`src/main/java/com/company/triage/orchestration/IncidentPoller.java` (`inFlight`,
`completed`).

**What**: the poller's duplicate-suppression sets live **inside the poller**. A manual
`POST /api/diagnose/{number}` calls the orchestrator directly, so it neither consults nor
updates them. With polling enabled, a manual trigger can diagnose an incident the poller is
mid-run on, or one it has already completed — two concurrent diagnoses of the same ticket,
and four advisory comments (or two, plus a duplicate the real gateway won't dedupe — see
FND-14).

**Why it matters**: this is precisely the demo shape — polling on, presenter triggers
manually to show the flow. It's also the one finding Gemini produced, and neither Codex nor
Claude found it. Cheapest fix is probably to move the guard out of the poller into the
orchestrator, where both entry points meet.

**Found by**: Gemini conflict (HIGH) — sole source.

- **Resolution**: fixed:this-commit (DiagnosisOrchestrator.run() coalesces concurrent calls for the same incident number via a ConcurrentHashMap<String,CompletableFuture> — the second caller awaits the first's result instead of starting a duplicate; two latch-forced concurrency tests)
- **Escape**: design-review — K1 (poller) and K3 (manual) were designed and built in separate sessions without a round asking 'what happens when both fire on the same incident at once?'. The guard existed in exactly one of the two entry points.

---

## FND-8 — A degraded run is indistinguishable from a live one at a glance · **HIGH**

**Where**: `src/main/resources/static/index.html` (trace rendering) and the
`DiagnosisOrchestrator` fallback introduced for FND-7.

**What**: the FND-7 fallback works — but it is *quiet*. On degradation the app still
returns **HTTP 200** with a complete, plausible, genuinely-correct report; the only signal
is one line at the top of the tool-call trace, rendered as grey monospace in a card at the
**bottom** of the page.

**This is not hypothetical — it already happened, to the project's own operator.** During
spike C2 (2026-07-30) the ADK engine threw immediately on a blank
`triage.integrations.llm.api-key`, degraded to the deterministic engine, and returned a
correct `Payments Platform Support` assignment with a real log↔code citation and two posted
comments. The run was reported as *"successfully ran C2"*. **No LLM was called at all.**
Evidence: `bin/spikes/spike-output.log`, trace line 1.

**Why it matters**: the demo's central claim is *"this is a high Copilot model reasoning,
on rails"* (D1) and D3's contrast asserts the model is the same frontier one Copilot CLI
runs. Presenting a silently-degraded run makes both statements false on stage — the exact
failure mode we removed on the *model-tier* axis (a mini model masquerading as frontier)
reappearing on the *engine* axis. It also cost a wasted spike cycle: C2's real question
(does a frontier model converge in the 14-call budget, and emit valid J4 JSON?) remains
unanswered, while looking answered.

**Mitigated 2026-07-30, not fully closed.** Two changes:
1. The UI now renders a **prominent amber banner** above the report when any trace line
   matches `degraded to the deterministic engine` — "⚠ Degraded run — this is NOT the live
   agent … no LLM was involved — do not describe it as model reasoning."
2. `secrets.properties.example` no longer ships a blank `llm.api-key` (the specific trigger
   here), with a comment explaining that blank ≠ optional.

**Still open for CDS**: the API response itself carries no machine-readable engine field —
a consumer (the ServiceNow work note, a future caller) still cannot distinguish a live from
a degraded run without string-matching the trace. Options: add an `engine`/`degraded` field
to `DiagnosisResult` (touches the J4 contract, hence a design call) · label the posted work
note when degraded · return a distinct HTTP status. Worth deciding before the poller (K1)
runs unattended, where nobody is watching a UI at all.

- **Resolution**: fixed:d1f0866 + 694ec08 (UI banner + DiagnosisResult.engine field)
- **Escape**: observability — the FND-7 fallback was correct but silent, and 'looks like success' is the most expensive kind of wrong. It cost a spike cycle before being noticed.

---

## FND-7 — `LlmCallsLimitExceededException` is unhandled: the J8 safety cap crashes
instead of degrading · **HIGH** · ✅ **RESOLVED 2026-07-30**

> **Resolution**: `DiagnosisOrchestrator` now catches any exception from the primary
> engine and falls back to the deterministic engine (chose that option from the three
> listed below — it reuses proven, tested, network-free code instead of fabricating a
> partial J4 report). `DeterministicDiagnosisEngine` is registered unconditionally (no
> longer gated on `triage.engine`) so it's always available as the fallback;
> `AdkDiagnosisEngine` is `@Primary` so it still wins as the active engine when both
> beans exist. The fallback is disclosed as the first trace line, never silent. When the
> active engine already IS the deterministic one, failures propagate normally — no
> self-fallback masking a real bug. Verified: `mvn test` and `mvn -Padk test` both pass
> (two new tests cover the fallback firing and the propagate-when-already-fallback case);
> re-ran the live `-Padk` app against the stub proxy configured to never converge — was a
> 500, now `HTTP 200` with `trace[0]` reading "⚠ primary engine did not converge
> (RuntimeException: …LlmCallsLimitExceededException…) — degraded to the deterministic
> engine". See `src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java`.

Original finding (kept for record):

**Where**: `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java` —
`runAgent()` (the `blockingForEach` call, ~line 158) has no `try/catch`; `diagnose()`'s
only `catch` (line 173, inside `parse()`) is for JSON parse failures, not this.

**What**: confirmed by actually running the `-Padk` build on 2026-07-30 (first time the
build has been executable — a JDK/Maven weren't available earlier in the project).
Pointed `triage.integrations.llm.*` at a local stub proxy (`bin/fake-openai-proxy.py`,
extended to fill tool-call arguments by JSON-schema type so it wouldn't produce its own
false failures) that always answers with a `tool_calls` response, never a final prose/JSON
answer — which is a legitimate model behavior a poorly-prompted or struggling real model
can also produce. The agent loop keeps calling tools until the `RunConfig.setMaxLlmCalls`
backstop (`maxToolCalls + 4` = 14 by default) trips
`com.google.adk.models.LlmCallsLimitExceededException`, which propagates uncaught through
`diagnose()` → the controller → an **unhandled 500** with a raw stack trace.

**Why it matters**: this is precisely the J8 "hard backstop on top of the tool-call
bounds" (the code comment's own words) — the mechanism that exists so a model that won't
converge can't run forever. It works as a *limiter*. It does not work as *demo-safety*:
tripping it crashes the request instead of returning a bounded, honest advisory
("investigation did not converge within its budget; partial evidence: …"), which is
exactly the failure mode D2 (deterministic fallback) and the whole "advisory-only,
bounded, never fails ugly" pitch are supposed to prevent. On stage, a model that stalls or
loops (rate limiting, an ambiguous incident, a proxy hiccup) would 500 instead of
gracefully degrading — and the runbook's fallback flip (switch browser tabs to :8081) only
helps if someone notices the crash and reacts; it doesn't make T2 itself safe.

**Options to weigh in CDS** (not decided): catch the exception in `runAgent()`/`diagnose()`
and synthesize a partial `DiagnosisReport` from whatever evidence the tool-call trace
already gathered · catch and fall back to invoking `DeterministicDiagnosisEngine` for that
request (auto-flip, not just the manual one in the runbook) · catch and return a plain
advisory-text response distinct from the strict J4 JSON shape, with `confidenceOverall`
forced to the lowest tier.

**What's still unverified** (out of scope for this stub, deliberately not fabricated): a
*real* model, given the actual tool schemas and incident context, may converge well within
the 14-call budget and never hit this path at all — the corp-laptop E2 spike against the
real Copilot-served model is the only way to know. This finding is about the missing
safety net, not a claim that the cap will trip in practice.

- **Resolution**: fixed:d1f0866 (orchestrator degrades to the deterministic engine)
- **Escape**: test-coverage — the J8 backstop was implemented and never exercised, so nobody saw that tripping it produced an unhandled 500 rather than a graceful degrade.

---

## FND-6 — `D1`–`D5` means two different things in two DDS · **LOW**

**Where**: `docs/discovery/servicenow-triage-java/STATUS.md` (D1–D5 = agent-engine forks)
vs `docs/discovery/orchestrator-vs-copilot-cli/4-decide/concepts-extracted.md` (D1–D4 =
demo paths).

**What**: the same ids label unrelated things in two live workspaces. Everything in this
file and in `DEMO-RUNBOOK.md` uses the *demo-path* namespace (D1 = our orchestration on a
high model, D2 = deterministic fallback), but a reader arriving from the Java DDS will
resolve them to the engine forks.

**Why it matters**: pre-existing and low-impact, but it is a live ambiguity in the demo
docs. Cheapest fix is renaming one set at the next CDS round.

- **Resolution**: fixed:this-commit (namespace notes on both DDS STATUS files; not renamed)
- **Escape**: naming-convention — two workspaces minted the same id prefix independently; nothing reserves or namespaces card ids across DDS workspaces.

---

## FND-5 — J6 claims the log↔code citation needs no deterministic engine · **MEDIUM**

**Where**: `docs/design-java/concepts/J6-knowledge-tools/README.md:37-41` ("no
deterministic engine") vs
`src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java:112-116`.

**What**: `DeterministicDiagnosisEngine` produces the same `file:line` citation with no
LLM at all, and it is both the app default and D2's on-stage fallback. J6's claim is a
statement about the *agentic* path that reads as a statement about the system.

**Why it matters**: it undercuts D2 — if the deterministic engine can't do log↔code
citation, the fallback doesn't preserve the evidence trail. It can; the doc says otherwise.

- **Resolution**: fixed:this-commit (J6's claim scoped to the agentic path)
- **Escape**: doc-precision — a true statement about one code path written as a statement about the system. It undercut D2 by implying the fallback loses the evidence trail.

---

## FND-4 — LLM config surface: C2 vs J2 disagree · **MEDIUM**

**Where**: `docs/discovery/copilot-cli-runtime/4-decide/concepts-extracted.md:14-16`
(`triage.integrations.llm.{base-url,api-key,model}`) vs
`docs/design-java/concepts/J2-adk-agent-loop/README.md:15-19` (`env("LLM_BASE_URL")` /
`env("LLM_API_KEY")`).

**What**: the code (`AdkModelFactory`) actually resolves *both*, most-specific-first
(system property → env → `secrets.properties`), so nothing is broken — but J2 documents
only the older env-var form, and C2/`secrets.properties` is now the documented route.

- **Resolution**: fixed:this-commit (J2 documents the full most-specific-first resolution order)
- **Escape**: doc-drift — config surface changed (env vars -> secrets.properties) and the card was not updated with it. Cheap to catch, and it later cost a whole spike cycle when a blank api-key silently degraded C2.

---

## FND-3 — The two-engine split (D2) is owned by no J-card · **MEDIUM**

**Where**: `docs/discovery/orchestrator-vs-copilot-cli/4-decide/concepts-extracted.md`
(D2 = deterministic engine as the guaranteed fallback) vs
`docs/design-java/concepts/J1-spring-boot-orchestrator/README.md:16-18` and `J2`.

**What**: D2 makes the deterministic/ADK split load-bearing for the demo, but J1 describes
a single path ("invokes the ADK agent (J2)"), and neither J1 nor J2 mentions the
`triage.engine` switch — even though `deterministic` is the **default**
(`matchIfMissing = true`). The demo's stage safety net exists only in code and in the
runbook, not in the design.

- **Resolution**: fixed:this-commit (J2 documents the two-engine split; J1 done in d1f0866)
- **Escape**: design-review — D2 was decided in a DDS and implemented in code, but no CDS card owned it, so the demo's stage safety net existed only in the runbook.

---

## FND-2 — `suggestedContacts` (J9) absent from the J4 report contract · **MEDIUM**

**Where**: `docs/design-java/concepts/J9-contact-suggestion/README.md:29-30` vs
`docs/design-java/concepts/J4-diagnosis-report/README.md:12-39,50`.

**What**: J9 adds `suggestedContacts` to the report and the Java record carries it, but
J4 — marked **🟢 Stable** and declared "the single source" that both the ServiceNow
comments and the UI derive from — does not list the field. The apparent intent (contact
names must not reach the ServiceNow work note) is stated nowhere in the contract.

**Why it matters**: J4 is the schema of record. An unstated carve-out is exactly the kind
of thing that leaks personal names into a customer-visible ticket.

- **Resolution**: fixed:this-commit (J4 contract documents suggestedContacts + the UI-only carve-out; locked by DiagnosisReportNoteTest)
- **Escape**: contract-review — the carve-out WAS documented on the Contact record but never propagated into J4, the declared schema of record. A privacy invariant enforced only by 'no code happens to reference the field' is not enforced.

---

## FND-1 — Poller re-trigger loop: J5's own writes bump the K2 cursor · **HIGH** · ✅ **RESOLVED 2026-07-30**

> **Resolution**: the poller was built (CDS `J10-incident-poller`) querying
> **`sys_created_on`**, not `sys_updated_on`. Creation time is immutable, so J5's work-note
> writes cannot resurface a ticket — a structural fix rather than a filter, and what
> `C-T3: insert-only` always intended. Three further layers: an in-flight claim set, a
> bounded completed set, and J5's existing note-level idempotency.
>
> Two additional bugs were found and fixed while implementing it, both of which would have
> caused **silent incident loss** (the opposite failure to the one this finding describes):
> advancing the cursor to `now()` after a batch drops anything created *during* processing;
> and a "newest handled" high-water mark drops an early failure whenever a later incident in
> the same batch succeeds. The cursor now advances only across an unbroken run of handled
> incidents, oldest first. Both are asserted as tests
> (`incidentCreatedDuringProcessingIsNotSkipped`, `failedIncidentIsRetriedAndDoesNotStopTheBatch`).
>
> **Still open, tracked on the J10 card**: cursor + completed set are in-process only, so a
> restart skips incidents created while the app was down.

Original finding (kept for record):

**Where**: `docs/discovery/servicenow-local-trigger/4-decide/concepts-extracted.md`
(K1 `sysparm_query` is *updated-since*, K2 cursor is max `sys_updated_on`) vs
`docs/design-java/concepts/J5-servicenow-gateway/README.md` (two automatic work-note
writes per run) vs `docs/discovery/servicenow-auto-trigger/4-decide/decision.md`
(constraint **C-T3: insert-only** — search the file for `C-T3`; line numbers shift).

**What**: K1 selects incidents by `sys_updated_on > cursor`. J5 then posts two work notes
to the incident, which **updates** it, advancing `sys_updated_on` past the cursor. On the
next poll the same incident is selected again → diagnose → post → re-select.

J5's idempotency guard ("skip if an identical AI note already exists") probably prevents
*duplicate comments*, but not the repeated LLM run behind them — which is the expensive
part, and on a Copilot seat also the ToS-sensitive part (C6).

C-T3 "insert-only" was the original defence against exactly this, and the K1/K2 design
does not carry it forward. Neither DDS references the other's trigger constraint.

**Why it matters**: an unattended poller loops on every incident it touches.

Demo-day exposure depends on which trigger actually runs. K1 polling is now the *current
decision* (it supersedes `servicenow-auto-trigger`'s manual-only deferral), so **if the
demo runs K1, this is live on stage** — the loop would re-diagnose the incident seconds
after the work notes post, burning Copilot calls in front of the audience. If the demo
runs the K3 manual trigger, it is latent. Either way it must be resolved before any
unattended run.

**Options to weigh in CDS** (not decided): filter the poll query to exclude records whose
last update was by the triage service account · track a processed-incident set keyed by
`sys_id` + content hash · make the cursor advance on `sys_created_on` instead · make J5's
write suppress the cursor bump explicitly.

- **Resolution**: fixed:694ec08 (poller built on sys_created_on; CDS card J10-incident-poller)
- **Escape**: design-review — the DDS specified an 'updated-since' cursor and no round asked 'what does our own write do to this query?'. A self-triggering trigger is a predictable class, not a surprise.

---

## FND-84 — `findSimilarIncidents` returned zero hits for every incident, always · **HIGH** · *field-reported*

**Where**: `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:130`
(`findSimilarIncidents`) and `:314` (`firstKeyword`).

**Reported by**: the operator, from live runs — "ServiceNow's Find Similar Incidents always
returns zero hits", 2026-08-05.

**What**: the entire retrieval was one encoded query,
`stateIN6,7^short_descriptionLIKE<first word of the subject line>`, with a hardcoded `0.5`
similarity written onto every row it returned. Three faults, only the first of which showed
up as the zero-hit symptom:

1. **The retrieval key was chosen by position, not by information.** The first word of a real
   subject line is a sentence opener — "Unable", "Users", "Cannot". For INC0010010
   (`docs/Siyad_Findings.md` §2) the whole query reduced to `short_descriptionLIKEHazards`.
2. **`cmdb_ci` — the field naming the affected system, and the strongest available match key —
   was never used.** Doubly dead before J24/SFF-1, whose reference-field parse bug returned
   `""` for it anyway.
3. **The score was fabricated.** `DeterministicDiagnosisEngine:159` renders it as
   `"%s (%.0f%% similar)"`, so every hit would have advertised "50% similar" in an advisory
   note posted onto a real ticket.

`stateIN6,7` additionally hardcodes out-of-the-box state values; an instance with customised
states returns nothing regardless of the keyword.

**Why it matters**: J5 calls similar incidents "often the strongest routing signal", and both
engines depend on it — the deterministic one at `DeterministicDiagnosisEngine:156`, the ADK
agent through the `find_similar_incidents` tool, whose prompt (`AdkDiagnosisEngine:105`) tells
the model it is "the strongest routing signal". Both were reasoning from a permanently empty
list. Because empty is a legitimate result, this never surfaced as an error — the trace said
`→ 0 hits` and the report simply routed on weaker evidence.

**Fix**: retrieve wide on the keys that carry signal, then rank locally — two overlapping
passes (same CI, most-recently-resolved first; then an OR-group over the ticket's distinctive
symptom terms), deduped and scored by `SimilarIncidentRanker` (text Jaccard 0.6 + CI 0.3 +
category 0.1), floored and capped from config. `resolved-states`, `similarity-floor` and
`max-similar` are now `triage.servicenow.*` properties rather than literals. Tokenising moved
to `model/SymptomTokens` so `IncidentSignals` and the ranker share one stopword list.

- **Resolution**: fixed (2026-08-05) — `SimilarIncidentRanker` + `SymptomTokens` added;
  `RealServiceNowGatewayTest` now asserts the query shape at the HTTP boundary.
- **Escape**: test-coverage — every test that exercised similar-incidents ran against
  `MockServiceNowGateway`'s two hardcoded rows, so the real query's shape was never asserted
  anywhere. This is the fourth instance of the identical mock-only blind spot (FND-47
  `u_environment`, FND-61 journals, J24/SFF-1 reference fields): a connector method with no
  test against real request/response shapes is a method whose behaviour against the live
  instance is unknown, and "returns an empty list" is the failure mode that hides best.

---

## FND-85 — `_loglevel` read as `loglevel`: every Sumo row came back with no level · **HIGH** · *field-reported*

**Where**: `src/main/java/com/company/triage/gateway/real/RealSumoGateway.java` (row mapping)
and `src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java:262`.

**Reported by**: the operator — "check why sumo.search() says errorToken=null; is it some kind
of error?", 2026-08-05.

**What**: the gateway mapped each result row with `f.path("loglevel")`. The field Sumo actually
returns is **`_loglevel`, with a leading underscore** — verified against the live AU instance,
where a real row carries `_loglevel = ERROR`. The misspelled key yielded `""` on every row ever
returned, which is indistinguishable from "this line genuinely has no level".

**Why it matters**: `DeterministicDiagnosisEngine:262` selects the error line with
`"ERROR".equals(l.level())`. That could never be true, so `errorLine` was always null →
`errorToken` always null → the `if (errorToken != null)` guard at `:298` never opened → **the
GitLab code search never ran against real data**, and the log↔code citation (RC3, the
"this log line is emitted at file:line" evidence) was never produced on a real run.

The failure was invisible because the Sumo search itself worked perfectly. The trace printed
`→ 20 line(s); errorToken=null`, which reads as "we searched, found logs, and there were no
errors today" — a plausible, healthy-looking sentence. Nothing distinguished it from the real
state: "we found 14 ERROR rows and threw the level away".

Measured on `delivery-hazards/prod`, 24h window, 2026-08-05:

| | before | after |
|---|---|---|
| rows returned | 20 | 20 |
| rows at level ERROR | **0** | **14** |
| derived `errorToken` | `null` | **`GNAF_FRONTAGE`** |

**Fix**: read `_loglevel`, and fall back to parsing the level out of `_raw` (Spring Boot's
default layout) when the field is absent — the field comes from a Sumo field-extraction rule,
so a source without that rule configured would otherwise reopen the identical hole.

- **Resolution**: fixed (2026-08-05) — `RealSumoGateway.level(...)` extracted and unit-tested
  against a verbatim live row (`RealSumoGatewayLevelTest`), plus a live assertion
  (`RealSumoGatewayLiveTest.errorRowsComeBackWithTheirLevelParsed`).
- **Escape**: integration-fidelity — no test ever asserted the SHAPE of a real Sumo response.
  `RealSumoGatewayLiveTest` called the live API and asserted only that rows came back and that
  `logger` matched the requested scope; it never looked at `level`, the one field the engine
  actually branches on. A connector test that asserts "we got rows" while ignoring the field
  the caller depends on will pass through any field-name drift. Same family as FND-84's
  mock-only blind spot, one layer up: there the request was never asserted, here the response
  was never asserted.

---

## FND-87 — Spring built the all-mock `ConnectorModeProvider`, so the banner said `mock` on a fully-real run · **HIGH**

**Where**: `src/main/java/com/company/triage/config/ConnectorModeProvider.java` — two public
constructors, neither annotated.

**What**: the class has an `Environment`-reading constructor and a no-arg convenience
constructor for unit tests that hardcodes all four connectors to `"mock"`. Spring's
constructor-resolution rule is that a component with several constructors and **no**
`@Autowired` marker falls back to the **no-arg** one. Spring therefore built the all-mock
instance on every run, and the constructor that reads config was dead code from the moment
the no-arg one was added.

**Why it matters**: the startup banner's `connectors:` line and the UI's LT7 provenance chips
both read from here. Verified against the live estate 2026-08-06, running
`SPRING_PROFILES_ACTIVE=real`:

| source | said |
|---|---|
| Spring condition report | `RealConfluenceGateway matched`, `MockConfluenceGateway did not match` |
| trace | `confluence.search(query="Delivery Hazards") → 5 page(s), 5 cleared the relevance floor` |
| Sumo trace | `→ 20 line(s); errorToken=DataIntegrityViolationException` |
| **banner + API** | **`connectors: servicenow=mock, confluence=mock, sumo=mock, gitlab=mock`** |

Every connector was live and calling real systems while the app announced mocks. This is
FND-74's own failure mode — "the UI asserting a run used mocks while it was writing to a real
ticket" — reopened one layer up: FND-74 fixed the VALUE this provider stores, and nothing
checked that Spring ever called the constructor that reads one. In the dangerous direction it
means a presenter reading "mock" while ServiceNow writeback posts to a live, customer-visible
ticket.

It also cost an investigation: the banner is the app's own answer to "what am I running?", so
a session trying to enable real connectors trusted it, concluded profile activation was
broken, and went looking for a config-precedence bug that never existed.

**Fix**: `@Autowired` on the `Environment` constructor, so the container's choice is explicit
rather than incidental.

- **Resolution**: fixed (2026-08-06). New `ConnectorModeProviderWiringTest` asserts the
  container's behaviour via `ApplicationContextRunner`; verified it FAILS (2 of 3) with the
  annotation removed, then passes with it restored.
- **Escape**: test-mechanism — `ConnectorModeProviderTest` calls `new ConnectorModeProvider()`
  and `DiagnosisOrchestratorConnectorModesTest` calls `new ConnectorModeProvider(env)`. Between
  them every LINE of the class was covered, and both stayed green for the whole life of the
  bug, because the defect was not in either constructor but in **which one Spring chose**. Line
  coverage cannot see constructor selection; only a container test can. This is exactly
  J20/STV-5's "mechanism tests, not annotation tests" — the same principle, one level lower:
  never assert on your own `new` when the question is what the framework does.

---
