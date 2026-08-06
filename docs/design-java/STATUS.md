# STATUS — CDS: Java Triage Copilot (Spring Boot + ADK)

**Phase**: J1–J11 Built; **J12–J23 designed, not built** (application review, 2026-08-05 —
see the review-round table below). All eleven original concepts are complete (J11
live-thinking-trace landed 2026-08-02 via STREAM-001–006, verification sweep TASK-017
confirmed both profiles green and the additive-only transport guarantee holds). (FND-32:
this line previously said "CDS Round 1 — concepts J1–J8 drafted", stale against the table
below for some time).
**Source**: `docs/discovery/servicenow-triage-java/4-decide/concepts-extracted.md`
plus `docs/discovery/servicenow-local-trigger/` (J10) and `docs/discovery/
copilot-cli-runtime/` (the E2 LLM-backend decision).
**Rigor**: Hackathon/RAPID — optimize demo-wow + build ease.
**Supersedes**: `docs/design/` (Rovo-native, ⏸️ suspended).

## Concept table

Implementation is at the **repo root** (`pom.xml`, `src/`; FND-11 — an earlier `app/`
subdirectory was flattened away in `23778f4`), Maven, Java 21, Spring Boot 3.4.3.
- `mvn test` → **140/140 pass** (default profile); `./run-deterministic.sh` boots in
  ~1s and `POST /api/diagnose/INC0010005` returns the full result end-to-end (offline).
- `mvn -Padk test` → **183/183 pass**: the real ADK 1.7.0 `LlmAgent` loop runs
  end-to-end against a local fake OpenAI endpoint (tool-call → tool exec → J4 parse),
  the J8 tool allowlist + call bound are proven, and the orchestrator's timeout +
  concurrency-coalescing (FND-15/31) are covered. (FND-30: hand-maintained test
  counts drift by construction — treat these as "as of the last full run", not a
  promise; `mvn test` / `mvn -Padk test` are the source of truth.)
- Real\*Gateway connectors (JS-2) compile in both profiles and are selected per
  connector via `@ConditionalOnProperty(triage.connectors.<system>)`, **not**
  `@Profile` (FND-10).
- **2026-07-23 simplification**: human-confirm gate removed → **automatic two-comment
  write-back** (sources, then advisory diagnosis); multi-agent/loops explored & deferred
  (`../discovery/servicenow-triage-java/3-synthesize/dead-ends.md`). Walkthrough →
  **[DEMO.md](DEMO.md)** (screenshots deferred until the app is finalized). Pitch
  deck + workflow diagram published as artifacts.

| ID | Concept | Complexity | State | Depends on |
|----|---------|-----------|-------|------------|
| J1 | spring-boot-orchestrator | Moderate | 🟢 Built | J3, J4, J5, J8 |
| J2 | adk-agent-loop | Critical | 🟢 Built + live loop proven vs ADK 1.7.0 (fake endpoint); bounds enforced | J1, J3, J4 |
| J3 | connector-tools | Moderate | 🟢 Built (interfaces + mocks + Real* stubs) | J4 |
| J4 | diagnosis-report | Simple | 🟢 Built | — |
| J5 | servicenow-gateway | Moderate | 🟢 Built (mock + Real REST incl. work-note write) | J3, J4 |
| J6 | knowledge-tools | Highway | 🟢 Built (mock + Real Confluence/Sumo/GitLab) | J3 |
| J7 | demo-ui-and-dataset | Moderate | 🟢 Built (UI + ground-truth dataset) | J4 |
| J8 | guardrails-observability | Simple | 🟢 Built (allowlist, advisory-only, trace) | all |
| J9 | contact-suggestion | Simple | 🟢 Built (wiki authors + recent committers, merged; display-only) | J4, J6 |
| J10 | incident-poller | Moderate | 🟢 Built, offline-verified (K1 outbound polling; OFF by default; no-duplicate + no-skip tested). Not yet run against a real instance | J1, J5 |
| J11 | live-thinking-trace | Complex | 🟢 Built (STREAM-001–006, 2026-08-02) — LT1 SPI migration, LT2 ToolRegistry/StepCatalog, LT4's 6 ADK callback edges, LT3/LT4/LT5/LT7 frontend renderers, and the additive-only `X-Triage-Run-Id` transport all landed; `mvn test` 140/140, `mvn -Padk test` 183/183. Verification sweep (TASK-017) confirmed the pre-J11 `DiagnosisResult` shape is unchanged for callers with no run-id header, and the deterministic replay path renders correctly end-to-end. Live ADK round-trip re-verified via the `adk-test` module + TASK-015's own Playwright-driven manual check — no Copilot proxy in this environment for a fresh live round trip | J1, J2, J4, J7, J8 |

