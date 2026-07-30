# J8 — Guardrails & Observability (cross-cutting)

**State**: 🟢 Built · **Complexity**: Simple (but woven through J2/J3/J5/J6) ·
**Depends on**: all

## Essence
The leash and the flight recorder. Makes the copilot safe to point at real
enterprise data and makes the demo credible ("look — it really did call these
tools, within these limits").

## Guardrails
- **Advisory only**: no auto-reassign, no close, no priority change, no remediation.
  The write-back is **automatic** (no human in the loop) but limited to **two labelled
  advisory comments** (sources + diagnosis, J5). Trust comes from *what* it's allowed
  to do (only comment) — not from a human gate. The assigned engineer still decides.
- **Untrusted input**: incident text, comments, wiki pages, log messages and source
  are treated as data, never instructions (prompt-injection defense). The model may
  not broaden its own permissions, fetch arbitrary secrets, run unlimited searches,
  download whole repos, execute code found in docs, or send data to unapproved
  destinations.
- **Least privilege + allowlists**: least-privilege service accounts — **read-only** for
  Confluence / Sumo / GitLab, but ServiceNow needs **read + write on `incident`**.
  *(Corrected 2026-07-31: this line read "read-only service accounts" flatly, which was
  false and always had been — posting the two advisory work notes is the app's entire
  payoff, and both `application.yml:112` and `RealServiceNowGateway.java:29` specify
  read+write.)* That write is narrow by design: append to one journal field; never
  reassign, close, or re-prioritise. Allowlisted
  GitLab projects (`triage.gitlab.allowed-projects`) and Sumo `_sourceCategory`
  scopes (`triage.sumo.allowed-scopes`); ServiceNow writes go to one configured
  field (`triage.servicenow.write-field`), never model-chosen. Confluence search
  has **no space-scoping mechanism at all** (corrected 2026-07-30 — this line
  previously claimed "allowlisted Confluence spaces" as if it were a fourth
  enforced bound; `ConfluenceGateway.search(query)` has no space parameter to
  allowlist). That is a deliberate design choice, not an oversight: J6 documents
  Confluence as intentionally "cheap, broad" — containment there comes from the
  read-only service account's own space permissions and a small result cap (5),
  not an app-level allowlist.
- **Bounds enforced in code, across three layers (FND-18/24/32 corrected the claims
  below to match what's actually enforced, and where)**:
  - `beforeToolCallback` (`BoundsCallback`, J2) — a **global** tool allowlist (all
    eight registered tools, every step; there is no per-step allowlist, see J2's
    FND-13 correction) and a max-tool-calls budget.
  - `TriageMateTools` (J6, FND-20/FND-38) — per-call result caps, a bounded Sumo
    time window, and the GitLab project allowlist; these are NOT model-supplied
    and NOT enforced by `beforeToolCallback`.
  - `DiagnosisOrchestrator` (J1, FND-15) — a wall-clock timeout on the whole engine
    call, on either engine.
  A tool *existing* ≠ the model may call it anywhere — but "anywhere" is bounded at
  the layer that actually owns each limit, not uniformly by one callback.

## Observability (per-run trace)
Record for every run: which tools were called and, for J9, who was suggested and
why. **Not currently recorded** (FND-18 corrected the claims below, which described
an aspirational MVP that was never built this way): query params, documents/records
retrieved, the model id, or human accept/reject — the human-confirm gate this last
one refers to was removed 2026-07-23 (see J5/`PIVOT.md`); recording an "accept/
reject" decision that no longer happens would be actively misleading. Later: final
actual assignment + resolution — the data that proves whether the tool reduces
assignment bouncing.
- **Amended by J11** (live thinking trace): an *additive* `List<TraceStep>` structured
  channel is being designed alongside this; `DiagnosisResult.trace` stays byte-identical and
  is neither replaced nor parsed. See `../J11-live-thinking-trace/README.md`.
- **Actual MVP**: one plain-text line per notable event (`SLF4J`, via
  `DiagnosisOrchestrator`/the engines), not structured JSON — surfaced to the UI
  (J7) as `DiagnosisResult.trace`, plus the machine-readable `DiagnosisResult.engine`
  field (FND-8/16) for "was this a live or a degraded run" specifically.

## Judging alignment (from the analysis)
Optimize the trace to answer: clearer summary? missing info identified? correct app
in top-3? correct team in top-3? useful evidence cited? relevant past incident
found? sensible next action? — not "did it nail root cause."

## Verification
- **FND-19, fixed 2026-07-30**: `PromptInjectionGuardrailTest` — no real LLM is
  available offline to red-team, so what's actually tested is the architectural
  guarantee: `ServiceNowGateway` exposes no reassign/close/priority-change method at
  all (reflection over the interface), and 5 fixture payloads embedded in report
  text fields never change the write behaviour — exactly 2 fixed-format advisory
  notes every time, payload rendered as inert verbatim text, never specially
  interpreted. Payloads load from `src/test/resources/fixtures/
  injection-payloads.jsonl` (this repo's pre-commit guard blocks raw injection
  strings in source).
- The J2 allowlist test (`BoundsCallbackTest`) covers the other half: an
  out-of-allowlist or hallucinated tool name is denied before it executes.
- The run trace lists every tool call and is rendered in the UI (J7); it does not
  currently record query params or the model id — see the corrected claim above.
