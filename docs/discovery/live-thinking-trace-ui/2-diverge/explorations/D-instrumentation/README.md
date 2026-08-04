# Exploration D — Instrumentation & data model

**Bias**: the domain data model, and how the two engines emit structured step events
without wrecking the existing design. Transport (SSE/poll/WebSocket) and visual design
are owned by other explorations — this one assumes *some* channel exists and asks what
flows through it.

Everything below was read in-session against the current tree (`32e2b47`).

---

## 0. What the code actually does today (verified)

| Fact | Where |
|---|---|
| `DiagnosisResult(report, trace, engine, writebackPosted)`, 2 back-compat ctors | `orchestration/DiagnosisResult.java:25-54` |
| `DiagnosisEngine` is a 1-method interface: `DiagnosisResult diagnose(String)` | `orchestration/DiagnosisEngine.java:10` |
| Deterministic engine: `List<String> trace = new ArrayList<>()` at line 63, **11 `trace.add(...)` sites**, returns `new DiagnosisResult(report, trace)` | `DeterministicDiagnosisEngine.java:62-177` |
| ADK engine: same local `ArrayList`, filled from `beforeToolCallbackSync` (lines 160-169) + 3 direct adds (177, 208, and the DENIED line) | `agent/AdkDiagnosisEngine.java:126-179` |
| Orchestrator **mutates the engine's list after the fact**: `result.trace().add(...)` ×4 and `add(0, ...)` for the FND-7 degradation line | `DiagnosisOrchestrator.java:148,150,155,160,182` |
| The list must therefore stay **mutable** and the returned result is **rebuilt twice** (lines 165, 177, 184-185) | same |
| UI renders trace as one `<pre>`-ish blob; the *only* semantic read is `data.engine` / `data.writebackPosted` | `static/index.html:95,134` |
| Test coupling to trace **wording**: 10 assertions, all `startsWith`/`contains` on prefixes (`sumo.search`, `contacts:`, `adk tool call: get_incident`, `DENIED get_incident`, `Sources consulted`, `writeback failed partway through`, `degraded to the deterministic engine`, `DiagnosisTimeoutException`, `one repair retry (FND-42)`) | `DeterministicDiagnosisEngineTest:58-59`, `DiagnosisOrchestratorTest:60,95,125,178`, `AdkLiveRoundTripTest:48,67,96` |
| **NEW (verified this session)**: ADK 1.7.0 exposes `afterToolCallbackSync` with signature `(InvocationContext, BaseTool, Map<String,Object> args, ToolContext, Object response) -> Optional<Map<String,Object>>` | `javap` on `com.google.adk.agents.Callbacks$AfterToolCallbackSync`, `google-adk-1.7.0.jar` |

That last row is the single most important finding in this exploration: **the ADK path
can resolve a step in place**, because `after` hands us the tool's actual return value.
Without it, structured "found KB001234" on the agent path would be impossible and the
whole in-progress → resolved idea would only work deterministically.

Also note the asymmetry the brief flags is real but *smaller than it looks*: the ADK
agent's tool order is model-chosen at runtime, but the **tool set is a closed 8-name
allowlist** (`AdkDiagnosisEngine.ALLOWED_TOOLS:116-124`). So we can't know the *order*
in advance, but we always know the *vocabulary* — which is all a label mapping needs.

---

## 1. Option 1 — parse the existing trace strings

Client- or server-side regex over `servicenow.getIncident(...) → CI=..., env=...` to
recover platform + result.

**Reject.** Not on taste grounds — on precedent. FND-16 (`docs/audit/found-issues-archive.md:519-537`)
is *this exact bug*, committed by this project, in the same UI file, four commits ago:
the degraded banner regex-matched a trace line while a real `engine` field sat unused.
Its resolution is quoted in `DiagnosisResult`'s own javadoc: **"string-matching a trace
line is not a contract."** The escape note says the lesson explicitly: *"A field that
exists specifically to replace a string-match should be used by the FIRST thing that
needs the distinction, not retrofitted after."*

Shipping a step-parser would re-introduce the retired bug at ~11× the surface area (11
line formats instead of 1), and every trace-wording tweak becomes a silent UI
regression. It is also *not even cheaper*: the deterministic engine's lines are
`String.formatted` with embedded counts and identifiers, so a faithful parser needs 11
regexes, i.e. more code than the record it is avoiding.

