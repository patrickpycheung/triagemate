# Incident Triage Copilot

A local **Spring Boot + Google ADK** app that gives every new ServiceNow incident a
**first-pass diagnosis automatically** — turning a blank ticket into a head start.

When a ticket comes in, the copilot reads it, gathers evidence across the systems your
teams already use (ServiceNow, Confluence, Sumo Logic, GitLab), and **posts two
advisory comments back to the ticket**: the **sources** it consulted (with links),
then its **first-pass diagnosis** (likely system, likely team, evidence, next check).

It is **advisory only** — it comments, it never reassigns, closes, or re-prioritises.
The assigned engineer still decides everything.

> Hackathon 2026 · IT. This is a proof-of-concept run locally for the presentation —
> not deployed. See [`PIVOT.md`](PIVOT.md) for how we got here (the earlier Forge/Rovo
> prototype is archived under [`docs/archive/`](docs/archive/README-forge-rovo.md)).

---

## Prerequisites

- **JDK 21** (a full JDK with a compiler, not just a JRE) and **Maven 3.9+**.
  Verify: `java -version` → 21, `mvn -version` → 3.9 on Java 21.
- No network, API keys, or external systems needed for the demo — it runs fully
  offline in the `mock` profile.

## How to start it

```bash
cd app
mvn spring-boot:run
```

Wait for `Started TriageApplication in ~1.3 seconds`, then open
**http://localhost:8080**. Stop with `Ctrl+C`.

## How to use it

**From the UI** (recommended for the demo)
1. Open http://localhost:8080 — the incident number **`INC0012345`** is pre-filled.
2. Click **Diagnose**.
3. You'll see the full diagnosis and, under *"Posted to ServiceNow — automatically,"*
   the two advisory comments it writes back (sources first, then the diagnosis).

**From the API** (same thing, headless)
```bash
curl -X POST http://localhost:8080/api/diagnose/INC0012345 | jq
```

**What happens on each run** — one bounded pass: read the ticket → clarify the real
symptom → gather evidence (similar past incidents + ownership, a Confluence runbook, a
narrow Sumo Logic window, the GitLab line that emits the error) → **auto-post the two
advisory comments**. In the `mock` profile the comments are written to the app log and
shown in the UI; nothing external is touched.

> **Full step-by-step run + presentation script:** [`app/README.md`](app/README.md).
> **Screenshot walkthrough:** [`docs/design-java/DEMO.md`](docs/design-java/DEMO.md).

## Verify it works

```bash
cd app
mvn test          # offline demo path            → 3 tests pass
mvn -Padk test    # + live ADK agent loop        → 5 tests pass
```

## Live agent mode (optional)

By default a deterministic engine runs the flow offline. To use a real LLM-driven
**Google ADK** agent over the same tools:

```bash
export LLM_BASE_URL=https://llm.internal/v1  LLM_API_KEY=***  LLM_MODEL=gpt-4o-mini
cd app && mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk
```

## Repository layout

```
app/                     Spring Boot application (the build)
  src/main/java/...       api · orchestration · gateway (mock + real) · model
  src/main/adk/...         ADK LlmAgent engine (profile: adk)
  src/main/resources/...   application config + demo UI
  README.md                full run + presentation runbook
docs/
  design-java/             active design (concepts J1–J8) · DEMO.md · screenshots
  discovery/               DDS problem exploration + decisions
  archive/                 retired Forge/Rovo prototype (reference only)
PIVOT.md                   why this is Spring Boot + ADK, not Rovo
scripts/                   helper scripts (e.g. Playwright screenshots)
```

## Design & concept

- **Pitch deck** and **workflow diagram** are published as artifacts (private; share
  from the artifact page) — links in [`app/README.md`](app/README.md).
- **Design**: [`docs/design-java/`](docs/design-java/) (concepts J1–J8, `STATUS.md`).
- **Discovery / decisions**: [`docs/discovery/servicenow-triage-java/`](docs/discovery/servicenow-triage-java/).

## Repositories

- **Primary**: Home Git (Forgejo) `eugene/hackathon2026` — the `origin` remote.
- **Mirror**: GitLab `eugene.novikov/hackathon2026` (private) — kept in sync
  automatically on every push via a server-side push mirror.
