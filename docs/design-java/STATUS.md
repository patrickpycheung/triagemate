# STATUS — CDS: Java Triage Copilot (Spring Boot + ADK)

**Phase**: All ten concepts (J1–J10) Built (FND-32: this line previously said "CDS
Round 1 — concepts J1–J8 drafted", stale against the table below for some time).
**Source**: `docs/discovery/servicenow-triage-java/4-decide/concepts-extracted.md`
plus `docs/discovery/servicenow-local-trigger/` (J10) and `docs/discovery/
copilot-cli-runtime/` (the E2 LLM-backend decision).
**Rigor**: Hackathon/RAPID — optimize demo-wow + build ease.
**Supersedes**: `docs/design/` (Rovo-native, ⏸️ suspended).

## Concept table

Implementation is at the **repo root** (`pom.xml`, `src/`; FND-11 — an earlier `app/`
subdirectory was flattened away in `23778f4`), Maven, Java 21, Spring Boot 3.4.3.
- `mvn test` → **33/33 pass** (default profile); `./run-deterministic.sh` boots in
  ~1s and `POST /api/diagnose/INC0012345` returns the full result end-to-end (offline).
- `mvn -Padk test` → **46/46 pass**: the real ADK 1.7.0 `LlmAgent` loop runs
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
| J1 | spring-boot-orchestrator | Moderate | 🟢 Built | J3, J4 |
| J2 | adk-agent-loop | Critical | 🟢 Built + live loop proven vs ADK 1.7.0 (fake endpoint); bounds enforced | J1, J3 |
| J3 | connector-tools | Moderate | 🟢 Built (interfaces + mocks + Real* stubs) | J4 |
| J4 | diagnosis-report | Simple | 🟢 Built | — |
| J5 | servicenow-gateway | Moderate | 🟢 Built (mock + Real REST incl. work-note write) | J3, J4 |
| J6 | knowledge-tools | Highway | 🟢 Built (mock + Real Confluence/Sumo/GitLab) | J3 |
| J7 | demo-ui-and-dataset | Moderate | 🟢 Built (UI + ground-truth dataset) | J4 |
| J8 | guardrails-observability | Simple | 🟢 Built (allowlist, advisory-only, trace) | all |
| J9 | contact-suggestion | Simple | 🟢 Built (wiki authors + recent committers, merged; display-only) | J4, J6 |
| J10 | incident-poller | Moderate | 🟢 Built, offline-verified (K1 outbound polling; OFF by default; no-duplicate + no-skip tested). Not yet run against a real instance | J1, J5 |

## Spikes

- **JS-1 (dependency/API)** ✅ DONE (DDS, 2026-07-23): ADK-Java **GA 1.7.0** +
  `google-adk-langchain4j` + `google-adk-spring-ai` + `langchain4j-open-ai:1.0.0`
  all resolve on Maven Central; Java 21 present. D1 → 🟢 Low Risk.
  (`docs/discovery/servicenow-triage-java/2-diverge/verification-js1/findings.md`.)
- **JS-1b (live round-trip)** — build day 1: `LlmAgent` + one `FunctionTool` +
  `google-adk-langchain4j` → **live** enterprise endpoint → parsed JSON. Fallback:
  swap model backend to `google-adk-spring-ai`. Pin 1.7.0 signatures.
- **JS-2 (connectivity)** — build day 1: one read-only call per system + one
  controlled ServiceNow work-note write to a **test** incident. Mocks until real
  access lands.

## Next round triggers
Round 2 when JS-1/JS-2 return (confirm engine + connectivity), or when a real
connector replaces a mock and its interface shifts.