One narrow legitimate use survives: `platform = substringBefore(line, '.')` as a
**fallback** for a step whose tool key is unknown (a hallucinated ADK tool name). Cheap,
and its failure mode is a generic icon, not a wrong claim.

---

## 2. Option 2 (recommended) — a real `TraceStep` record, alongside `List<String>`

Placed in `src/main/java/com/company/triage/orchestration/trace/` so **both** engines can
use it (`src/main/adk` may reference `src/main/java` — `AdkDiagnosisEngine` already imports
`DiagnosisEngine` and `DiagnosisResult`; the reverse is what's forbidden).

```java
package com.company.triage.orchestration.trace;

/**
 * One observable step of a run (J7). Structured on purpose: the UI must not infer
 * platform/state by string-matching a trace line — that is the FND-16 bug.
 *
 * label   — present BEFORE the outcome is known ("Searching Confluence…").
 * result  — the resolved one-liner, null until state is DONE/FAILED/DENIED.
 * legacy  — the exact List<String> line this step renders as, so the human-readable
 *           trace stays byte-identical to what shipped (C-1, and 10 test assertions).
 */
public record TraceStep(
        int seq,                 // 1-based, stable within a run: the UI's key
        Platform platform,
        String tool,             // canonical key: "confluence.search" | "search_confluence"
        String label,            // in-progress text, authored up front
        String result,           // resolved text, nullable
        State state,
        long startedAtEpochMs,
        Long durationMs          // null until resolved
) {
    public enum Platform { SERVICENOW, CONFLUENCE, SUMO, GITLAB, TRIAGEMATE }
    public enum State    { PENDING, ACTIVE, DONE, FAILED, DENIED }
}
```

Field-by-field rationale:

- **`seq` over UUID** — the UI needs to patch a row in place ("resolve"); a monotonic int
  is the smallest thing that does that, sorts naturally, and is readable in a demo.
  No cross-run identity is needed (persistence is out of scope per elicit).
- **`platform` as an enum, not a string** — it drives an icon; an enum makes the
  exhaustive switch in the UI/tests fail loudly when a platform is added.
- **`label` *and* `result` as separate fields, not one mutable `text`** — the resolve
  animation needs both at once (cross-fade old→new). Collapsing them forces the client to
  cache the previous value.
- **`durationMs`** — the honest answer to C-2/F-2: the deterministic engine's steps
  really take 0-3 ms, and the UI can *say so* ("2 ms") while still pacing the reveal.
  This is what makes a paced animation non-deceptive: the real number is on screen.
- **`legacy`** — deliberately **not** a component. See §5; the legacy string is produced
  by the collector, not stored twice.
- **No `error`/`throwable`** — `result` carries the failure text for `FAILED`/`DENIED`.
  A hackathon UI has nowhere to put a stack trace.

**Back-compat: `trace` stays, and stays authoritative for prose.** Do *not* derive
`List<String>` from steps by re-formatting — the 11 existing formats carry arg detail
(`sumo.search(scope=prod/payment, window=±10m, max=20) → 20 line(s); errorToken=X`) that
a generic renderer would flatten, breaking `startsWith` assertions and the demo's
credibility ("it shows the actual query"). Instead the collector takes the
already-formatted line at resolve time and appends it verbatim, so the strings are
byte-identical and **zero test assertions change**.

`DiagnosisResult` is **extended, not parallel-channelled**:

```java
public record DiagnosisResult(
        DiagnosisReport report,
        List<String> trace,
        Engine engine,
        boolean writebackPosted,
        List<TraceStep> steps        // NEW — the structured mirror of trace
) {
    public DiagnosisResult(DiagnosisReport r, List<String> t, Engine e, boolean w) {
        this(r, t, e, w, List.of());     // third back-compat ctor
    }
    // the two existing ctors delegate to that one, unchanged
}
```

Why extend rather than a separate `/api/steps/{n}` resource: the final payload already
carries `trace` for exactly this audience, `steps` is its typed twin, and one document
means the UI can render a *completed* run (page reload, poller-triggered run the viewer
joined late) with no extra fetch and no state reconciliation. A live channel (exploration
A) then streams the *same* `TraceStep` objects as they are produced — same schema on both
paths, which is the property that keeps the UI a single renderer instead of two.

---

## 3. Emission mechanism — four candidates

| Mechanism | Verdict |
|---|---|
| **Change `diagnose(String)` → `diagnose(String, TraceSink)`** | ✅ **Recommended**, with a `default` overload (below) so no caller changes. |
| `TraceCollector` created *inside* each engine and returned on the result | ✗ Structured steps then exist only *after* the run — the same F-1 dead end the trace has. Nothing can stream. |
| Spring `ApplicationEventPublisher` | ✗ Needs a correlation id in every event, a per-run subscriber registry, and it drags `@Component`-ness into the ADK static-tool world. Ordering across a virtual thread is not guaranteed by contract. Real cost, no benefit at this size. |
| `ThreadLocal<TraceSink>` (the FND-33 precedent) | ✗ See below. |

**On the FND-33 ThreadLocal precedent: it does not transfer.** That ThreadLocal exists
because ADK function-tool methods are `static` and *cannot* be handed a parameter —
`TriageMateTools.CURRENT_INCIDENT` is a workaround for an API we don't control, and its
own javadoc justifies it on exactly that ground. `DiagnosisEngine.diagnose` is *our*
interface; we can just add a parameter. Using a ThreadLocal where a parameter is
available would also be actively wrong here: the orchestrator runs each engine call on a
**virtual thread from `engineExecutor`** (`DiagnosisOrchestrator:191`) and reads/rebuilds
the result on the *caller's* thread, so a sink installed by the caller would not be
visible inside the engine without extra propagation code. Parameter passing is both
simpler and correct.

### The signature change that costs nothing

```java
public interface DiagnosisEngine {
    /** Emits structured steps as it goes (J7). Engines implement THIS. */
    DiagnosisResult diagnose(String incidentNumber, TraceSink sink);

    /** Every existing caller/test keeps compiling; steps go nowhere. */
    default DiagnosisResult diagnose(String incidentNumber) {
        return diagnose(incidentNumber, TraceSink.NOOP);
    }
}
```

Direction matters: engines implement the 2-arg form and the interface *defaults* the
1-arg form. The inverse (default 2-arg delegating to 1-arg) would compile just as well
but silently drop steps from any engine that forgot to override — a FND-8-class
"documented but not implemented" trap.

### The sink

```java
public interface TraceSink {
    /** Announce a step BEFORE its outcome is known. */
    Step begin(TraceStep.Platform platform, String tool, String label);

    /** An instantaneous step with no pending phase (understand:, report assembled:). */
    void note(TraceStep.Platform platform, String tool, String label, String legacyLine);

    interface Step {
        /** Resolved happily. `legacyLine` is appended to List<String> verbatim. */
        void done(String result, String legacyLine);
        void failed(String why);
        void denied(String why);          // ADK allowlist/budget refusal
    }

    TraceSink NOOP = /* begin() returns a no-op Step; note() does nothing */;
}
```

`TraceCollector implements TraceSink` is the one real implementation: it owns the
`List<TraceStep>` **and** the `List<String>` (a `synchronizedList`/`CopyOnWriteArrayList`,
because with FND-31 coalescing two viewers may read a run's steps while the engine is
still writing them), stamps `startedAtEpochMs`/`durationMs`, and assigns `seq`. A
streaming transport composes rather than replaces: `new CompositeSink(collector, sseSink)`
— which is why this design is transport-agnostic and does not pre-empt exploration A.

**One required orchestrator discipline**: `DiagnosisOrchestrator` currently calls
`result.trace().add(...)` directly (lines 148,150,155,160,182). Left as-is, those five
lines would produce trace strings with **no matching step** — the two channels drift, and
drift between a real field and a rendered string is the FND-8/16/25 bug family. So the
orchestrator must hold the `TraceCollector` it created and emit through it
(`sink.note(SERVICENOW, "servicenow.addWorkNote", …, legacyLine)`), keeping the legacy
strings identical. The degradation line's `add(0, …)` becomes a `prependFailure(...)` on
the collector so it stays first in both lists.

---

## 4. The in-progress label problem

**Deterministic path** — trivial: the script is fixed, so the label is authored at the
`begin(...)` call site, right where the `trace.add` is today. 11 call sites become
`begin(...)` + `done(...)` pairs (or a single `note(...)` for the instantaneous ones).

**ADK path** — the label must be derived from `tool.name()` inside
`beforeToolCallbackSync`, which is the first moment we know anything. That is enough,
because the tool vocabulary is a closed 8-name set. A shared static catalog in
`src/main/java` (usable from both engines, and the single place the UI's platform mapping
is decided):

```java
public final class StepCatalog {
    private record Entry(TraceStep.Platform platform, String label) {}
    private static final Map<String, Entry> BY_TOOL = Map.ofEntries(
      // ADK snake_case names (== @Schema names == the allowlist in AdkDiagnosisEngine)
      entry("get_incident",           new Entry(SERVICENOW, "Reading the incident in ServiceNow…")),
      entry("find_similar_incidents", new Entry(SERVICENOW, "Looking for similar past incidents…")),
      entry("find_ownership",         new Entry(SERVICENOW, "Looking up service ownership…")),
      entry("search_confluence",      new Entry(CONFLUENCE, "Searching Confluence…")),
      entry("search_logs",            new Entry(SUMO,       "Searching Sumo Logic…")),
      entry("search_code",            new Entry(GITLAB,     "Searching GitLab code…")),
      entry("find_page_contributors", new Entry(CONFLUENCE, "Finding who wrote the runbook…")),
      entry("find_recent_committers", new Entry(GITLAB,     "Finding recent committers…")),
      // deterministic dotted keys, same platforms
      entry("servicenow.getIncident",          new Entry(SERVICENOW, "Reading the incident in ServiceNow…")),
      entry("servicenow.findSimilarIncidents", new Entry(SERVICENOW, "Looking for similar past incidents…")),
      entry("servicenow.findOwnership",        new Entry(SERVICENOW, "Looking up service ownership…")),
      entry("servicenow.addWorkNote",          new Entry(SERVICENOW, "Posting an advisory comment…")),
      entry("confluence.search",               new Entry(CONFLUENCE, "Searching Confluence…")),
      entry("sumo.search",                     new Entry(SUMO,       "Searching Sumo Logic…")),
      entry("gitlab.searchCode",               new Entry(GITLAB,     "Searching GitLab code…")));

    /** Never throws: an unknown (hallucinated) tool name still gets a legible row. */
    public static TraceStep.Platform platformOf(String tool) { … fallback: prefix before '.', else TRIAGEMATE … }
    public static String labelOf(String tool) { … fallback: "Calling " + tool + "…" … }
}
```

Keying the same map by both naming conventions is deliberate: it is the seam where the
two engines' vocabularies meet, it makes the ADK↔deterministic label parity *visible in
one screen*, and a test can assert `ALLOWED_TOOLS ⊆ BY_TOOL.keySet()` so adding a tool
without a label fails the build rather than showing a blank row.

**Resolving the ADK step** is the newly-verified `afterToolCallbackSync` (§0). The
`response` argument is the tool's actual return value, so:

```java
.beforeToolCallbackSync((inv, tool, args, ctx) -> {
    if (!bounds.allow(tool.name())) {
        String why = bounds.denialReason(tool.name());
        sink.begin(StepCatalog.platformOf(tool.name()), tool.name(),
                   StepCatalog.labelOf(tool.name()))
            .denied(why);                    // legacy line: "adk: DENIED %s — %s"
        return Optional.of(Map.of("error", why + "; …"));
    }
    active.put(tool.name(), sink.begin(platformOf(...), tool.name(), labelOf(...)));
    return Optional.empty();                 // legacy line: "adk tool call: " + tool.name()
})
.afterToolCallbackSync((inv, tool, args, ctx, response) -> {
    Step s = active.remove(tool.name());
    if (s != null) s.done(Summaries.of(response), "adk tool result: " + tool.name() + " → " + Summaries.of(response));
    return Optional.empty();                 // don't rewrite the response
})
```

`Summaries.of(Object)` is the one genuinely new piece of logic on the agent path:
`Collection` → `"N result(s)"`; `KnowledgeDoc` → `"Found " + id + " — " + title`;
`IncidentContext` → `"CI=… env=…"`; `Map` with `"error"` → the error text; else a
truncated `toString()`. It is a `switch` over the ~8 gateway return types the tools
already declare, so it is exhaustive-by-construction and testable without an LLM.

Two honest caveats to record: (a) `active` is a per-run map keyed by tool name — if the
model calls the same tool twice concurrently the pairing is ambiguous; ADK's loop is
sequential per invocation, and the FND-33 note already relies on "one run owns its
thread", so keying by name is acceptable here, but it is an assumption, not a guarantee.
(b) if a tool *throws*, `afterToolCallback` may not fire; the collector should mark any
still-`ACTIVE` step `FAILED` when the run ends, so no row spins forever.

---

## 5. Platform attribution for non-platform steps

`understand:`, `contacts:`, `report assembled:`, `adk agent finished: N tool call(s)`,
the FND-42 repair-retry line, the FND-7 degradation line, and the
`writeback disabled (…)` line touch no external system.

**Recommendation: a `TRIAGEMATE` pseudo-platform** (not `null`, not `INTERNAL`). Reasons:
`null` forces every consumer to branch and invites a blank icon; `INTERNAL` is
engineering vocabulary the audience doesn't share; `TRIAGEMATE` is truthful ("this step
was *us* thinking, not a system we queried"), it gives the app's own mark a place in a
row of third-party logos, and it sidesteps C-5 entirely for those rows since our own mark
needs no trademark care. It also reads correctly in the demo narrative — the audience
sees the copilot's *own* reasoning interleaved with the systems it consulted, which is
precisely the "process of thinking" ask.

Sub-classification is unnecessary at hackathon rigor, with two exceptions worth encoding
because they carry different *meaning*, not a different platform:

- the FND-7 degradation step → `platform=TRIAGEMATE, state=FAILED`. The banner still
  reads `data.engine` (FND-16 — do not regress that); the step just makes the failure
  visible *in sequence*.
- `writeback disabled` → `platform=SERVICENOW, state=PENDING`-then-terminal is tempting
  but dishonest; use `TRIAGEMATE` + `DONE` with result "not posted — writeback disabled",
  and leave the authoritative claim to `data.writebackPosted` (FND-25).

The two real `addWorkNote` steps *are* `SERVICENOW` — they hit the platform.

---

## 6. Blast radius

**Changed (7 files)**

| File | Change |
|---|---|
| `orchestration/DiagnosisResult.java` | +`List<TraceStep> steps` component, +1 back-compat ctor (existing two delegate) |
| `orchestration/DiagnosisEngine.java` | +2-arg `diagnose`, 1-arg becomes a `default` |
| `orchestration/DeterministicDiagnosisEngine.java` | signature +`TraceSink`; 11 `trace.add` sites → `begin`/`done`/`note`, **same legacy strings** |
| `orchestration/DiagnosisOrchestrator.java` | creates the `TraceCollector`, passes it to `callWithTimeout`→`diagnose`, replaces 5 `result.trace().add` with sink calls, carries `steps` through the 3 result rebuilds (165, 177, 184) |
| `adk/.../AdkDiagnosisEngine.java` | signature +`TraceSink`; `beforeToolCallbackSync` emits `begin`/`denied`; **new** `afterToolCallbackSync` emits `done` |
| `static/index.html` | renders `data.steps` when present, falls back to `data.trace` blob when empty (so a stale/other client never breaks) |
| `docs/design-java/concepts/J7-*/README.md` | documents the step contract |

**New (5 files)**: `orchestration/trace/{TraceStep, TraceSink, TraceCollector, StepCatalog, Summaries}.java`
(`Summaries` may need to live under `src/main/adk` if it must reference ADK types — it
should not: it switches on *our* gateway model types, so keep it in `main/java`).

**Tests: zero existing assertions need to change.** All 10 trace assertions are
`startsWith`/`contains` over strings the collector still emits verbatim, and every test
that calls `diagnose(n)` keeps compiling via the `default` method. Engine constructors are
untouched. What must be *added*: a `TraceCollector` unit test (seq/state/duration
transitions), a `StepCatalog` test asserting `AdkDiagnosisEngine.ALLOWED_TOOLS ⊆ catalog`
(needs the set exposed package-private or the assertion duplicated under `src/adk-test`),
and one `BoundsCallbackTest`-adjacent check that a denied call produces `state=DENIED`.

**Risks**: (1) the `steps`/`trace` pair can drift if a future contributor calls
`trace().add` directly — mitigate by making the collector's string list
`Collections.unmodifiableList` *at the boundary* once the run ends, or at minimum a
javadoc `@implNote`; (2) `afterToolCallbackSync` is now load-bearing on the ADK path and
only exercised under `-Padk` + `FakeOpenAiServer`; (3) adding a record component to
`DiagnosisResult` changes the JSON payload shape — additive only, so the existing UI and
any programmatic caller are unaffected.

---

## 7. Recommendation, in one line

Add a `TraceStep` record + `TraceSink`/`TraceCollector` in `main/java`, pass the sink as
a **new second parameter** on `DiagnosisEngine.diagnose` with the old 1-arg form kept as
a `default`, keep `List<String> trace` byte-identical and emitted *by the collector*, and
derive ADK labels from `tool.name()` through a shared `StepCatalog` — resolving those
steps via the (now verified) `afterToolCallbackSync`. Reject trace parsing outright: FND-16
already litigated it in this repo.
