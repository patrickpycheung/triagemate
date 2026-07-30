# Exploration A — Transport

**Bias:** backend transport mechanics. How do per-step trace events get from a running
diagnosis to the browser *as they happen*?

Scope note: I do **not** address visual design (exploration B) or whether animating is
honest (exploration C). But transport analysis forces one honesty-adjacent finding into
the open — see A-0 — because it changes which transport is even worth building.

---

## What I verified in the code (beyond the brief's given facts)

| # | Verified | Where |
|---|---|---|
| V-1 | `pom.xml` has exactly `spring-boot-starter-web`, `-validation`, `-test`. **No WebFlux, no `spring-boot-starter-websocket`.** Servlet stack (embedded Tomcat), Java 21. ADK deps are inside the `adk` profile only. | pom.xml:30-38, 58-104 |
| V-2 | `spring.threads.virtual` is **not** set. Request threads are ordinary Tomcat pool threads (200 by default). Only the orchestrator's own `Executors.newVirtualThreadPerTaskExecutor()` uses virtual threads. | application.yml (absent), `DiagnosisOrchestrator:76` |
| V-3 | **No Java test hits `POST /api/diagnose/{n}`.** No MockMvc/WebTestClient test on `DiagnosisController` anywhere in `src/test`. So changing the endpoint's media type breaks *only* `index.html`, not the 34/50 green suites. | grep over `src/test` |
| V-4 | Each engine creates its trace list **locally and privately**: `List<String> trace = new ArrayList<>();` (`DeterministicDiagnosisEngine:63`, `AdkDiagnosisEngine.diagnose`). Nothing outside the engine can observe it before `diagnose()` returns. This is the single mechanical blocker for *every* live transport. |  |
| V-5 | The engine call runs on a **different thread** from the HTTP request thread (`callWithTimeout` → `engineExecutor.submit`). A `ThreadLocal` set in the controller therefore does **not** reach the engine. An emitter must be **captured by the lambda**, i.e. passed as an argument. | `DiagnosisOrchestrator:190` |
| V-6 | **Neither engine emits a "step started" event today.** Deterministic adds one line *after* each step, containing the result (`servicenow.getIncident(...) → CI=..., env=...`). ADK's `beforeToolCallback` adds `adk tool call: <name>` *before* the tool runs and there is **no `afterToolCallback`** — so on the ADK path we have a start with no result, and on the deterministic path a result with no start. | `DeterministicDiagnosisEngine:68-177`, `AdkDiagnosisEngine.diagnoseBound` |
| V-7 | The orchestrator appends 1-3 more trace lines *after* the engine returns (writeback / writeback-disabled / partial-failure), and `diagnoseWithFallback` **prepends** the degradation line at index 0. So the trace is not append-only in run order — a streaming channel cannot assume "event N is final once sent". | `DiagnosisOrchestrator:139-186` |
| V-8 | FND-31: the second concurrent caller for the same incident gets `existing.get()` — a `CompletableFuture` of the *final* result. There is **no fan-out point** for a second live subscriber. | `DiagnosisOrchestrator:98-127` |

**V-6 and V-7 are the most consequential findings and they are transport-independent.**
The operator's ask ("checking… → resolves in place") needs a *two-phase* event per step.
The code emits one phase, and the phases we do have are inconsistent between engines. No
choice of SSE/WebSocket/polling gives you that. **Emission is the hard part; transport is
the easy part.** Any option below carries the same ~30-60 line emission change; the
options differ only in the last hop.

---

## A-0 — The uncomfortable arithmetic (drives the recommendation)

| Engine | Wall clock | Steps | Time per step | Live transport buys… |
|---|---|---|---|---|
| Deterministic (default, D2, the guaranteed path) | **2-19 ms** | 11 | ~0.2-1.7 ms | **Nothing.** The run finishes before the browser paints one frame (16.7 ms). A 250 ms poll, an SSE flush, a WebSocket frame — all arrive *after* the run is over. |
| ADK (`-Padk` + live proxy) | seconds to 90 s | up to 10 tool calls | ~1-10 s | Real, substantial value. |

So: **on the path the demo is guaranteed to fall back to, no transport works at all** —
only client-side replay does. And on the ADK path, replay *also* works (it just shows
everything at the end). This means replay is the **only** universal renderer, and any real
transport is an *upgrade* for one of the two paths.

