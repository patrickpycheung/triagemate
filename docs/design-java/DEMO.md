# Demo Walkthrough — TriageMate

What the running Spring Boot app does, end to end, and how to run it.

> Screenshots/video removed for now — deferred until the app is finalized (captured from
> the real app via Playwright against the offline default (`mock`) connector config: no
> network, no LLM, no external systems). This walkthrough describes verified, real
> behavior in the meantime.

## Run it

```bash
mvn spring-boot:run            # from the repo root; needs JDK 21 (a compiler, not just a JRE)
# open http://localhost:8080 → the incident number INC0012345 is pre-filled → Diagnose
```

Verified in-session: `mvn test` → **34/34 pass**, `mvn -Padk test` → **50/50 pass**, app
boots in ~1.3s and serves the full diagnosis end-to-end offline.

## 1 · Trigger

A ServiceNow incident number goes in (manually here; a ServiceNow Business-Rule
webhook can call the same endpoint on ticket creation — no human in the loop).

## 2 · The automatic two-comment write-back (the payoff)

The copilot runs a bounded, evidence-gathering diagnosis and **automatically posts two
advisory comments back to the ticket — sources first, then its view.** It only
comments; it never reassigns, closes, or re-prioritises. Sources go first so the
diagnosis is auditable: every conclusion is one click from the evidence behind it.

- **Comment 1 — Sources consulted**: links to the exact Confluence page (KB001234),
  the Sumo Logic window (`order=INC-ORD-4471`), the GitLab line
  (`payment_service.py:44`), and the two similar resolved incidents.
- **Comment 2 — First-pass diagnosis (advisory)**: what appears to have happened, the
  likely system (Payment Service) and team (Payments Platform Support, medium
  confidence), the next check, and the standing "no reassignment or changes made."

## 3 · Full diagnosis view

The demo UI shows the whole structured report the two comments are rendered from —
clarified symptom, ranked candidate systems with confidence, evidence from all four
systems (including the log↔code citation `payment_service.py:44`), and the tool-call
trace proving it really consulted each source.

## The worked example

The mock dataset (`src/main/java/.../gateway/mock/`, reusing the S3′ Sumo fixture
and `seed-repo`) models incident **INC0012345 / order INC-ORD-4471**: discounted
orders fail at checkout because a percentage discount is applied *after* tax in the
gateway while the expected total discounts *before* tax — surfaced as
`PAYMENT_RECONCILE_MISMATCH expected=11.50 charged=11.25`, tied back to
`payment_service.py:44`.

## Optional: post the comments to a REAL ServiceNow ticket

The walkthrough above runs against the offline default (`mock`) connector config. To
make the two comments land on a real **dev** ServiceNow incident (and show it updating
live in ServiceNow), switch just the ServiceNow connector to real
(`triage.connectors.servicenow=real` — a per-connector property, not a Spring profile):

```bash
cp secrets.properties.example secrets.properties   # fill in triage.integrations.servicenow.*
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=snow-live
```

Type a real incident number → the *Sources consulted* and *First-pass diagnosis* entries
appear in that ticket's Work notes / Activity stream. Full steps + the service-account
and write-field notes are in [`../../README.md`](../../README.md) → "Live demo".
Evidence stays mock/curated; only ServiceNow is live. Run it where the dev instance is
reachable (the corporate-network laptop).

## Generating screenshots (deferred)

Not run yet — planned once the app is finalized. App running on `:8080`, then
`node bin/shot.mjs <out-dir>` (Playwright + chromium) captures landing / full-page /
write-back close-up / mobile, to be kept under `docs/design-java/screenshots/`.
