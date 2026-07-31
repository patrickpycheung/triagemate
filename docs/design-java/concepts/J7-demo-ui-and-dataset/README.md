# J7 — Demo UI & Ground-Truth Dataset

**State**: 🟢 Built · **Complexity**: Moderate · **Depends on**: J4 ·
**Carries**: RC6 (demo safety), S3′ fixture + seed-repo

## Essence
What the audience sees, and the curated data that makes it work offline and
convincingly. Scope to **one** demonstration application — do not cover the enterprise.

## Demo UI
- A single static page (served by Spring Boot) with a box: enter incident number →
  "Diagnose" → renders the J4 report: symptom, ranked candidates + confidence bars,
  suggested team, **evidence list with source badges + links**, contradicting
  evidence, missing info, next action, and the tool-call trace (J8) so viewers see
  it "really consulted all four sources."
- Plain HTML + fetch to `/api/diagnose/{n}`; no framework needed.
- **"Who to talk to" card (FND-21: not previously cross-referenced here)** — J9's
  suggested contacts, sourced from wiki authors of consulted runbooks and recent
  committers to the implicated file. Advisory, UI-only — see the FND-2 privacy carve-out
  on J4: this never renders into the ServiceNow comments.
- Renders the **two comments posted automatically to ServiceNow** (sources, then
  advisory diagnosis) so viewers see the real write-back — **but only when
  `data.writebackPosted` is true** (FND-25, fixed 2026-07-30). This card previously
  rendered unconditionally, reconstructed client-side from the report regardless of
  whether writeback actually ran, so with `triage.writeback.enabled=false` the audience
  was told comments were posted when none were. `writebackPosted` is set by
  `DiagnosisOrchestrator` *after* the write decision — the only place that actually
  knows — never inferred from report content. When false, a plain "NOT posted" card
  shows instead.
- **Degraded-run banner (FND-16, fixed 2026-07-30)**: when the ADK engine failed and the
  orchestrator fell back to the deterministic one (FND-7), the UI shows a prominent
  amber banner — "this is NOT the live agent … no LLM was involved." Reads
  `data.engine === 'DEGRADED_TO_DETERMINISTIC'`, the actual `DiagnosisResult` field, not
  a regex over the trace text. It previously matched the trace *string*, which FND-8's
  own resolution already said wasn't a contract — proven by rewording that trace line and
  confirming the old check would have gone silently blank while still degraded.
- **Run walkthrough: [`../../DEMO.md`](../../DEMO.md)**. Screenshots deferred until the
  app is finalized (then captured via `bin/shot.mjs` / Playwright into `../screenshots/`).

## Ground-truth dataset (the default `mock` connector config serves this — J3;
## corrected 2026-07-30, there is no Spring `mock` profile)
For the one demo app, capture per incident: actual affected application, actual root
cause, correct assignment team, relevant Confluence page, relevant log evidence,
final resolution. Requirements (from the analysis):
- 3–10 historical incidents (≥2 "similar resolved" for the routing signal),
- 1–2 Confluence pages (a runbook / known-error doc),
- a known support team, one repository (**reuse the seed repo** at `docs/design/concepts/log-code-reasoning/verification-s3/seed-repo/` — FND-26: not repo-root),
- a small set of Sumo logs (**reuse S3′ `sumo-fixture.json`**),
- at least one **known error + resolution** with a seeded distinctive log line.

> **Amended by J11** — `J11-live-thinking-trace` replaces the flat trace card with an
> animated per-step trace (platform logo, in-progress state, resolve-in-place). It also
> requires J7 to gain the `--sn/--cf/--sl/--gl` brand CSS variables, which do **not** exist
> in `static/index.html` today. See `../J11-live-thinking-trace/README.md`.

## Demo scenario (locked narrative)
"A user reports an operation failed with a vague description. The copilot clarifies
the symptom, finds a similar resolved incident, locates the runbook, runs one narrow
Sumo query, correlates the log line to `seed-repo` source (file:line), suggests the
likely team — medium confidence — and posts an advisory work note. No reassignment
performed."

## Demo safety (RC6)
- Everything runs on the default (`mock`) connector config with **no network** as
  the safe default (`triage.connectors.*=mock`, not a Spring profile — J3/FND-10).
- Fallbacks: any single tool failure degrades to "evidence omitted," never a crash.
- Pre-demo checklist; a recorded backup run.

## Offline evaluation (optional, strong)
Take resolved historical incidents, hide assignment + resolution, and check whether
the copilot reconstructs them (correct app + team in top-3).

**Error display (FND-48, fixed 2026-07-31)**: `run()` now checks `res.ok` before calling
`render()` and shows `data.error` (from J1's `DiagnosisApiExceptionHandler`) on failure.
Previously `render(await res.json())` ran unconditionally, and a non-2xx body has no
`report` field — `render()`'s first line dereferences `r.candidateSystems`, so the failure
mode on stage was a raw `TypeError`, not a message.

**FND-52 (same day)**: the first cut called `res.json()` *above* the `res.ok` check, which
only works while every error is one the advice maps. Anything unmapped still reaches
Spring's whitelabel page, and a browser `fetch` gets **HTML** — so `res.json()` threw and the
user saw `SyntaxError: Unexpected token '<'`. That swapped one opaque parse error for
another. Now: read as text, parse defensively, guard on `res.ok` first.

**FND-54**: `MockServiceNowGateway` used to fabricate a context for *any* number, so a stage
typo returned a confident diagnosis of a nonexistent incident — worse than the TypeError it
replaced, and it made the 404 unreachable in the demo config (only the *real* gateway threw).
The mock now rejects anything but its one modelled incident, `INC0012345`.

## Verification
- Fresh checkout + `mvn spring-boot:run` → open page → diagnose the demo incident →
  full report with the log↔code citation, no network.
