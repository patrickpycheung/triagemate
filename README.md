# TriageMate

A local **Spring Boot + Google ADK** app that gives every new ServiceNow incident a
**first-pass diagnosis automatically** — turning a blank ticket into a head start.

When a ticket comes in, the copilot reads it, gathers evidence across the systems your
teams already use (ServiceNow, Confluence, Sumo Logic, GitLab), and **posts two
advisory comments back to the ticket**: the **sources** it consulted (with links),
then its **first-pass diagnosis** (likely system, likely team, evidence, next check).

It also surfaces **who to talk to** (J9): the wiki authors of the runbooks it consulted
and the recent committers to the implicated source file — merged so whoever has the most
context ranks first. Shown in the triage UI (not posted to the ticket).

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

The last line printed is the URL to open — click it. Stop with `Ctrl+C`.

```
  ==========================================================
   TriageMate is ready   →   http://localhost

   engine:     deterministic
   connectors: servicenow=mock, confluence=mock, sumo=mock, gitlab=mock
  ==========================================================
```

The default port is **80**, so the URL has no port tail. That port is privileged on
macOS/Linux — plain `mvn spring-boot:run` there needs `sudo`, or pass
`--server.port=8080`. The run scripts below handle this for you.

### The four run scripts

Two independent axes — which **engine** reasons, and whether the **data** is mock or
live. Both `-real` scripts are thin wrappers that add `--spring.profiles.active=real`
to the mock script beside them.

| | Mock data (offline, guaranteed) | Real data (live systems) |
|---|---|---|
| **Deterministic** (no LLM) | `./run-deterministic.sh` | `./run-deterministic-real.sh` |
| **ADK** (live agent) | `./run-adk.sh` | `./run-adk-real.sh` |

All four serve on **port 80** by default (as does `application.yml`), so the URL is
just `http://localhost`. Linux reserves ports below 1024 for root, so run the one-time
setup once per machine:

```bash
sudo ./bin/setup-custom-domain.sh
```

That maps `triagemate.auspost.local` **and** lowers the unprivileged-port floor
(persisted in `/etc/sysctl.d/`), after which `./run-*.sh` binds 80 as your normal user
— sudo to set up, never to run. Until then the scripts fall back to 8080 with a note
rather than failing, and the startup banner always shows whichever port it actually
got. Pass `--server.port=N` to pin one explicitly; that is never second-guessed.

**ADK + mock data is not a scripted replay.** The mocks fix what the *tools return*;
ADK still calls the real model through the Copilot proxy, so the reasoning, the
tool-choice decisions and the ~8s thinking pauses in the trace are all genuine, and
two runs won't be identical. That combination — live agent, guaranteed data, no
connector network — is usually the best one to demo: it shows the real thing working
without depending on four systems being reachable.

Rough order of risk on stage: `run-deterministic.sh` (nothing can fail) →
`run-adk.sh` (needs the proxy) → `run-deterministic-real.sh` (needs four connectors) →
`run-adk-real.sh` (needs both).

Each `-real` script flips **all four** connectors. For a partial mix, pass the
individual key to the mock script instead — e.g. real ServiceNow, everything else
curated:

```bash
./run-deterministic.sh --triage.connectors.servicenow=real
```

## How to use it

**From the UI** (recommended for the demo)
1. Open the URL from the startup banner — the incident number **`INC0010005`** is pre-filled.
2. Click **Diagnose**.
3. You'll see the full diagnosis and, under *"Posted to ServiceNow — automatically,"*
   the two advisory comments it writes back (sources first, then the diagnosis).

**From the API** (same thing, headless)
```bash
curl -X POST http://localhost/api/diagnose/INC0010005 | jq   # add :8080 if it fell back
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
gitignored `secrets.properties` file (a standard Java properties file that Spring Boot
auto-imports) — copy the template, fill it in, and just run. No shell `export` needed:

```bash
cp secrets.properties.example secrets.properties
# fill in triage.integrations.servicenow.{base-url,user,secret}

mvn spring-boot:run -Dtriage.connectors.servicenow=real
```

(There are only two named profiles — the default, all-mock config, and `real`, which
flips **every** connector live at once. For just one connector, as here, override its
`triage.connectors.*` key directly rather than using a profile.)

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
cp secrets.properties.example secrets.properties
# fill in triage.integrations.llm.{base-url,api-key,model}

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