### Review round — J12–J23 (2026-08-05, designed not built)

Whole-application review (8-dimension multi-agent sweep with adversarial verification of
every finding, cross-checked against independent Codex `gpt-5.6-sol` and Gemini
architecture reviews). 41 findings confirmed, 1 refuted, both test profiles green
throughout (152 default / 201 adk — the counts in the bullets above are stale by the same
FND-30 mechanism they warn about). The 28 findings needing a *design decision* became the
twelve cards below; the 13 whose remedy was already fully specified went to
`FOUND-ISSUES.md` as FND-70…82 instead of getting ceremony they don't need.

Nothing here is built. **The two cards to read first are J12 and J14** — J12 because the
live trace's central promise (rows resolving in place) does not currently reach the client
on the ADK path at all, and J14 because a null `openedAt` from a real ServiceNow ticket
makes the *fallback* engine throw, which is the one thing the safety net may never do.

| ID | Concept | Complexity | State | Depends on |
|----|---------|-----------|-------|------------|
| J12 | live-trace-delivery | Moderate | 🟢 **Built** (LTD-1/LTD-2; STATUS row corrected 2026-08-06 — verified in code: `RunStepsController` does full re-read + client upsert by `(attempt, callId)`). Was: 🔴 Designed, not built — HIGH. The `since` cursor delivers new *positions*, but J11 models rows as *mutable and keyed by `callId`*; every `ACTIVE → DONE` resolution is therefore dropped until the POST settles. Replaces it with identity-keyed convergent delivery and settles who owns `seq` | J7, J8, J11 (amends J11) |
| J13 | evidence-citation-integrity | Moderate | 🟡 **Partly built — ECI-6 landed 2026-08-06**, only ECI-5 (typed identifier fields) remains. (corrected 2026-08-05 — this row said "Designed, not built" while the card said "Partly built"; verified against code: `DiagnosisReportValidator:85` does reject duplicate evidence ids, but `AdkDiagnosisEngine:440` still validates *outside* `runAgentAndParse`, so **ECI-6's repair turn is genuinely unwired** and ECI-5 is not done). Makes J4's "every conclusion ties to evidence" enforceable: unique ids (multiple code hits currently all get `e-code`), candidates citing only evidence that names *their* system, and 0.86 code-citation confidence gated on system agreement | J4, J2, J3 (amends J4, J8) |
| J14 | fallback-real-input-robustness | Moderate | 🟡 **Partly built — FRI-3/FRI-4 landed 2026-08-06; FRI-5 (= J25/KQR-4) and FRI-6 remain** (STATUS row corrected 2026-08-06 — card says Partly built and code agrees: FRI-1/FRI-2 landed, `DeterministicDiagnosisEngine:252,283` no longer dereference `openedAt`). Remaining scope below was: 🔴 Designed, not built — HIGH. Extends FND-63 to real-connector input: null/display-format `openedAt`, `_sourceCategory`-shaped loggers, hyphenated subjects, per-call connector degradation. "Degraded" must mean a weaker report, never a 500 | J2, J3, J5 (amends J2, J5, J3) |
| J15 | port-contract-demo-runbook | Moderate | 🟢 **Built** (STATUS row corrected 2026-08-06 — verified: runbook T3 re-derived from the scripts, explicit `--server.port` pin honoured). Was: 🔴 Designed, not built — HIGH. The rehearsed on-stage fallback flip targets port 8081, which nothing has served since the 2026-08-04 port-80 change. Single-sources the port + proxy-endpoint contract and re-derives the runbook from it | J1, J2, J7 (amends J7) |
| J16 | run-trace-registry-lifecycle | Moderate | 🔴 Designed, not built. A live buffer belongs to the run in flight, not to whoever last sent a header for that incident — fixes waiter aliasing onto stale/poller-owned runs, and derives the TTL from `timeout-ms` | J1, J10, J11 (amends J11) |
| J17 | poller-completion-semantics | Moderate | 🔴 Designed, not built. K1 "completed" becomes diagnosed AND delivered AND not-already-done-by-another-trigger, with a no-LLM redelivery queue — today a failed writeback marks the incident done and its only external output is lost permanently | J1, J5, J10 (amends J10, J1) |
| J18 | guardrail-enforcement-completeness | Simple | 🔴 Designed, not built. A bound is owned by the boundary, not the caller: the GitLab allowlist FND-38 added to `search_code` is bypassed by `find_recent_committers`, and ServiceNow encoded-query values are unconstrained | J2, J5, J6, J8 (amends J8, J5) |
| J19 | instruction-config-fidelity | Simple | 🔴 Designed, not built. Every bound the prompt states derives from the enforced `TriageProperties` value — no second hardcoded `prod`, budget disclosed up front, exhaustion phrased globally (completes FND-60's discipline) | J2, J8 (amends J8, J2) |
| J20 | startup-truth-and-validation | Moderate | 🔴 Designed, not built. Boot validates what the run will need (LLM config, nullable allowlists) and the banner reports the *effective* engine, not configured intent — the FND-49/FND-36 class, re-opened | J1, J2, J8 |
| J21 | network-exposure-posture | Simple | 🔴 Designed, not built. The mutating endpoint is reachable from the whole LAN with no auth while writeback is on by default: loopback bind + a required non-safelisted header that forces an unanswered CORS preflight | J1, J5, J7, J11 (amends J1, J7) |
| J22 | real-gateway-contract-tests | Moderate | 🟢 **Built** (STATUS row corrected 2026-08-06 — verified: 10 real-gateway test files incl. Confluence + GitLab contract tests). Was: 🔴 Designed, not built. Confluence and GitLab are the only connectors whose real HTTP layer has zero tests — and GitLab double-encodes the project id (`%252F`), which would 404 every real-mode code search. One rule: caller-derived text is a URI variable, never spliced into the template | J3, J6 (amends J3, J6) |
| J23 | live-ui-honesty | Simple | 🔴 Designed, not built. J11's honesty contract made state-owned rather than renderer-owned: provenance chips during the live window, past-tense caption on a finished run, and a `DEGRADED_TO_DETERMINISTIC` branch so a run that spent 30s on the proxy stops calling itself "offline" | J7, J11 (amends J11) |
| J24 | servicenow-field-fidelity | Moderate | 🟢 **Built** (SFF-1…SFF-5; STATUS row corrected 2026-08-06 — verified in code: `RealServiceNowGateway.text()` unwraps `display_value`, 15 SFF markers across main). Was: 🔴 Designed, not built — HIGH. **Field-reported** (sajids4, `6c550ab`, live instance). Reference fields (`cmdb_ci`, `caller_id`) arrive as `{display_value, link}` objects and parse to `""`, so the affected system is derived from the subject line and the Sumo scope is built from a sentence fragment. **Corrects FND-67's premise** — the CMDB was never empty, the parse dropped it | J3, J5, J2 (amends J5, J2, J14) |
| J25 | knowledge-query-relevance | Moderate | 🟡 **Mostly built — KQR-2 landed 2026-08-06; only KQR-4 (= J14/FRI-5) remains** (STATUS row corrected 2026-08-06 — verified: `RealConfluenceGateway` uses `siteSearch` not `text ~` (KQR-1) and drops attachments/db objects (KQR-3), 10 KQR markers in main). Remaining scope below was: 🔴 Designed, not built. **Field-reported** (same commit). The Confluence query is a 12-term keyword bag; on the live instance it returned five unrelated pages (a Teradata data-model PDF among them) and all five were cited as evidence. Also makes a *failed* search distinguishable from an *empty* one | J6, J3, J24 (amends J6, J2) |
| J26 | similar-incident-ranking | Moderate | 🟢 Built (`worktree-hack-111`). **Field-reported** — "Find Similar Incidents always returns zero hits". Retrieval was `short_descriptionLIKE<first word>` with a hardcoded `0.5` similarity written onto every row; `cmdb_ci` was never used. Now retrieves wide on the keys that carry signal, then ranks locally (`SimilarIncidentRanker`: text Jaccard 0.6 + CI 0.3 + category 0.1), with `resolved-states`/`similarity-floor`/`max-similar` as config. **No card directory yet** — the ID is claimed in code | J3, J5 (amends J5) |
| J27 | adk-journal-filter | Simple | 🟢 Built (`14fa031`). FND-67 closure on the **agent** path: `isAiAuthoredNote` had two call sites, both deterministic-only, while `TriageMateTools.getIncident()` handed raw `comments`/`workNotes` to a model the instruction tells to read them. Filtered at the tool boundary, not in the prompt | J2, J4, J5 (amends J2) |
| J28 | precedent-grounded-cause-resolution | Moderate | 🟢 **Built — both engines** (deterministic 2026-08-05; ADK ungated 2026-08-06 with J13/ECI-6). Two new report components that **quote** what past incidents did rather than **assert** what is happening now. *It may cite a cause; it may not assert one* — no causal substrate exists. Abstention (`null`) is legal and is the **common** path; no percentages (the denominator is the hedge); the resolution section is built **entirely from closed vocabularies** (`ResolutionVerb` + ServiceNow `close_code`), so attacker-influenced free text can never reach it. Deterministic path is implementable today; ADK emission gated on J13/ECI-6, `KNOWN_ERROR_DOC` on J25, `CODE_PATH` on J13 | J4, J5, J7, J26, J27 (amends J4, J5, J7) |
| J29 | log-line-fidelity | Simple | 🟢 **Built + verified live** (`a31c961`, `9418b7b`, `5e188d3`, `8b8d3f4`). **Field-reported** (cheungp, `6cc2f1c`). `loglevel` is **absent** on every real Sumo row, so every log arrived at level `""`: the ERROR filter matched nothing, both GitLab steps skipped, and every log-derived candidate silently sat in the 0.45 tier instead of 0.70. The token regex separately picked `GNAF_FRONTAGE` (a SQL data value) over `DataIntegrityViolationException`. Level now recovers from `_raw` (`\s+`, not the field report's single space — that misses WARN/INFO), unreadable levels are disclosed rather than scored as a severity, and a thrown FQN outranks the snake-case token. Live: 20 rows → {ERROR=14, WARN=6}. **GitLab-side end-to-end unverified** (API unreachable from the dev box) | J6, J3, J22 (amends J6, J13) |

## Spikes

- **JS-1 (dependency/API)** ✅ DONE (DDS, 2026-07-23): ADK-Java **GA 1.7.0** +
  `google-adk-langchain4j` + `google-adk-spring-ai` + `langchain4j-open-ai:1.0.0`
  all resolve on Maven Central; Java 21 present. D1 → 🟢 Low Risk.
  (`docs/discovery/servicenow-triage-java/2-diverge/verification-js1/findings.md`.)
- **JS-1b (live round-trip)** — build day 1: `LlmAgent` + one `FunctionTool` +
  `google-adk-langchain4j` → **live** enterprise endpoint → parsed JSON. Fallback:
  swap model backend to `google-adk-spring-ai`. Pin 1.7.0 signatures.
- **LT1-SPI (J11 SPI feasibility)** ✅ DONE (2026-07-31): flipping `DiagnosisEngine` to
  `diagnose(String, TraceSink)` as the abstract method **compiled and kept the suite green**
  (34/34 + 50/50 at spike time — see the top-of-file line above for the current count),
  zero assertion changes. Blast radius measured **by the compiler** at **18 sites /
  3 files** — the DDS's grep-derived "16 / 2 files" missed `IncidentPollerTest` entirely.
  Source reverted (sink was stubbed). Lesson: enumerate a SAM change with the compiler, and
  always `clean` — a warm `test-compile` reported 0 errors from stale classes.
  (`concepts/J11-live-thinking-trace/verification-lt1-spi/findings.md`.)
- **JS-2 (connectivity)** — build day 1: one read-only call per system + one
  controlled ServiceNow work-note write to a **test** incident. Mocks until real
  access lands.

## Next round triggers
- **J1–J10**: when JS-2 returns (connectivity), or when a real connector replaces a mock
  and its interface shifts.
- **J11**: ~~STUCK at Round 4~~ → **🟡 Stable after Rounds 5–8 (2026-08-01)**. Historical
  note follows; the current state is the row above. At Round 4 every design question reachable
  through analysis was decided, spiked, or fixed (Round 3's `/doc-test cds` found 5 real design
  holes across 9 perspectives; all fixed and re-verified; Round 4 found only a one-line
  doc-freshness drift, no design change). Remaining blockers are **empirical, not design**:
  real ADK per-step latency (needs the corp laptop + Copilot proxy running) and projector
  legibility of the glow-pulse badge (needs an actual projector). No further CDS round can
  produce signal on either — they need the operator to run the physical spike, not more
  analysis.
  **Disposition (2026-07-31): operator will run both spikes and report back.**
  **UPDATE 2026-08-01 — the LT4 latency spike has returned.** Recorded in
  `concepts/J11-live-thinking-trace/verification-lt4-latency/findings.md`: the first
  end-to-end agentic run against a real Copilot-served model (real ServiceNow + Confluence)
  took **37s for 3 tool calls** (mean over 3 runs) — **8.0s ± 2.6s per tool call**, **13.1s**
  for the final report, with byte-identical output every time. That answers J11's core question decisively (**LT4 live streaming is required**: a
  3-call run is already 40s of blank screen), settles the reveal-cadence fork, and showed
  `timeout-ms: 90000` was *shorter than a run its own 10-call tool budget permits*
  (92.7s ± 8.2) — corrected to 120s as FND-69. Getting there also required fixing FND-66/67/68, which only
  surfaced against real data. **J11 is now unblocked for CDS Round 5**; the sole remaining
  external blocker is `verification-lt5-projector/` (badge legibility at distance).
