# J19 — Instruction/Config Fidelity (the prompt tells the truth about the run's bounds)

**State**: 🟢 **Built** — all five ICF rules (2026-08-06) — ICF-1 (one derivation of the default environment, owned by TriageProperties.Sumo and used by both engines), ICF-2 (the last configurable literal — "use prod" — removed from the prompt), ICF-3 (budget disclosed as a number) and ICF-5 (invariant tests, run against an estate sharing none of the demo defaults) landed. **ICF-4 landed 2026-08-06** — denials carry a Cause, allow() delegates to deny() so the two forms cannot disagree, denialReason keeps its exact text for the LT2 trace row, and the MODEL-facing message is derived from the cause. **All five rules built.** ·
**Complexity**: Simple ·
**Priority**: MEDIUM ·
**Depends on**: J2 (ADK agent engine + `instruction()`), J8 (the bounds themselves) ·
**Amends**: J8 (extends the FND-60 disclosure rule from allowlists to the tool-call
budget, and adds the prompt-literal rule to its Open/risks), J2 (instruction text and
the denial payload returned to the model) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 2 confirmed findings

## Essence

Everything the ADK instruction says about a bound is **derived from the same
`TriageProperties` value the app enforces** — never a second literal, never silence.
FND-60 established half of this ("a guardrail that rejects a model-supplied value needs
a matching answer to *how does the model learn the valid ones?*", J8 Open/risks) and
built it for the Sumo environment and GitLab project allowlists. This card finishes the
job: the *fallback* value inside that same block stops being a hardcoded `prod`, the
tool-call **budget** joins the disclosed bounds, the denial the model reads names the
right scope of "stop", and the guarding test asserts the invariant instead of one
sample of it.

## Why one card

Three of the four items live inside one 12-line block of one string
(`AdkDiagnosisEngine.instruction()`, `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java:138-148`)
and the fourth is the reply the model reads when it violates that block. Fixed
piecemeal they produce three separate edits to the same paragraph, three separate
assertions bolted onto one test, and no statement of the rule that would have caught
either finding — which is exactly how FND-60 was "fixed" and still left a hardcoded
allowlist value two lines below its own fix. The card exists to land the *rule*
(ICF-2) alongside the two instances, so the next bound added to this block cannot
repeat the class.

## Evidence — what the review found

| # | What | Where | Severity | Failure scenario |
|---|---|---|---|---|
| 1 | The BOUNDED VALUES block injects the configured environment allowlist via `%s`, then two lines later hardcodes `use prod` — a second copy of an allowlist value, in the very block whose comment (`:192-194`) claims one source "so they cannot drift" | [`AdkDiagnosisEngine.java:145`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L145) | MEDIUM | Operator narrows `triage.sumo.allowed-environments` for a tenant or a demo profile (e.g. `["vtest"]`). An incident with an unclear environment field makes the model follow the instruction and call `search_logs(environment="prod")`; [`TriageMateTools.java:131`](../../../../src/main/adk/java/com/company/triage/agent/TriageMateTools.java#L131) hard-throws `environment not allowed`. One of the 10 budgeted tool calls plus one LLM round trip burned on a rejection **the app itself dictated** |
| 2 | The prompt never states the tool-call budget — only "wastes one of your limited tool calls" | [`AdkDiagnosisEngine.java:138-139`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L138) | MEDIUM | The model cannot plan a 10-call investigation it does not know is 10 calls. Budget arrives only as a *reaction* — after it has already been spent |
| 3 | The budget-exhaustion denial is phrased per-tool: `"... ; stop calling that tool and produce the report from what you have"` is appended uniformly to allowlist **and** budget denials | [`AdkDiagnosisEngine.java:330-331`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L330) | MEDIUM | "Stop calling *that* tool" invites trying a different one. Every retry is another LLM turn against `setMaxLlmCalls(maxToolCalls + 5)` ([`:682`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L682)); a sequential 10-call run already needs 11 turns, leaving 4. Exhausting them raises ADK's `LlmCallsLimitExceededException` — no report, and the orchestrator degrades to `DEGRADED_TO_DETERMINISTIC` mid-demo |
| 4 | `AdkAllowlistVisibilityTest.instructionReflectsConfiguredAllowlistsNotHardcodedDefaults` builds `allowed-environments=["sandbox"]` and asserts only `doesNotContain("pdev")` | [`AdkAllowlistVisibilityTest.java:62-80`](../../../../src/adk-test/java/com/company/triage/agent/AdkAllowlistVisibilityTest.java#L62) | MEDIUM | The test **currently passes against the broken state**: with `["sandbox"]` configured, the instruction it asserts on still says "use prod". A test whose javadoc states the invariant ("the allowlists must come from config, not be hardcoded a second time in the prompt") samples one violation and misses the one that is there |

Verifier notes that sharpen the above:

- On #1 — **latent, not live**: the shipped `application.yml:153-158` allowlist does
  contain `prod`, so the demo path is unaffected today. The failure needs an operator
  to narrow or rename environments, which real-connector mode against a real tenant
  makes realistic. That is why this is MEDIUM and not HIGH — and also why a test, not a
  fix alone, is the deliverable: nothing on the demo path will ever surface it.
- On #3 — the `+5` comment at
  [`:680-681`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L680)
  justifies the headroom as covering "the investigation PLUS one possible FND-42 repair
  round trip". The verifier confirmed by `javap` on ADK 1.7.0 that the LLM-call counter
  lives on a per-`InvocationContext` `invocationCostManager`, and the FND-42 repair is a
  **second** `runner.runAsync` ([`:693`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L693)),
  so the repair gets a fresh budget and shares nothing. The `+5` is really 4 spare
  post-exhaustion turns. The number is fine; its stated reason is wrong.
- On #3 — the verifier called the denial-loop-to-abort scenario "plausible but
  pessimistic": it needs the model to ignore "produce the report from what you have"
  several times. Recorded honestly; the design still changes the wording, because the
  cost is one `if` and the phrasing is simply false when the budget is what ran out.

Also verified while reading the code, and load-bearing for the design:
`DeterministicDiagnosisEngine.java:68-73` **already** derives exactly the fallback the
prompt hardcodes — "`prod` when it's a configured environment, else the first one, so a
deployment that renames its environments still gets a valid category" — from
`props.sumo().allowedEnvironments()`, and feeds it to
`IncidentSignals.environmentCode(env, allowed, fallback)`
([`:190`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L190)).
The rule is not missing from the codebase. It is missing from the prompt.

## Design

### ICF-1 — One derivation of the default environment, shared by both engines

Move the four-line expression at `DeterministicDiagnosisEngine.java:68-73` onto the
config record as a derived accessor, `TriageProperties.Sumo#defaultEnvironment()`:
`prod` when the allowlist contains it, otherwise the first entry, otherwise `prod` when
the list is null/empty. The deterministic engine calls it instead of its private field;
`instruction()` interpolates it. **One derivation, two readers** — the FND-40/FND-60
shape, applied to the one value they both missed.

*Rejected — copy the ternary into `AdkDiagnosisEngine`*: a third copy of a rule that
already exists twice is the defect, not the fix.

*Rejected — a new `triage.sumo.default-environment` key*: it adds a value an operator
can set to something outside `allowed-environments`, re-creating finding #1 in YAML
where no test can see it. The derivation is **total** and cannot yield a disallowed
value; a key cannot promise that.

Accepted wart: `get(0)` makes the fallback sensitive to YAML list order. The
deterministic engine has accepted this since FND-40; ICF-1 does not change the
behaviour, only where it lives. Null-safety of the accessor is ICF-1's own concern;
making a null `allowed-environments` impossible at boot belongs to **J20**.

### ICF-2 — No operator-configurable value appears in the prompt as a literal

The rule, stated so the next bound cannot repeat the class: **any value that an
operator can change in `application.yml` and the app then enforces must reach the
instruction through `.formatted()`, never as prose.** Today that set is: Sumo
allowed environments, the derived default environment (ICF-1), GitLab allowed projects,
the tool-call budget (ICF-3). It deliberately does **not** cover the tool *names*
(`get_incident`, `search_logs`, …), which are hardcoded in the instruction and again in
`ToolRegistry.ALLOWED_TOOLS`: those are method names ADK registers, not operator
config, and cannot drift from a config file that does not describe them. Scoping the
rule this way keeps it enforceable — an implementer who over-applies it ends up
generating tool documentation from reflection, which J11 LT2 already rejected for a
different reason ("do not make an observability component the source of truth for which
tools are permitted").

Lands in J8's Open/risks next to the FND-60 paragraph, because that is where the next
person adding a guardrail will look.

### ICF-3 — The budget is disclosed up front, as a number

`instruction()` is already per-instance and already `.formatted()`; `maxToolCalls` is
already a field (`:180`, `:191`). Add to the BOUNDED VALUES block: *"You may make at
most %d tool calls in this run in total, across all tools. Plan the investigation to
fit — when the budget runs out you must produce the report from whatever you have."*
The proactive statement is the point: the current wording ("wastes one of your limited
tool calls") is a warning about a number the model is never given.

*Rejected — disclose remaining budget per call* (e.g. appending "3 calls left" to each
tool result): it needs a per-call mutation of tool output, changing `TriageMateTools`
return shapes and J11's step rows for a signal the model can track itself from a
disclosed total. Not worth the blast radius for a hackathon demo.

### ICF-4 — Denials are typed by cause, and only the model-facing wording differs

`BoundsCallback.allow(String)` returns a bare boolean and the reason is recovered by
calling `denialReason(String)`, which re-derives the cause. Replace the boolean with a
typed decision — `Optional<Denial> deny(String toolName)` where `Denial` carries
`Cause.ALLOWLIST | Cause.BUDGET` and the existing human-readable text — and build both
model-facing payloads from the cause:

- `ALLOWLIST` → keep today's wording verbatim. "That tool" is *correct* here: other
  tools remain available and the model should switch.
- `BUDGET` → *"the tool-call budget for this run (%d) is exhausted; do not call ANY
  tool again — produce the JSON report now from what you have."*

Two constraints this must not break. First, **`denialReason(String)` stays** and keeps
its current text: J11 LT2 carries it into the `DENIED` row's `result` so that
allowlist-rejection and budget-exhaustion stay distinguishable in the trace. Second,
the decision must still be computed **before** any `TraceSink` call and the returned
`Optional` must not depend on trace emission — the STREAM-003 safety contract
documented at `AdkDiagnosisEngine.java:296-302` and pinned by `AdkToolEdgeSafetyTest`.

*Rejected — string-match the reason in the engine* (`why.startsWith("max tool calls")`):
that is parsing a human-readable string to recover a decision the code already made —
the FND-16 class J11 named outright ("never parse trace strings"). Typed cause or
nothing.

Because the budget message is terminal ("do not call ANY tool again"), the expected
number of post-exhaustion turns drops to one, which is why ICF-4 leaves
`setMaxLlmCalls(maxToolCalls + 5)` **unchanged**. Its comment at `:680-681` is corrected
to state what the `+5` actually buys — post-exhaustion denial turns — since the
per-`InvocationContext` counter means the FND-42 repair never drew on it.

### ICF-5 — The guard test asserts the invariant, not one sample

`instructionReflectsConfiguredAllowlistsNotHardcodedDefaults` currently proves "`pdev`
is absent when `sandbox` is configured". Rewrite it to prove the invariant its own
javadoc states: with a custom config, **no** environment code outside the configured
list appears anywhere in the instruction — checked against the full known vocabulary
(`pdev`, `ptest`, `stest`, `vtest`, `prod`), not one member of it. Same for the GitLab
project. This is the assertion that would have failed on finding #1 the day it was
written.

## Verification

All ADK-side tests live in `src/adk-test/` and therefore run **only under `-Padk`**
(baseline 201; default profile baseline 152, both green).

- **`AdkAllowlistVisibilityTest`** (`-Padk`, extended):
  - `instructionReflectsConfiguredAllowlistsNotHardcodedDefaults` — rewritten per ICF-5:
    configure `["sandbox"]` / `["team/other-repo"]`, assert the instruction contains
    both and contains **none** of `pdev|ptest|stest|vtest|prod` and no other project
    slug. Fails today.
  - `instructionNamesTheDerivedDefaultEnvironment` — with `["sandbox"]`, the instruction's
    fallback clause names `sandbox`; with a list containing `prod`, it names `prod`.
  - `instructionStatesTheToolCallBudget` — build with `new TriageProperties.Agent(3)`,
    assert the instruction states `3`; rebuild with `10` and assert `10`, so the
    assertion cannot pass on a hardcoded number.
- **`TriagePropertiesSumoDefaultEnvironmentTest`** (default profile, `src/test/`, new):
  `defaultEnvironment()` returns `prod` when allowed, the first entry when not, and
  `prod` for an empty or null list. Lives in the default profile because
  `TriageProperties` is in `src/main/java/` — the same reason `ToolRegistry` was moved
  there (see its javadoc).
- **`BoundsCallbackTest`** (`-Padk`, extended): `deny()` returns `Cause.ALLOWLIST` for an
  unregistered name **without consuming budget** (the existing no-budget-charge rule,
  `BoundsCallback.java:54-58`), `Cause.BUDGET` on the call after the last allowed one,
  and `denialReason(String)` text is unchanged for both — pinning J11's `DENIED` row.
- **`AdkToolEdgeSafetyTest`** (`-Padk`, extended): a budget denial and an allowlist
  denial return *different* payloads to the model, and both still return their denial
  `Optional` intact when the `TraceSink` throws. Requires ICF-4's payload construction
  to be extracted into a package-private static method so it is reachable without a live
  runner.

## Out of scope

- **Whether the bounds themselves are complete** — `find_recent_committers` missing the
  GitLab allowlist, ServiceNow encoded-query constraints → **J18**.
- **Startup validation of the properties this card reads** — a null
  `allowed-environments` or `allowed-projects` reaching `defaultEnvironment()` at all →
  **J20**. J19 assumes non-null and degrades safely if it is not; J20 makes it a boot
  failure.
- **The instruction's JSON-contract and fencing guidance** (FND-66, `unfence()`) — a
  parsing concern, not a config-fidelity one; handled outside this card.
- **Whether prompt-only guardrails should become structural** — J8's accepted FND-44
  limitation. J19 makes what the prompt *says* true; it does not promote any statement
  from prose to enforcement.
- **What the UI shows about bounds and degradation** → **J23**.
