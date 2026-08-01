# STATUS — CDS: Java Triage Copilot (Spring Boot + ADK)

**Phase**: J1–J10 Built; **J11 (live-thinking-trace) STUCK at CDS Round 4** (blocked on 2 external spikes, not a design question) — graduated 2026-07-31 from DDS `live-thinking-trace-ui`. Previously: all ten concepts (J1–J10) Built (FND-32: this line previously said "CDS
Round 1 — concepts J1–J8 drafted", stale against the table below for some time).
**Source**: `docs/discovery/servicenow-triage-java/4-decide/concepts-extracted.md`
plus `docs/discovery/servicenow-local-trigger/` (J10) and `docs/discovery/
copilot-cli-runtime/` (the E2 LLM-backend decision).
**Rigor**: Hackathon/RAPID — optimize demo-wow + build ease.
**Supersedes**: `docs/design/` (Rovo-native, ⏸️ suspended).

## Concept table

Implementation is at the **repo root** (`pom.xml`, `src/`; FND-11 — an earlier `app/`
subdirectory was flattened away in `23778f4`), Maven, Java 21, Spring Boot 3.4.3.
- `mvn test` → **46/46 pass** (default profile); `./run-deterministic.sh` boots in
  ~1s and `POST /api/diagnose/INC0010005` returns the full result end-to-end (offline).
- `mvn -Padk test` → **62/62 pass**: the real ADK 1.7.0 `LlmAgent` loop runs
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
| J11 | live-thinking-trace | Complex | 🟡 **Stable, design-only** (CDS R5–R9, 2026-08-02) — LT4 latency spike returned and was folded in (LT4 grew from 3 tool edges to **6**, `javap`-verified). LT5 projector risk **closed by operator**: presentation is a big screen off the corp laptop, not a projected image, so the photon-loss caveat doesn't apply. **Zero open design items**, one quiet round from 🟢. **Not yet implemented** — no code exists; needs `cds-implementation-planner` if/when scheduled | J1, J2, J4, J7, J8 |

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
