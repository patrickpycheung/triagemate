# Incident Triage Copilot — Spring Boot POC

A local Spring Boot app that runs a **bounded, evidence-gathering** diagnosis of a
ServiceNow incident and **automatically posts two advisory comments back to the
ticket** — sources first, then a first-pass diagnosis. Advisory only: it comments,
it never reassigns, closes, or re-prioritises. Implements CDS concepts J1–J8
(`../docs/design-java/`). Runs fully offline for the demo; a live ADK agent drops in
behind one profile.

---

## Prerequisites

- **JDK 21** — a full JDK with a compiler, *not* just a headless JRE.
- **Maven 3.9+**.

Both are already installed for this machine (under `~/.local`, wired into `~/.bashrc`),
so a **new terminal** has `java`, `javac`, and `mvn` on the `PATH`. Verify:

```bash
java -version     # → 21.x  (Temurin)
mvn -version      # → 3.9.x, Java 21
```

If they're not found (e.g. an old shell), load them for this session:

```bash
export JAVA_HOME="$HOME/.local/jvm/temurin-21"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/apache-maven-3.9.9/bin:$PATH"
```

---

## 1. Start the application

```bash
cd ~/work/hackathon2026/app
mvn spring-boot:run
```

Wait for this line (~1–2 seconds):

```
Started TriageApplication in 1.3 seconds (process running for 1.6)
```

The app now serves on **http://localhost:8080** in the offline `mock` profile — no
network, no LLM, no external systems. Leave this terminal running.

> First run downloads dependencies; later runs are instant.

---

## 2. Trigger the process

**A · From the UI (recommended for the demo)**

1. Open **http://localhost:8080** in a browser.
2. The incident number **`INC0012345`** is pre-filled.
3. Click **Diagnose**.

**B · From the API (same thing, headless)**

```bash
curl -X POST http://localhost:8080/api/diagnose/INC0012345 | jq
```

**What happens on a trigger** — the copilot, in one bounded pass:
1. reads the ticket from ServiceNow,
2. clarifies the real symptom,
3. gathers evidence (similar past incidents + ownership, a Confluence runbook, a
   narrow Sumo Logic window, the GitLab line that emits the error),
4. **automatically posts two advisory comments** back to the ticket — **Sources
   consulted** first (clickable links), then the **First-pass diagnosis**.

In the `mock` profile the two comments are written to the app log (nothing external is
touched); the UI shows exactly what they contain.

---

## 3. Run the presentation — step by step

**Part A — the concept (slides).** Open the published pitch deck and walk slides 1→8
(problem → idea → 4-step overview → the two-comment output → systems → guardrails →
payoff):

- Pitch deck: `https://claude.ai/code/artifact/6772a5e5-838c-4310-be1f-c880aab8dd89`
- Workflow diagram: `https://claude.ai/code/artifact/80b87ae8-6a41-45c3-b406-901d883993d0`