That reframes the question from "which transport?" to "**is the ADK-only upgrade worth
its complexity, given a replay renderer must be built regardless?**"

---

## Common prerequisite: how the engine emits without polluting domain code

The minimal-diff mechanism, given V-4/V-5 — and I like this one because it changes **zero
of the 11+ `trace.add(...)` call sites**:

```java
// new, orchestration package
public interface TraceSink {
    void step(String line);                       // no-op default impl for K1/poller
    static TraceSink noop() { return l -> {}; }
}

// A List that publishes as it accumulates. Engines keep using trace.add(...).
final class EmittingTraceList extends ArrayList<String> {
    private final TraceSink sink;
    EmittingTraceList(TraceSink sink) { this.sink = sink; }
    @Override public boolean add(String s) { boolean r = super.add(s); sink.step(s); return r; }
    // NOTE: must also override add(int, String) — FND-7 prepends at index 0 (V-7).
}
```

`DiagnosisEngine` gains a **default** overload so neither engine is forced to change and
`IncidentPoller` is untouched:

```java
default DiagnosisResult diagnose(String incidentNumber, TraceSink sink) { return diagnose(incidentNumber); }
```

Each engine's one line `new ArrayList<>()` → `new EmittingTraceList(sink)`. The orchestrator
captures the sink in the submitted lambda (V-5) and passes `TraceSink.noop()` from the poller.

Rejected alternatives:
- **`ApplicationEventPublisher`** — Spring events are synchronous-by-default on the
  publishing thread and give you a global bus you then have to filter by incident. More
  machinery, no benefit, and it puts a Spring dependency into the engine's inner loop.
- **`ThreadLocal`** — doesn't cross the virtual-thread boundary (V-5). Would need
  `ScopedValue`/`InheritableThreadLocal` plumbing. Don't.
- **Changing `DiagnosisResult`** — no; C-1 says don't touch the contracts FND-16/25 fixed.

This prerequisite is ~40 lines and, notably, is where the risk actually lives: `add()`
now has a side effect that can throw (a dead SSE emitter) **inside the diagnosis loop**.
Every option below must wrap the sink so a transport failure cannot fail the diagnosis.
I would make `EmittingTraceList.add` swallow-and-log unconditionally.

---

## Options

### 1. SSE (`SseEmitter`)

- **Deps:** none. `SseEmitter` is `spring-web`, works on the servlet stack via Tomcat
  async (`AsyncContext`). No WebFlux, no `Flux` — if you reached for `Flux` you'd be adding
  reactor + WebFlux for one endpoint, which is unjustifiable here.
- **Shape:** `GET /api/diagnose/{n}/stream` returning `SseEmitter`, plus an incident-keyed
  registry (`ConcurrentHashMap<String, List<SseEmitter>>`) that the `TraceSink` fans out to.
  Client: `new EventSource(...)` — plain JS, no build step. ✅
- **Ordering problem:** the browser must open the stream *before* the POST starts, or it
  misses the early steps. With a 2-19 ms deterministic run that race is unwinnable; even
  on ADK you'd want the client to open the stream, then fire the POST — two requests whose
  interleaving you now own.
- **Lifecycle cost (the real one):** `SseEmitter.send()` is **not** documented as safe for
  concurrent use and needs external synchronization; you must handle `onTimeout`,
  `onCompletion`, `onError`, set an explicit infinite/long timeout (default `spring.mvc.async.request-timeout`
  would kill a 90 s run), and `complete()` in a finally. Plus: when FND-15's
  `future.cancel(true)` fires, the engine thread may keep calling `add()` into a completed
  emitter (V-5 best-effort cancellation) — guaranteed `IllegalStateException` unless guarded.
- **FND-31 / two browsers:** solvable (registry is a list), but a late joiner sees only
  events from its join point unless you also keep a replay buffer — at which point you have
  built the polling option's data structure anyway.
- **Degradation (FND-7):** works, but note V-7 — the degradation line is *prepended*. Over a
  stream you'd have already sent the ADK steps, then the deterministic engine's 11 steps
  arrive. The stream must send an explicit `event: degraded` marker and the client must
  *reset* the step list, not append. This is genuine design work, not a wire-format detail.

**Verdict: the textbook answer, and about 3× the code of the alternatives.**

### 2. WebSocket (`spring-boot-starter-websocket`)

