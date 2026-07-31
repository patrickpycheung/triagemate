# Found-issues archive

Append-only record of resolved `FND-*` entries from `/FOUND-ISSUES.md`, newest first.

Each entry keeps its original text plus two lines added at resolution time:

- **Resolution** — `fixed:<sha>` (fixed directly), `promoted:<card path>` (now tracked
  by a design card), or `accepted:<card path>` (evaluated and deliberately not built —
  low probability/impact for this app's actual scope; documented as an accepted
  limitation in the owning card rather than left as an open backlog item).
- **Escape** — which **process layer** should have caught it. Not what was wrong in the
  code: what was wrong in how we work. A future retrospective clusters on this field, so
  it is written while the context is fresh.

Drained 2026-07-30 by `/found-issues-resolve`. Re-run 2026-07-31.

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
Evidence: `bin/spike-output.log`, trace line 1.

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