*(Both are private — use the page's Share menu to send them to the room.)*

**Part B — the live demo.** Then show the real app doing it:

1. **Start it** — `cd app && mvn spring-boot:run`, wait for *Started TriageApplication*.
2. **Open** http://localhost:8080. Say: *"A support ticket just came in — `INC0012345`,
   'orders sometimes don't go through at checkout'. Vague. Normally a human starts from
   a blank page."*
3. **Click Diagnose.** It returns in well under a second.
4. **Walk the result, top to bottom:**
   - *Diagnosis* — the vague ticket is now a clear symptom.
   - *Candidate systems* — ranked with confidence (Payment Service ~86%).
   - *Suggested assignment* — Payments Platform Support.
   - *Evidence* — from all four systems, including the **log↔code citation**
     (`payment_service.py:44` — the exact line that emits the error).
5. **Land the payoff — "Posted to ServiceNow — automatically":** the **two comments**.
   Say: *"Sources first — every link is the exact material it used — then its view.
   And it only comments. It never reassigns or closes anything. The engineer still
   decides — they just don't start from zero."*
6. **Point at the trace** — proof it really consulted each source, within its limits.

**Part C — stop.** `Ctrl+C` in the app terminal.

> A screenshot walkthrough (in case the live demo can't run) is in
> **[../docs/design-java/DEMO.md](../docs/design-java/DEMO.md)**.

---

## Verify it works (tests)

```bash
mvn test          # offline demo path → 3 tests pass
mvn -Padk test    # + live ADK agent loop (against a fake endpoint) → 5 tests pass
```

## The two engines (same tools, same output — J2)

| Engine | How | When |
|---|---|---|
| **deterministic** (default) | scripted phase flow, no LLM | offline demo, always works |
| **adk** | live ADK `LlmAgent` over the tools | `mvn -Padk spring-boot:run` + `triage.engine=adk` + `LLM_*` env |

To drive a real enterprise OpenAI-compatible model:

```bash
export LLM_BASE_URL=https://llm.internal/v1   LLM_API_KEY=***   LLM_MODEL=gpt-4o-mini
mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk
```
The ADK path is **Spike JS-1b** — see `src/main/adk/java/com/company/triage/agent/README.md`.

## Project layout

```
src/main/java/com/company/triage
├── api/           DiagnosisController                              (J1)
├── orchestration/ DiagnosisOrchestrator, DiagnosisEngine,
│                  DeterministicDiagnosisEngine, DiagnosisResult    (J1/J2)
├── gateway/       *Gateway interfaces + mock/* + real/*            (J3/J5/J6)
└── model/         DiagnosisReport (+ toSourcesNote/toDiagnosisNote) & records (J4)
src/main/adk/…     ADK LlmAgent engine, tools, callbacks           (J2, profile: adk)
src/main/resources/static/index.html   demo UI                     (J7)
```

## Guardrails (J8)

Advisory only — no reassign/close/priority/remediation. Write-back is **automatic**
(no human in the loop) but limited to **two labelled advisory comments** — *sources
first*, then the *first-pass diagnosis* (`triage.writeback.enabled`, default true;
safe in `mock`, which just logs). Sumo scopes are allowlisted; all fetched content is
treated as untrusted data. Every run emits a redacted tool-call trace.

## Connectors: mock or real (independently)

Each system has a `Mock*Gateway` and a `Real*Gateway`, switched **per connector**:

```
triage.connectors.servicenow=mock|real
triage.connectors.confluence=mock|real
triage.connectors.sumo=mock|real
triage.connectors.gitlab=mock|real     # default: all mock
```

Mix freely — the useful demo combo is **real ServiceNow + mock evidence**.

## ⭐ Live demo: write the comments to a REAL ServiceNow ticket

Instead of only showing the result in our UI, post the two advisory comments onto a
real ticket in your **ServiceNow dev instance** and switch to ServiceNow to show it
updating live. Run this **on a machine that can reach the dev instance** (e.g. the
corporate-network laptop).

**1. One-time: a service account + a test ticket.**
- A ServiceNow user/service account with **read + write on `incident`** (e.g. the
  `itil` role, or a scoped REST role). Basic-auth credentials.
- Create a test incident. Its number is what you'll type in the demo. *(The diagnosis
  content is the scripted payment-reconcile story regardless of the ticket's text, so
  any incident works; a ticket worded like "orders don't go through at checkout" just
  makes the read look coherent.)*

**2. Run with real ServiceNow (evidence stays mock):**

```bash
export SNOW_BASE_URL=https://devNNNNN.service-now.com
export SNOW_USER=<service-account>   SNOW_PASSWORD=<password>
cd app
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=snow-live
```

**3. Trigger it** — open http://localhost:8080, type your **real incident number**,
click **Diagnose** (or `curl -X POST http://localhost:8080/api/diagnose/<INC>`).

**4. Show it in ServiceNow** — open that incident; the two advisory entries appear in
the **Work notes / Activity** stream: *Sources consulted*, then *First-pass diagnosis*.

By default the comments go to **`work_notes`** (internal, fulfiller-visible). To post
customer-facing **Additional comments** instead:
`--triage.servicenow.write-field=comments`.

> **Everything real** (all four systems) = `--spring.profiles.active=real` with every
> credential set (see `application.yml` `triage.integrations.*`). ServiceNow-only is
> the recommended demo.

## Auto-trigger on ticket creation — deferred

Firing automatically when a qualifying incident is created is designed but **out of
scope for the demo** (a cloud dev instance can't reach a corp-network laptop without a
tunnel/MID Server, which aren't available). The demo uses the **manual trigger** above;
the investigation and the production path (Flow Designer + MID Server) are in
[`../docs/discovery/servicenow-auto-trigger/`](../docs/discovery/servicenow-auto-trigger/).
