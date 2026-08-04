# Recommendation

For this application, use:

1. `POST /api/diagnosis-runs` → create/start work and return `202 Accepted` with a `runId`.
2. `GET /api/diagnosis-runs/{runId}/events` → `SseEmitter` stream opened with native `EventSource`.
3. A per-run, bounded append-only event log retained for a short TTL.
4. Monotonic SSE `id` values and replay using `Last-Event-ID`.
5. ADK `beforeToolCallbackSync` / `afterToolCallbackSync` / `onToolErrorCallbackSync` for actual tool execution edges.
6. `Event.functionCalls()` / `functionResponses()` for model-request/result observability, not as exact execution-timing callbacks.
7. Structured status events such as `STARTED`, `COMPLETED`, `DENIED`, and `FAILED`; do not present this as access to hidden model chain-of-thought.

This fits the existing synchronous POST in [DiagnosisController.java](/home/eugene/work/hackathon2026/src/main/java/com/company/triage/api/DiagnosisController.java:28), the virtual-thread execution in [DiagnosisOrchestrator.java](/home/eugene/work/hackathon2026/src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java:73), and the current ADK callback wiring in [AdkDiagnosisEngine.java](/home/eugene/work/hackathon2026/src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java:143).

# 1. Overview and implementation truth

## Spring MVC `SseEmitter`

`SseEmitter` is a Servlet-stack asynchronous response type. The controller request thread returns the emitter and exits; another thread may subsequently call `send()`, followed eventually by `complete()`. This exact “send from some other thread” pattern is documented by Spring. Individual Servlet response writes remain blocking, even though the overall request is asynchronous. [Spring MVC asynchronous requests](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)

Calling `send()` from your `Executors.newVirtualThreadPerTaskExecutor()` task is therefore valid. `SseEmitter`/`ResponseBodyEmitter` has an internal write lock, but your surrounding run state, replay buffer, event sequence allocation, and ADK callbacks must still be thread-safe.

### Lifecycle responsibilities

| Method/callback | Correct use |
|---|---|
| `complete()` | Normal application completion. Causes the final async dispatch and closes the request. |
| `completeWithError(ex)` | Application-detected producer failure. Once the response is committed, it cannot reliably change the HTTP status. |
| `onTimeout(...)` | Container-thread notification that the async request timed out. Remove the emitter/subscriber and release resources. |
| `onError(...)` | Container-thread notification of an async processing error. Remove the emitter/subscriber. |
| `onCompletion(...)` | Definitive cleanup hook. Runs for normal completion, timeout, and network error. Treat callbacks as idempotent because error/timeout may precede completion. |

If `send()` throws `IOException` because the client disconnected, Spring explicitly says not to call `complete()` or `completeWithError()` afterward. The container will generate the error notification and Spring will finish the async lifecycle. `AsyncRequestNotUsableException` is an `IOException` indicating that the response failed, received a container error, or has already completed. [Spring disconnect handling](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html), [`AsyncRequestNotUsableException`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/context/request/async/AsyncRequestNotUsableException.html)

Practical handling:

```java
try {
    emitter.send(SseEmitter.event()
            .id(Long.toString(event.id()))
            .name("trace")
            .data(event, MediaType.APPLICATION_JSON));
} catch (IOException | IllegalStateException disconnectedOrCompleted) {
    subscribers.remove(emitter);

    // Important: do not call completeWithError() after this write failure.
}
```

The Servlet API does not provide an immediate, portable “browser went away” notification. A disconnect is normally discovered on the next write, so send a comment heartbeat every 10–20 seconds:

```java
emitter.send(SseEmitter.event().comment("keepalive"));
```

Spring recommends periodic writes for exactly this reason. [`SseEventBuilder.comment`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.SseEventBuilder.html)

## Async timeout: the dangerous default