- **New dependency**, new config class, new handler, session bookkeeping, and you get
  bidirectionality you have **zero** use for (the browser sends nothing after "Diagnose").
- Everything hard about SSE (registry, late joiners, lifecycle, degradation reset) is still
  hard, plus connection upgrade. STOMP/SockJS would be worse (SockJS's fallbacks want a CDN
  client → violates the offline constraint unless vendored).
- **Verdict: over-engineering. Reject.** No requirement here is one-way-insufficient.

### 3. Client polling a progress endpoint

- **Deps:** none. New endpoint `GET /api/diagnose/{n}/progress` → `{steps: [...], done: bool, engine: ...}`
  reading a `ConcurrentHashMap<String, CopyOnWriteArrayList<String>>` the `TraceSink` writes to.
- **Client:** `setInterval` + `fetch` while the POST promise is unresolved; clear on resolve.
  ~15 lines of JS, no `EventSource`, no stream parsing. ✅ No build step.
- **Concurrency:** the POST holds one Tomcat thread for up to 90 s; the polls are separate
  requests on separate connections. 200-thread pool, one presenter, one browser — a non-issue
  at demo scale. Browser 6-connection limit: non-issue.
- **Two browsers / FND-31:** **best of all options.** The buffer is keyed by incident and
  shared, so a second viewer — or one that joins mid-run, or reloads — gets the *whole*
  history on its first poll. No registry, no fan-out, no late-joiner problem. FND-31's
  coalescing is naturally correct because the progress data is per-incident, exactly like
  the coalescing map.
- **Degradation:** trivially correct — the client re-reads the *current full list* each
  poll, so V-7's prepend and the FND-7 restart just... show up. No reset protocol needed.
  **This is a real structural advantage over streaming, not a tie-breaker.**
- **Latency:** 250-500 ms granularity. Against 1-10 s LLM steps, imperceptible. Against a
  19 ms run, irrelevant (see A-0).
- **Cleanup:** must evict the buffer (on completion + a TTL) or it leaks per incident. Same
  bounded-map hygiene the poller's `completed-cap` already models.
- **Verdict: ~60 lines total, no new failure modes, correct under every concurrency case
  the codebase already worries about.**

### 4. No transport — client-side replay

- **Deps/backend changes: zero.** Response is unchanged; `render()` walks `data.trace` on a
  timer instead of `join('\n')`.
- Works identically on **both** engines. It is the only thing that works on the 2-19 ms path.
- Cost: the timing shown is **not** the real timing. That is exploration C's question, not
  mine. Transport-wise I only note: it requires no per-step timestamps to exist, and *adding*
  real per-step timestamps to the trace would let a replay be honest (see "to verify").
- **Verdict: mandatory floor. Whatever else is chosen, this must exist for D2.**

### 5. Streaming JSON / chunked NDJSON from the POST itself

The option I'd have picked if not for one flaw. `ResponseBodyEmitter`/`StreamingResponseBody`
from the existing POST, `Content-Type: application/x-ndjson`: one line per step, **final line
is the complete `DiagnosisResult`**. Client uses `res.body.getReader()` — plain fetch, no build
step, no `EventSource`, no second endpoint, **no ordering race** (the stream *is* the POST, so
you cannot miss an early step), and V-3 says no test breaks.

Flaw: it makes the *only* API endpoint non-JSON, so any programmatic consumer (and the
current `await res.json()`) breaks, and it inherits SSE's whole async-lifecycle burden
(timeouts, thread-safety, cancellation) while *still* not solving the second-viewer case
(a second browser can't attach to someone else's POST). Also: HTTP-level buffering is a
classic silent failure — on localhost it's fine, but it's an unverified assumption.

**Verdict: elegant, and strictly worse than polling once you count the second viewer and
the media-type break.** Keep as runner-up.

### 6. Long-polling
`GET /progress?since=N` blocking up to ~10 s via `DeferredResult`. Strictly more complexity
than option 3 to buy latency that A-0 says is worthless here. **Reject.**

---

## Comparison

