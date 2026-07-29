# TriageMate

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
mvn spring-boot:run
```

Wait for `Started TriageMateApplication in ~1.3 seconds`, then open
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

> **Screenshot walkthrough:** [`docs/design-java/DEMO.md`](docs/design-java/DEMO.md).

## Post the comments to a real ServiceNow ticket (optional)

Instead of only showing the result in the UI, write the two advisory comments onto a
real incident in your **ServiceNow dev instance** and switch to ServiceNow to show it
updating live. Only the ServiceNow connector goes live; the evidence stays mock.

Run this **where the dev instance is reachable** (e.g. the corporate-network laptop),
with a service account that has **read + write on `incident`**. Secrets are kept in a
gitignored `.env` file, not exported by hand — copy the template and fill it in:

```bash
cp .env.example .env   # fill in SNOW_BASE_URL / SNOW_USER / SNOW_PASSWORD

export $(grep -v '^#' .env | xargs)
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=snow-live
```

Don't have a ServiceNow service account yet? See
[`docs/integrations/SERVICENOW.md`](docs/integrations/SERVICENOW.md) for how to get
one. The other connectors (Confluence, Sumo Logic, GitLab) and the live LLM/ADK agent
mode each have their own setup guide under
[`docs/integrations/`](docs/integrations/README.md).

Then trigger it with a **real incident number** (UI or `curl`). The two entries —
*Sources consulted* then *First-pass diagnosis* — appear in that ticket's **Work notes /
Activity** stream. To post customer-facing *Additional comments* instead, add
`--triage.servicenow.write-field=comments`.

Connectors switch independently (`triage.connectors.{servicenow,confluence,sumo,gitlab}=mock|real`,
default mock). Auto-trigger on ticket creation is deferred — see
[`docs/discovery/servicenow-auto-trigger/`](docs/discovery/servicenow-auto-trigger/).

## Verify it works

```bash
mvn test          # offline demo path            → 3 tests pass
mvn -Padk test    # + live ADK agent loop        → 5 tests pass
```

## Live agent mode (optional)

By default a deterministic engine runs the flow offline. To use a real LLM-driven
**Google ADK** agent over the same tools:

```bash
cp .env.example .env   # fill in LLM_BASE_URL / LLM_API_KEY / LLM_MODEL

export $(grep -v '^#' .env | xargs)
mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk
```

## Repository layout

```
pom.xml                  Spring Boot build (run from repo root)
src/main/java/...        api · orchestration · gateway (mock + real) · model
src/main/adk/...         ADK LlmAgent engine (profile: adk)
src/main/resources/...   application config + demo UI
docs/
  design-java/             active design (concepts J1–J8) · DEMO.md · screenshots
  discovery/               DDS problem exploration + decisions
  archive/                 retired Forge/Rovo prototype (reference only)
PIVOT.md                   why this is Spring Boot + ADK, not Rovo
rovo/                     paused Forge/Rovo prototype (JS agent, on hold)
```

## Design & concept

- **Design**: [`docs/design-java/`](docs/design-java/) (concepts J1–J8, `STATUS.md`).
- **Discovery / decisions**: [`docs/discovery/servicenow-triage-java/`](docs/discovery/servicenow-triage-java/).

## Repositories

- **Primary**: Home Git (Forgejo) `eugene/hackathon2026` — the `origin` remote.
- **Mirror**: GitLab `eugene.novikov/hackathon2026` (private) — kept in sync
  automatically on every push via a server-side push mirror.