`spring.mvc.async.request-timeout` has no Spring Boot default value. If unset, Spring uses the underlying Servlet container’s default. [Boot 3.4 property reference](https://docs.spring.io/spring-boot/3.4/appendix/application-properties/index.html)

The Servlet default, and embedded Tomcat’s default, is 30 seconds. [Jakarta `AsyncContext`](https://jakarta.ee/specifications/servlet/6.0/apidocs/jakarta.servlet/jakarta/servlet/asynccontext), [Tomcat HTTP connector](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html)

That conflicts with this repository’s 90-second diagnosis deadline. Configure it explicitly:

```yaml
spring:
  mvc:
    async:
      request-timeout: 3m
```

Alternatively, pass an explicit timeout per emitter:

```java
SseEmitter emitter = new SseEmitter(180_000L);
```

A value of zero or less means no Servlet async timeout, but a finite value is safer for leak containment. Heartbeats and proxy idle timeouts are separate from the Servlet async timeout.

## Virtual threads

`SseEmitter` works with `spring.threads.virtual.enabled=true`. Boot uses virtual threads for its auto-configured async task executor and, where supported, embedded server request execution. [Boot task execution](https://docs.spring.io/spring-boot/3.4/reference/features/task-execution-and-scheduling.html), [Boot virtual threads](https://docs.spring.io/spring-boot/3.4/reference/features/spring-application.html#features.spring-application.virtual-threads)

Important distinctions:

- Your existing `Executors.newVirtualThreadPerTaskExecutor()` is already virtual; the Boot property does not alter or manage it.
- `SseEmitter.send()` runs on the thread that calls it.
- Virtual threads reduce the cost of blocking but do not provide backpressure, ordering, replay, or emitter cleanup.
- ADK callbacks may run on ADK/RxJava execution threads rather than the orchestration virtual thread. Make the progress sink thread-safe.
- MDC, security context, and other `ThreadLocal` state do not automatically transfer to your manually created executor.

## Proxy requirements

The response must have `Content-Type: text/event-stream`. Nginx response buffering is enabled by default and can turn a live stream into bursts or one final response. Disable it in Nginx:

```nginx
location /api/diagnosis-runs/ {
    proxy_pass http://localhost:8080;
    proxy_buffering off;
    proxy_read_timeout 5m;
}
```

Or send:

```http
X-Accel-Buffering: no
Cache-Control: no-cache, no-transform
Content-Type: text/event-stream
```

Nginx documents both `proxy_buffering off` and `X-Accel-Buffering: no`. [Nginx proxy buffering](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)

Test through the real proxy with:

```bash
curl -N -H 'Accept: text/event-stream' \
  http://localhost:8080/api/diagnosis-runs/RUN_ID/events
```

# 2. Options and approaches

| Approach | Advantages | Disadvantages |
|---|---|---|
| POST creates run, GET uses `EventSource` | Native reconnect, `Last-Event-ID`, simple vanilla JS, clean resource URLs | Requires replay to close the POST→GET race; two requests |
| POST response is the stream, consumed using `fetch()` | One request; POST body and custom headers supported; no initial attachment race | Manual stream/SSE parser, manual retry and deduplication, no automatic `Last-Event-ID` |
| Create → subscribe → explicit start handshake | No event can precede subscription | Three-state protocol, abandoned created runs, start idempotency required |
| WebSocket | Full duplex; multiplexing | Unnecessary for one-way progress, manual reconnect/replay, more server/client protocol code |
| Polling | Very simple and robust | Not truly live, repeated requests, less smooth UI |

The two-endpoint design is the best fit here because the start request is already naturally a POST and the trace is one-way.

## Solving the attachment race

A naïve implementation loses events emitted between:

```text
POST completes → browser parses JSON → EventSource GET connects
```

Normal solutions are:

- **Buffer/replay:** retain all events for the short run and replay them on GET. Recommended here.
- **SSE IDs plus `Last-Event-ID`:** required for reconnect replay. It does not by itself solve the initial connection, whose last ID is empty.
- **Create-then-subscribe:** delay work until the GET attaches. Avoids buffering but introduces abandoned runs and more state transitions.

For this bounded POC, retain the complete run event log—likely fewer than a few dozen events—until five minutes after termination. Add a hard cap such as 512 events and a maximum number of retained runs.

# 3. Concrete Spring design

## API shape

```java
public record StartRunRequest(String incidentNumber) {}

public record StartRunResponse(UUID runId, String eventsUrl) {}

public record TraceEvent(
        long id,
        String stepId,
        String phase,     // STARTED, COMPLETED, DENIED, FAILED
        String text,
        Instant timestamp,
        Object detail) {}
```

```java
@RestController
@RequestMapping("/api/diagnosis-runs")
final class DiagnosisRunController {

    private final DiagnosisRunService runs;

    DiagnosisRunController(DiagnosisRunService runs) {
        this.runs = runs;
    }

    @PostMapping
    ResponseEntity<StartRunResponse> start(@RequestBody StartRunRequest request) {
        String incident = request.incidentNumber().trim().toUpperCase();
        UUID id = runs.start(incident);

        return ResponseEntity.accepted().body(new StartRunResponse(
                id, "/api/diagnosis-runs/" + id + "/events"));
    }

    @GetMapping(
            value = "/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events(
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastId) {

        long after = parseLastId(lastId);
        SseEmitter emitter = runs.attach(runId, after); // Throw 404 before returning if unknown.

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private static long parseLastId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
```

## Minimal replayable run state

This version deliberately serializes small sends under one per-run monitor. That gives deterministic IDs and order. For a high-volume system, replace it with a per-run single-writer queue so one slow client cannot block other publishers.

```java
final class RunState {

    private static final int MAX_REPLAY = 512;
    private static final long EMITTER_TIMEOUT_MS = 180_000;

    private final Deque<TraceEvent> replay = new ArrayDeque<>();
    private final Set<SseEmitter> subscribers = new HashSet<>();

    private long nextId = 1;
    private boolean terminal;

    synchronized SseEmitter attach(long afterId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Runnable detach = () -> detach(emitter);

        emitter.onTimeout(detach);
        emitter.onCompletion(detach);
        emitter.onError(error -> detach.run());

        try {
            // Sets the browser's future reconnect delay and forces early bytes.
            emitter.send(SseEmitter.event()
                    .comment("connected")
                    .reconnectTime(2_000));

            for (TraceEvent event : replay) {
                if (event.id() > afterId) {
                    send(emitter, event);
                }
            }

            if (terminal) {
                emitter.complete();
            } else {
                subscribers.add(emitter);
            }
        } catch (IOException | IllegalStateException unusable) {
            // A failed send will be completed through the container lifecycle.
        }

        return emitter;
    }

    synchronized TraceEvent publish(
            String stepId, String phase, String text, Object detail) {

        if (terminal) {
            throw new IllegalStateException("run is already terminal");
        }

        TraceEvent event = new TraceEvent(
                nextId++, stepId, phase, text, Instant.now(), detail);

        replay.addLast(event);
        while (replay.size() > MAX_REPLAY) {
            replay.removeFirst();
        }

        for (Iterator<SseEmitter> it = subscribers.iterator(); it.hasNext();) {
            SseEmitter emitter = it.next();
            try {
                send(emitter, event);
            } catch (IOException | IllegalStateException unusable) {
                it.remove();
                // Do not call completeWithError after a write failure.
            }
        }

        return event;
    }

    synchronized void heartbeat() {
        for (Iterator<SseEmitter> it = subscribers.iterator(); it.hasNext();) {
            try {
                it.next().send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException unusable) {
                it.remove();
            }
        }
    }

    synchronized void finish() {
        terminal = true;
        subscribers.forEach(SseEmitter::complete);
        subscribers.clear();
    }

    private synchronized void detach(SseEmitter emitter) {
        subscribers.remove(emitter);
    }

    private static void send(SseEmitter emitter, TraceEvent event)
            throws IOException {

        emitter.send(SseEmitter.event()
                .id(Long.toString(event.id()))
                .name("trace")
                .data(event, MediaType.APPLICATION_JSON));
    }
}
```

For a diagnosis failure, prefer sending a typed terminal event and then completing normally:

```java
state.publish("run", "FAILED", safeUserMessage(error), null);
state.finish();
```

That lets the browser render an error. `completeWithError(error)` is appropriate for a server-side streaming failure where you cannot or do not want to send a protocol-level error event. It cannot reliably turn an already committed stream into a JSON `500`.

Do not delete `RunState` when one emitter disconnects. The diagnosis currently performs automatic ServiceNow writeback, so it should normally continue without a viewer. Keep the state for reconnect and expire it separately after a terminal TTL.

# 4. Browser `EventSource` facts

Native `EventSource`:

- Takes a URL and an optional `withCredentials` flag.
- Does not accept an HTTP method, body, arbitrary headers, or an `AbortSignal`; it therefore cannot send a POST body.
- Reconnects after an interrupted stream unless `close()` is called or the server returns a fatal response such as `204`.
- Updates its reconnect delay from an SSE `retry: milliseconds` field.
- Stores the most recent SSE `id` and sends it as `Last-Event-ID` when reconnecting.
- Requires a successful `200` response with `Content-Type: text/event-stream`.
- Under HTTP/1.1, browsers commonly limit SSE to roughly six connections per browser and origin across tabs. HTTP/2 uses negotiated stream limits instead. [HTML EventSource specification](https://html.spec.whatwg.org/multipage/server-sent-events.html), [MDN SSE guide](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)

Vanilla client:

```js
async function startDiagnosis(incidentNumber) {
  const response = await fetch("/api/diagnosis-runs", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ incidentNumber })
  });

  if (!response.ok) {
    throw new Error(`Start failed: HTTP ${response.status}`);
  }

  const { eventsUrl } = await response.json();
  const source = new EventSource(eventsUrl);

  source.addEventListener("trace", event => {
    const update = JSON.parse(event.data);
    updateStep(update);

    if (update.phase === "FAILED") {
      source.close();
      showRunError(update.text);
    }

    if (update.stepId === "run" && update.phase === "COMPLETED") {
      source.close(); // Prevent reconnect after intentional server EOF.
    }
  });

  source.onerror = () => {
    // CONNECTING usually means EventSource is already retrying.
    if (source.readyState === EventSource.CLOSED) {
      showRunError("The trace stream was closed.");
    }
  };

  return source;
}
```

When POST streaming is required instead, use:

```js
const response = await fetch("/api/diagnose", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload),
  signal: abortController.signal
});

const reader = response.body
  .pipeThrough(new TextDecoderStream())
  .getReader();

for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  parseIncrementalFrames(value);
}
```

That supports POST semantics but requires your own chunk framing, partial-line buffering, retry, IDs, and deduplication. [`ReadableStream.getReader()`](https://developer.mozilla.org/en-US/docs/Web/API/ReadableStream/getReader), [streaming Fetch responses](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch)

# 5. Google ADK Java 1.7.0

## Exact facts verified against 1.7.0

I inspected the cached artifact:

```text
com.google.adk:google-adk:1.7.0
SHA-256:
a27b6ba58987e5e616eaf318cfff0e72629d9e5df0b8ced8a318dc8278181af7
```

Verified public surface:

- `Runner.runAsync(...)` returns `Flowable<Event>`.
- `Event.functionCalls()` returns `ImmutableList<FunctionCall>`.
- `Event.functionResponses()` returns `ImmutableList<FunctionResponse>`.
- `Event` also exposes `id()`, `invocationId()`, `author()`, `content()`, `actions()`, `partial()`, `turnComplete()`, `errorCode()`, `errorMessage()`, `timestamp()`, and related metadata.
- `FunctionCall` has `id()`, `name()`, `args()`, `partialArgs()`, and `willContinue()`.
- `FunctionResponse` has `id()`, `name()`, `response()`, `parts()`, `willContinue()`, and `scheduling()`.
- `ToolContext.functionCallId()` returns `Optional<String>`.
- The builder has `beforeToolCallback`, `beforeToolCallbackSync`, `afterToolCallback`, `afterToolCallbackSync`, `onToolErrorCallback`, and `onToolErrorCallbackSync`.
- The synchronous callbacks return `Optional<Map<String,Object>>`.
- The asynchronous versions return `Maybe<Map<String,Object>>`.

The public 1.7.0 Javadocs confirm the runner and event methods. [`Runner.runAsync`](https://adk.dev/api-reference/java/com/google/adk/runner/Runner.html), [`Event`](https://adk.dev/api-reference/java/com/google/adk/events/Event.html), [`LlmAgent.Builder`](https://adk.dev/api-reference/java/com/google/adk/agents/LlmAgent.Builder.html)

## Does the Flowable expose tool START events?

Precise answer: **it exposes a tool-call-request event before completion, but there is no dedicated `ToolStartedEvent` or explicit start-phase field on `Event`.**

In the 1.7.0 implementation inspected from bytecode:

1. The model response containing one or more `FunctionCall` parts is emitted.
2. ADK processes those calls.
3. Later, a response event containing one or more `FunctionResponse` parts is emitted.

Therefore:

```java
if (!event.functionCalls().isEmpty()) {
    // Model requested tool execution.
}

if (!event.functionResponses().isEmpty()) {
    // Tool result(s) are represented here.
}
```

But a function-call event means “the model requested these calls,” not precisely “the first instruction of each tool body has now begun.” One event may contain several calls, and ADK may execute them in parallel. Completion responses may likewise be merged.

For exact execution edges, callbacks are better:

- `beforeToolCallback` is the pre-execution edge.
- `afterToolCallback` is the successful-result edge.
- `onToolErrorCallback` is required for the failure edge; `afterToolCallback` should not be treated as a `finally` hook.

The callback return values are not merely acknowledgements:

- Before callback: non-empty result overrides/short-circuits tool execution; empty continues.
- After callback: non-empty result replaces/processes the result; empty preserves the original.
- Error callback: non-empty result converts/overrides the failure; empty allows the error to continue.

These semantics are documented in the 1.7.0 callback API. [`BeforeToolCallback`](https://adk.dev/api-reference/java/com/google/adk/agents/Callbacks.BeforeToolCallback.html), [`AfterToolCallback`](https://adk.dev/api-reference/java/com/google/adk/agents/Callbacks.AfterToolCallback.html), [`OnToolErrorCallback`](https://adk.dev/api-reference/java/com/google/adk/agents/Callbacks.OnToolErrorCallback.html)

## Recommended callback integration

Your current code only registers `beforeToolCallbackSync`, so it records a call but never replaces that line when the tool finishes. Add all three edges:

```java
Set<String> deniedCalls = ConcurrentHashMap.newKeySet();

LlmAgent agent = LlmAgent.builder()
        // name, model, instruction, tools...
        .beforeToolCallbackSync((invocation, tool, args, toolCtx) -> {
            String callId = toolCtx.functionCallId()
                    .orElseThrow(() -> new IllegalStateException(
                            "ADK tool callback has no function-call id"));

            if (!bounds.allow(tool.name())) {
                String reason = bounds.denialReason(tool.name());
                deniedCalls.add(callId);
                progress.denied(callId, tool.name(), reason);

                return Optional.of(Map.of(
                        "error", reason + "; produce the report from available evidence"));
            }

            progress.started(callId, tool.name(), args);
            return Optional.empty(); // Continue into the real tool.
        })
        .afterToolCallbackSync((invocation, tool, args, toolCtx, response) -> {
            String callId = toolCtx.functionCallId().orElseThrow();

            // A before-callback override may still flow through result processing.
            if (!deniedCalls.remove(callId)) {
                progress.completed(callId, tool.name(), summarize(response));
            }

            return Optional.empty(); // Preserve the real result.
        })
        .onToolErrorCallbackSync((invocation, tool, args, toolCtx, error) -> {
            String callId = toolCtx.functionCallId().orElseThrow();
            progress.failed(callId, tool.name(), safeMessage(error));

            return Optional.empty(); // Do not convert the error into a result.
        })
        .build();
```

Keep the `ProgressSink` concurrent. ADK’s execution thread is not part of the public timing contract, and parallel tool calls can produce overlapping callbacks.

You can also inspect intermediate `Event`s in the existing `blockingForEach` at [AdkDiagnosisEngine.java](/home/eugene/work/hackathon2026/src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java:223), but avoid emitting duplicate START/COMPLETED UI events from both the callbacks and the Flowable. Use callbacks for timing and the Flowable for model output, metadata, and audit validation.

# 6. Accessible live step list and CSS motion

Routine progress is `polite`, not `assertive`. `role="status"` already implies `aria-live="polite"` and `aria-atomic="true"`. Assertive regions may interrupt or clear queued speech, so reserve a separate `role="alert"` for a genuinely urgent fatal condition. [WAI-ARIA status role](https://www.w3.org/TR/wai-aria/), [WCAG status-message technique](https://www.w3.org/WAI/WCAG21/Techniques/aria/ARIA22)

For reliable replacement-in-place behavior, keep the visible semantic list separate from a single atomic announcer:

```html
<section id="trace-region"
         aria-labelledby="trace-heading"
         aria-busy="false">
  <h2 id="trace-heading">Investigation progress</h2>
  <ol id="trace-list"></ol>
</section>

<p id="trace-status"
   class="sr-only"
   role="status"
   aria-live="polite"
   aria-atomic="true"></p>

<p id="run-alert"
   class="sr-only"
   role="alert"></p>
```

```js
const rows = new Map();

function updateStep(update) {
  const region = document.getElementById("trace-region");
  const list = document.getElementById("trace-list");
  const announcer = document.getElementById("trace-status");

  // aria-busy is useful for grouping a DOM batch. Do not leave the live
  // announcer busy for the entire run or intermediate announcements may wait.
  region.setAttribute("aria-busy", "true");

  let row = rows.get(update.stepId);
  if (!row) {
    row = document.createElement("li");
    row.className = "step";
    row.innerHTML =
      '<span class="step-marker" aria-hidden="true"></span>' +
      '<span class="step-text"></span>';
    rows.set(update.stepId, row);
    list.append(row);
  }

  row.dataset.state = update.phase.toLowerCase();
  row.querySelector(".step-text").textContent = update.text;

  region.setAttribute("aria-busy", "false");

  // Replacing this atomic status text announces the complete new line.
  announcer.textContent = update.text;
}
```

`aria-busy="true"` tells assistive technology that a region is being modified and that it may wait until the update is complete. It should bracket a batch, not remain true for the entire diagnosis if intermediate announcements matter. [WAI-ARIA `aria-busy`](https://www.w3.org/TR/wai-aria/#aria-busy)

CSS-only shimmer and pulse:

```css
.step {
  position: relative;
  margin: .4rem 0;
  padding: .65rem .8rem .65rem 2rem;
  border-left: 3px solid #50627e;
  border-radius: .35rem;
}

.step-marker {
  position: absolute;
  left: .7rem;
  top: .9rem;
  width: .55rem;
  height: .55rem;
  border-radius: 50%;
  background: #93a1b5;
}

.step[data-state="started"] {
  border-left-color: #5aa9ff;
  background-image:
    linear-gradient(100deg,
      transparent 20%,
      rgb(90 169 255 / 14%) 45%,
      transparent 70%);
  background-size: 220% 100%;
  animation: trace-shimmer 1.5s linear infinite;
}

.step[data-state="started"] .step-marker {
  background: #5aa9ff;
  animation: trace-pulse 1.1s ease-in-out infinite;
}

.step[data-state="completed"] {
  border-left-color: #3ecf8e;
}

.step[data-state="completed"] .step-marker {
  background: #3ecf8e;
}

.step[data-state="failed"],
.step[data-state="denied"] {
  border-left-color: #ffb454;
}

@keyframes trace-shimmer {
  to { background-position: -220% 0; }
}

@keyframes trace-pulse {
  50% { opacity: .35; }
}

/* Motion is decorative; the static marker, text, and border still convey state. */
@media (prefers-reduced-motion: reduce) {
  .step[data-state="started"],
  .step[data-state="started"] .step-marker {
    animation: none;
  }

  .step[data-state="started"] {
    background-image: none;
  }
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}
```

The text itself should include state—“Searching logs…” followed by “Searched logs — 12 relevant lines”—so animation and color are never the only signals. `prefers-reduced-motion` is specifically intended to remove or reduce non-essential animation. [MDN `prefers-reduced-motion`](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/%40media/prefers-reduced-motion)

# 7. Key references

- [Spring MVC asynchronous requests and SSE](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)
- [`SseEmitter` API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html)
- [`ResponseBodyEmitter` lifecycle API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseBodyEmitter.html)
- [Spring Boot 3.4 application properties](https://docs.spring.io/spring-boot/3.4/appendix/application-properties/index.html)
- [HTML EventSource specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [MDN server-sent events guide](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [Nginx proxy buffering](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- [ADK Java 1.7.0 `Event`](https://adk.dev/api-reference/java/com/google/adk/events/Event.html)
- [ADK Java 1.7.0 `Runner`](https://adk.dev/api-reference/java/com/google/adk/runner/Runner.html)
- [ADK event interpretation guide](https://adk-labs.github.io/adk-docs/events/)
- [WAI-ARIA 1.2](https://www.w3.org/TR/wai-aria/)
- [WCAG status messages](https://www.w3.org/WAI/WCAG21/Understanding/status-messages)