| | New deps | Backend LOC (est.) | Frontend build step | Works on 2-19 ms engine | 2nd viewer / mid-run join | FND-7 degradation | Async lifecycle risk | Hackathon fit |
|---|---|---|---|---|---|---|---|---|
| 1 SSE | none | ~120 | no (`EventSource`) | **no** | needs registry + replay buffer | needs explicit reset protocol | **high** (timeout, concurrent send, cancel-after-complete) | medium |
| 2 WebSocket | **yes** | ~180 | no | **no** | needs session registry | same as SSE | high | **poor** |
| 3 **Polling** | none | **~60** | no (`fetch`) | **no** (replay covers it) | **free & correct** | **free** (re-reads full list) | **none** | **best** |
| 4 Replay only | none | **0** | no | **yes** | n/a | already handled by `render()` | none | best |
| 5 NDJSON stream | none | ~90 | no (`getReader`) | **no** | **no** | needs reset protocol | high | medium |
| 6 Long-poll | none | ~90 | no | no | free | free | medium (`DeferredResult`) | poor |

---

## Recommendation

**Build 4 + 3, in that order, sharing one renderer.**

1. **Ship the replay renderer first (option 4, zero backend change).** It is the only thing
   that works on the default deterministic engine (A-0), it is the fallback path's UI, and
   it is on the critical path for the demo whatever else happens. If time runs out here,
   the demo still works.
2. **Then add polling (option 3) as a live feeder for the same renderer.** ~60 lines, no
   dependencies, no async-servlet lifecycle, and — the part I did not expect going in —
   *structurally simpler than streaming for this codebase specifically*, because a
   per-incident shared buffer re-read in full on every poll is automatically correct for
   FND-31 coalescing, mid-run joiners, page reloads, and FND-7's index-0 prepend (V-7).
   Every streaming option needs bespoke protocol work for each of those four cases.
3. **Reject SSE and WebSocket for this project.** Not because streaming is wrong in general
   — because here it costs 2-3× the code, adds three new failure modes (async request
   timeout vs. the 90 s engine timeout, concurrent `send()`, emitting after
   `future.cancel(true)`), and its one advantage (sub-100 ms latency) is worth nothing
   against 1-10 s LLM steps and worth negative on a 19 ms run. Picking SSE here would be
   choosing the impressive answer over the correct one.

**But the headline is V-6/V-7, not the transport.** The operator's "checking… → resolves in
place" needs a *start* event and a *complete* event per step. The code has one event per
step, and on the ADK path that event carries no result. So the first real work item is
`afterToolCallback` on the ADK agent plus paired start/finish emission in the deterministic
engine — **and that work is identical no matter which transport wins.** Do not let the
transport debate consume the budget that belongs here.

---

## What I could not verify from reading code — spike list

1. **[medium confidence, unverified]** That `SseEmitter.send()` from the orchestrator's
   virtual thread while the Tomcat request thread has returned behaves correctly under
   Boot 3.4.3 / Tomcat 10.1. I'm relying on general knowledge of MVC async, not a run.
   Moot if polling wins.
2. **[unverified]** Whether `spring.mvc.async.request-timeout`'s default would kill a 90 s
   SSE stream. I believe the Boot default is "no timeout" (container default) but did not
   confirm — again moot under polling.
3. **[unverified, must spike]** Real per-step latency on the ADK path against a
   Copilot-served model. `timeout-ms: 90000` is explicitly documented in application.yml as
   *"a safer guess, not a measurement"* pending spike C2. **If real steps land at 200-400 ms
   rather than seconds, even the ADK path becomes barely animatable and option 4 alone wins.**
   This single measurement can invalidate the case for *any* transport. It is the highest-value
   spike on this list.
4. **[unverified]** Whether `ArrayList` subclassing catches every mutation path — I confirmed
   `add(String)` and `add(int, String)` are used (V-7), but not that nothing does
   `addAll`/`set`/iterator-remove on a trace. A grep of all `result.trace()` / `trace.`
   usages across the tree would settle it in a minute.
5. **[unverified]** Whether ADK 1.7.0 exposes `afterToolCallbackSync` with the tool *result*
   in a usable shape. `beforeToolCallbackSync` is confirmed in use; the "after" side is my
   assumption from symmetry. **This gates the whole "resolves in place" behaviour on the ADK
   path** and should be spiked against the real ADK jar.
6. **[guess]** That the deterministic engine can be split into paired start/finish emissions
   without restructuring `diagnose()`. It reads as a straight-line method with clear step
   boundaries, so I rate this likely — but I did not write the diff.
7. **[unverified]** Localhost chunked-transfer flush behaviour for option 5. Only matters if
   NDJSON is revived.
