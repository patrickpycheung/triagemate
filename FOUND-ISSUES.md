# Found issues

**Backlog: 20 open (FND-9…FND-32, minus FND-14/15/16/31 — resolved)** — raised by
`/doc-test cds` on 2026-07-30.

Queue of findings that need a decision or a fix and are not yet tracked elsewhere.
Resolved entries move to [`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md)
with two added lines: a **Resolution** (`fixed:<sha>` or `promoted:<card>`) and an
**Escape** (which *process layer* should have caught it — the input a future retrospective
clusters on).

Format: `## FND-<n> — <one-line title> · **HIGH|MEDIUM|LOW**`, then **Where** / **What** /
**Why it matters**. Add new entries at the bottom.

## When to log here vs. fix directly

- **Log it** when resolving it means a design decision, a behaviour change, or a data /
  API contract change — anything where picking the fix is itself the hard part.
- **Fix it directly** (no entry) when it is a genuinely small correction with an easy undo
  and no money / legal / data / governance / deploy surface. Don't force ceremony onto
  trivia.

---

## About this batch (FND-9…FND-32)

Raised by `/doc-test cds` (Phase 2 ×2 agents + conflict triple: Codex, Gemini, Claude).
**40 Phase-2 gaps + 27 raw conflicts, deduplicated to 24 entries** — the sources overlapped
heavily, so entries are clustered by cause rather than listed per-symptom. Each carries a
`Found by:` line; agreement across independent tools is the confidence signal.

Five findings were **fixed immediately** rather than logged, because each was a doc
asserting something *false* about safety or a contract: the J8 tool allowlist (documented
but not implemented), J4's contact field names + enum casing, the silent `gpt-4o-mini`
default, and four staleness bugs in the workspace README. See commit `b8b2dd0`.

**One systemic cause explains most of what remains**: the J-cards were written at design
time (J1–J8 "Drafted") and the implementation moved past them without the cards following.
That is a *process* observation, not 24 independent mistakes — worth one fix to how cards
are updated, not 24 patches.

Three entries were flagged as more than drift and action-first, and **all three are now
resolved** (see the archive): **FND-14** (real-connector idempotency), **FND-15** (no
timeout, no tool bound), and **FND-31**, found alongside them during the fix (the manual
endpoint bypassing the poller's dedupe state) — all fixed together since they shared the
same rendezvous point, `DiagnosisOrchestrator`. **FND-16** (mine, from the same FND-8
session — the degraded-run UI banner matching a trace string instead of the `engine`
field that exists for exactly this) is resolved too.

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

---

## FND-11 — Docs locate the app in `app/`, which does not exist · **MEDIUM**

**Where**: `docs/design-java/STATUS.md:10` ("Implementation in `app/`"),
`J5/README.md:43` ("Runbook: `app/README.md`").

**What**: there is no `app/` directory. `pom.xml` and `src/` are at the repo root (moved in
`23778f4`, "flatten Java app to repo root"); the runbook is
`docs/design-java/DEMO-RUNBOOK.md`.

**Found by**: Phase 2 (both agents), Claude conflict.

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

---

## FND-18 — J8's trace spec doesn't match what is traced · **MEDIUM**

**Where**: `J8/README.md:31-33` vs `DiagnosisOrchestrator.java:60` and the engines' traces.

**What**: three mismatches. J8 says the per-run trace records "human accept/reject" (a field
of the confirm gate removed 2026-07-23); says a "structured JSON log per run" while the code
emits one plain-text line; and claims query params (sans secrets) and the model id are
captured — the trace records tool names only.

**Found by**: Phase 2 (agent B), Claude conflict.

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

---

## FND-22 — J1's declared dependencies are wrong and imply a cycle · **MEDIUM**

**Where**: `J1/README.md:3` (`Depends on: J3, J4`) vs its own Design section (uses J2, J5,
J8) and `J2/README.md:3` (`Depends on: J1`).

**What**: J1 omits J2/J5/J8; J2 declares J1 → J1↔J2 reads as circular.

**Why it matters**: resolvable by naming the direction properly — J1 owns the
`DiagnosisEngine` interface, J2 implements it — but as written the graph is unusable for
dependency-ordered planning.

**Found by**: Codex conflict.

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

---

## FND-26 — `seed-repo/` path is wrong · **LOW**

**Where**: `J6/README.md:35`, `J7/README.md:28`.

**What**: cited as repo-root `seed-repo/`; it lives at
`docs/design/concepts/log-code-reasoning/verification-s3/seed-repo/` (moved into `rovo/` and
the suspended CDS during the pivot).

**Found by**: Phase 2 (agent B).

---

## FND-27 — `triage.agent.max-tool-calls` is undeclared and unnamed · **LOW**

**Where**: `AdkDiagnosisEngine.java:95` (`@Value("${triage.agent.max-tool-calls:10}")`) vs
`application.yml` and J1/J2/J8.

**What**: the key that bounds the agent's tool budget appears in no config file and in no
card — it exists only as an inline default. Every other `triage.*` key is declared in
`application.yml` with a comment.

**Found by**: Phase 2 (agent A).

---

## FND-28 — J2 carries a stale ADK-version risk line · **LOW**

**Where**: `J2/README.md:95` ("ADK v0.8.0 API drift → pin the version") vs `J2:72-77`
("ADK-Java is GA 1.x — **not** the 0.8.0 the older docs show") and `pom.xml` (`1.7.0`).

**What**: the risk section contradicts the same card's own version note.

**Found by**: Phase 2 (agent A).

---

## FND-29 — J3 documents `search(String cql)`; the code takes a plain query · **LOW**

**Where**: `J3/README.md:19` vs `ConfluenceGateway` and the `search_confluence` ADK schema
(`query`), and `MockConfluenceGateway` (keyword matching, not CQL).

**Found by**: Phase 2 (agent A).

---

## FND-30 — J10's test count is wrong · **LOW**

**Where**: `J10/README.md:96` ("10 unit tests in `IncidentPollerTest`").

**What**: there were 9 at the time of writing. A hand-maintained count in a doc drifts by
construction — better to state what is covered than how many.

**Found by**: Phase 2 (agent B).

---

## FND-32 — `docs/design-java/STATUS.md` is stale in its own header and counts · **LOW**

**Where**: `STATUS.md:3` ("Phase: CDS Round 1 — concepts J1–J8 drafted") vs its table
showing J1–J10 all Built; `STATUS.md:21` ("Tests now 3/3 default and 5/5 `-Padk`") vs actual
16 default / 24 `-Padk`.

**Found by**: Phase 2 (both agents).

---

## Previously resolved (FND-1…FND-8)

Archived in [`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md) — the
first eight entries came out of `/doc-test dds` on 2026-07-29 plus the work that followed,
and were all resolved on 2026-07-30. Two were worth the trip on their own:

- **FND-1** — the trigger the DDS specified would have re-diagnosed every incident it
  commented on, forever, because the app's own work notes bumped the cursor it polled on.
- **FND-8** — the FND-7 fallback was correct but silent, so a run that never called the
  model looked exactly like a successful one. It cost a spike cycle before anyone noticed.
