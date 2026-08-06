package com.company.triage.orchestration;

import com.company.triage.config.ConnectorModeProvider;
import com.company.triage.config.TriageProperties;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.orchestration.trace.InMemoryRunTraceRegistry;
import com.company.triage.orchestration.trace.RunTraceRegistry;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns a diagnosis run (J1): delegates to the active engine (J2), then — with no
 * human in the loop — automatically posts the advisory diagnosis back to ServiceNow
 * as TWO comments (J5): the sources first, then the first-pass diagnosis. It only
 * comments; it never reassigns, closes, or re-prioritises the ticket (J8).
 *
 * <p><b>Degrades, never crashes (FND-7).</b> If the active engine is the live ADK
 * agent and it fails to converge — most concretely {@code
 * LlmCallsLimitExceededException} tripping the J8 tool-call/LLM-call backstop, but
 * also any other model/proxy/network failure — this falls back to {@link
 * DeterministicDiagnosisEngine}, which has no LLM/network dependency and cannot fail
 * the same way. The fallback is disclosed in the trace (J8/J7), not hidden. When the
 * active engine already IS the deterministic one, there is nothing to fall back to,
 * so its exceptions propagate normally — a bug there should surface as a bug, not be
 * silently swallowed by "falling back" to itself.
 *
 * <p><b>Wall-clock timeout (FND-15).</b> Every {@code engine.diagnose(...)} call — on
 * EITHER engine, since the deterministic path also makes real HTTP calls once
 * {@code triage.connectors.*=real} — runs on a virtual thread bounded by {@code
 * triage.orchestrator.timeout-ms}. A hung gateway (network partition, a proxy that
 * accepts a connection and never responds) previously hung the request forever,
 * including on the K1 poller's single scheduler thread, where nobody would notice a
 * stuck run. A timeout on the ADK engine feeds the same FND-7 fallback path as any
 * other failure; a timeout on the deterministic engine propagates, per the policy
 * above. Cancellation of the underlying call is best-effort (interrupting a blocked
 * HTTP call does not always abort it immediately) — the orchestrator recovers either
 * way; the leaked thread does not.
 *
 * <p><b>Concurrent-diagnosis coalescing (FND-31).</b> {@code DiagnosisController} (the
 * manual K3 trigger) and {@code IncidentPoller} (the automatic K1 trigger) both call
 * {@link #run(String)} directly — this is the ONE place their calls meet. Without a
 * guard here, a manual trigger fired while the poller is mid-run on the same incident
 * (exactly the demo shape: polling on, presenter also clicks) would run two full
 * diagnoses and post FOUR advisory comments (or two, plus a duplicate the real
 * gateway doesn't catch if a prior write already landed — see the idempotency check
 * in {@code RealServiceNowGateway}, FND-14). Concurrent calls for the SAME incident
 * number now coalesce: the second caller waits for the first's result instead of
 * starting a duplicate run, and only the first performs the ServiceNow write. This is
 * deliberately narrower than {@code IncidentPoller}'s own in-flight/completed
 * bookkeeping, which solves a different problem (don't re-poll something already
 * handled, across scheduler ticks over time) — the two are complementary, not
 * redundant.
 */
@Service
public class DiagnosisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisOrchestrator.class);

    private final DiagnosisEngine engine;
    private final DiagnosisEngine fallbackEngine;
    private final ServiceNowGateway serviceNow;
    private final TriageProperties props;
    private final RunTraceRegistry runTraceRegistry;
    private final Map<String, String> connectors;
    private final ExecutorService engineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * FND-31: coalesces concurrent runs for the same incident number.
     *
     * <p><b>J16/RTR-2: the entry carries the run's {@code runId}, not just its future.</b>
     * A waiter needs to alias its own trace buffer onto the run it is about to block on, and
     * the only way to name that run is its {@code runId}. Resolving it through a separate
     * incident→runId index in the registry was a second source of truth racing this map — a
     * waiter could resolve to a different run than the one it then awaited. Publishing both
     * facts as ONE map value makes that unrepresentable: whatever {@code putIfAbsent} hands
     * back is, by construction, the future being awaited AND the runId being aliased to.
     */
    private final ConcurrentHashMap<String, InFlightRun> inFlight = new ConcurrentHashMap<>();

    /** The canonical in-flight run for an incident: what a waiter blocks on, and the
     *  {@code runId} whose live buffer it should watch while it waits (J16/RTR-2). */
    record InFlightRun(CompletableFuture<DiagnosisResult> future, String runId) {}

    /**
     * Test/back-compat convenience: no {@link RunTraceRegistry} wired in gets a private
     * {@link InMemoryRunTraceRegistry} nothing else can poll. Since J16/RTR-1 every run
     * registers a buffer, so this can no longer be "no registry at all" — the run needs a
     * collector either way.
     */
    public DiagnosisOrchestrator(DiagnosisEngine engine,
                                 @Qualifier("deterministicDiagnosisEngine") DiagnosisEngine fallbackEngine,
                                 ServiceNowGateway serviceNow,
                                 TriageProperties props) {
        this(engine, fallbackEngine, serviceNow, props, new InMemoryRunTraceRegistry(props));
    }

    /**
     * Test/back-compat convenience: no {@link ConnectorModeProvider} wired in defaults every
     * connector to {@code "mock"} (its own no-arg constructor) — accurate for these
     * hand-built-engine unit-test call sites, which never touch a real connector either way.
     */
    public DiagnosisOrchestrator(DiagnosisEngine engine,
                                 @Qualifier("deterministicDiagnosisEngine") DiagnosisEngine fallbackEngine,
                                 ServiceNowGateway serviceNow,
                                 TriageProperties props,
                                 RunTraceRegistry runTraceRegistry) {
        this(engine, fallbackEngine, serviceNow, props, runTraceRegistry, new ConnectorModeProvider());
    }

    /**
     * TASK-016 (J11 §LT7): {@code connectorModeProvider} is the ONLY constructor param Spring
     * actually autowires (real {@link ConnectorModeProvider} bean, backed by the live
     * {@code Environment}) — the two convenience constructors above exist purely for
     * pre-TASK-016 test call sites and default it to "mock everywhere", same as
     * {@code matchIfMissing = true} on every {@code Mock*Gateway}.
     */
    @Autowired
    public DiagnosisOrchestrator(DiagnosisEngine engine,
                                 @Qualifier("deterministicDiagnosisEngine") DiagnosisEngine fallbackEngine,
                                 ServiceNowGateway serviceNow,
                                 TriageProperties props,
                                 RunTraceRegistry runTraceRegistry,
                                 ConnectorModeProvider connectorModeProvider) {
        this.engine = engine;
        this.fallbackEngine = fallbackEngine;
        this.serviceNow = serviceNow;
        this.props = props;
        this.runTraceRegistry = runTraceRegistry;
        this.connectors = connectorModeProvider.modes();
        // FND-49: triage.engine=adk without -Padk matches no ADK bean, so the app silently
        // falls back to `engine == fallbackEngine` (deterministic) with nothing announcing
        // it — the FND-8 failure class (narrating a live model over a scripted run) via a
        // misconfiguration path rather than a runtime one. Log the mismatch loudly at
        // startup; do NOT fail fast (a hackathon build shouldn't refuse to boot over this).
        if (props.engine() == TriageProperties.Engine.ADK && engine == fallbackEngine) {
            log.warn("triage.engine=adk but no ADK engine bean is active (missing -Padk build, "
                    + "or the ADK bean failed to register) — running DETERMINISTIC only. "
                    + "This is NOT the FND-7 fallback (no failure occurred); the app never had "
                    + "an ADK engine to try.");
        }
    }

    /**
     * FND-56: whether the ADK engine bean is actually wired as primary, not just whether
     * {@code triage.engine=adk} is configured. {@code IncidentPoller}'s C6 unattended-LLM-use
     * WARN used to check the config value alone, so a non-{@code -Padk} build with
     * {@code engine=adk} claimed "unattended, programmatic LLM use" for a run that will never
     * contact a model — the exact opposite of what the FND-49 WARN above says at the same
     * moment. Same identity comparison FND-49 uses, exposed so K1 doesn't need its own.
     */
    /**
     * J20/STV-1: the connector modes this orchestrator is ACTUALLY using — resolved once at
     * construction from {@code ConnectorModeProvider}, i.e. from which beans wired, not from
     * the properties that asked for them.
     */
    public java.util.Map<String, String> connectorModes() {
        return connectors;
    }

    /**
     * J17/PCS-4 — incidents that reached the end of a run successfully, <b>whatever trigger
     * asked for it</b>. Bounded and insertion-ordered, same shape as the poller's own set.
     *
     * <p>This orchestrator is already "the ONE place their calls meet", so it is the only
     * component that can see both triggers. K1 could otherwise re-diagnose an incident a
     * presenter had just run from the UI seconds earlier — a second full agent pass, and a
     * second pair of advisory comments on a ticket that already had them.
     */
    private final java.util.Set<String> recentlyCompleted =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private static final int RECENTLY_COMPLETED_CAP = 500;

    /**
     * J17/PCS-4 — <b>advisory to the poller only.</b> This orchestrator must never refuse a run
     * because of it.
     *
     * <p>The asymmetry is the point. A human clicking Diagnose a second time on stage has
     * asked for a second run and must get one —
     * {@code DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced} pins
     * that as intended, and this does not amend it. An unattended poller re-picking the same
     * incident has asked for nothing; it merely has not been told.
     */
    public boolean wasRecentlyCompleted(String incidentNumber) {
        return recentlyCompleted.contains(normalize(incidentNumber));
    }

    /**
     * The same normalisation {@code run()} applies (FND-37/FND-50), so a UI call and a poller
     * call for the same ticket agree about being the same ticket.
     */
    private static String normalize(String incidentNumber) {
        return incidentNumber == null ? "" : incidentNumber.trim().toUpperCase();
    }

    private void recordCompleted(String incidentNumber) {
        synchronized (recentlyCompleted) {
            recentlyCompleted.add(normalize(incidentNumber));
            var it = recentlyCompleted.iterator();
            while (recentlyCompleted.size() > RECENTLY_COMPLETED_CAP && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    public boolean isAdkActuallyActive() {
        return engine != fallbackEngine;
    }

    @PreDestroy
    void shutdown() {
        engineExecutor.shutdownNow();
    }

    public DiagnosisResult run(String rawIncidentNumber) {
        return run(rawIncidentNumber, null);
    }

    /**
     * {@code runId} is the optional, client-minted id from the {@code X-Triage-Run-Id} header
     * on {@code POST /api/diagnose/{incidentNumber}}. {@code null} or blank means the caller
     * sent no header — most notably {@code IncidentPoller} (K1).
     *
     * <p><b>J16/RTR-1: no header no longer means no buffer.</b> LT4 rule 4 ("no header ⇒ no
     * buffer") is retired. A headerless run now runs under a server-minted {@code "srv-" +
     * UUID} and registers exactly like a header-carrying one; a client-supplied {@code runId}
     * stays authoritative when present, so the header contract is untouched. Rule 4 made the
     * existence of a live buffer depend on which trigger started the run, which broke the
     * FND-31 mixed-trigger shape below: when K1 owned the run, an attended K3 caller
     * coalescing onto it had nothing to alias to and no live trace to watch.
     */
    public DiagnosisResult run(String rawIncidentNumber, String runId) {
        // FND-50: normalize here, not just in DiagnosisController — K1 (IncidentPoller)
        // passes ServiceNow's raw value directly, so normalizing only at the controller
        // let K1 and K3 fail to coalesce on a case/whitespace difference, defeating
        // FND-31 for exactly the mixed-trigger case it exists for.
        String incidentNumber = rawIncidentNumber.trim().toUpperCase();
        String effectiveRunId = hasText(runId) ? runId : "srv-" + UUID.randomUUID();

        // J16/RTR-2 — the ordering here is the fix, not a narrowing of it. REGISTER the
        // buffer, THEN publish (runId, future) as one value, THEN putIfAbsent. Because the
        // collector exists before this run is visible in the map at all, any waiter that
        // wins the putIfAbsent race and reads this entry is guaranteed to find a registered,
        // non-terminal buffer to alias to. Publishing first and registering after would
        // leave a window where a waiter aliases to a runId that has no collector yet.
        TraceCollector collector = runTraceRegistry.register(effectiveRunId, incidentNumber);
        InFlightRun mine = new InFlightRun(new CompletableFuture<>(), effectiveRunId);
        InFlightRun existing = inFlight.putIfAbsent(incidentNumber, mine);
        if (existing != null) {
            log.info("diagnosis for {} already in flight — waiting for it instead of starting a duplicate (FND-31)",
                    incidentNumber);
            // FND-31 + LT4: this caller never runs an engine (it just waits below), so it
            // owns no segment of its own — alias its runId onto the canonical run's buffer
            // so a client polling on it watches the same live steps instead of one that will
            // never receive any. aliasTo refuses if the canonical run has already finished
            // or aged out (J16/RTR-3); the waiter then has no live buffer and the client
            // renders the final result from this call's response, which is honest.
            runTraceRegistry.aliasTo(effectiveRunId, existing.runId());
            return awaitExisting(incidentNumber, existing.future());
        }
        try {
            DiagnosisResult result = runOnce(incidentNumber, collector);
            mine.future().complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.future().completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(incidentNumber, mine);
            // FND-76: complete the future on EVERY exit path, including a Throwable that is
            // not a RuntimeException. The catch above covers the normal failure modes, but an
            // Error raised on THIS thread outside the engine future — an OOM or
            // StackOverflowError while assembling the trace, the work-note text, or the
            // result record — used to escape past both `complete` calls while this finally
            // still removed the map entry. The future then stayed incomplete forever with
            // nobody left to complete it.
            //
            // That matters because `awaitExisting` blocks in an UNTIMED `existing.get()`, and
            // IncidentPoller is single-threaded (Spring's default scheduler is one thread).
            // A poller tick that coalesced onto this run as a waiter would block on that
            // future for the rest of the process lifetime — no WARN, no recovery, K1 simply
            // stops polling forever. Narrow window, unbounded consequence, two lines to close.
            if (!mine.future().isDone()) {
                mine.future().completeExceptionally(new IllegalStateException(
                        "diagnosis for " + incidentNumber + " ended without completing its result "
                                + "(an Error escaped the run) — failing waiters rather than hanging them"));
            }
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private DiagnosisResult awaitExisting(String incidentNumber, CompletableFuture<DiagnosisResult> existing) {
        try {
            return existing.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("in-flight diagnosis for " + incidentNumber + " failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted waiting for in-flight diagnosis of " + incidentNumber, e);
        }
    }

    /**
     * TASK-003 (J11/LT1): one collector per run, shared across the primary and (if needed)
     * fallback engine calls — see {@link #diagnoseWithFallback} for the segment-per-attempt
     * degrade handling. J16/RTR-2 moved its creation up into {@link #run(String, String)},
     * because registering the buffer has to happen strictly BEFORE this run becomes visible
     * in the in-flight map for a waiter to alias onto.
     */
    private DiagnosisResult runOnce(String incidentNumber, TraceCollector collector) {
        long t0 = System.currentTimeMillis();
        try {
            return runOnceWithCollector(incidentNumber, collector, t0);
        } finally {
            // FND-77: "done" is a fact about the run being OVER, and "over by failure" is
            // still over. markDone() used to sit only on the success path, so a run that
            // threw (IncidentNotFoundException, a timeout, a fallback that also failed) left
            // its registered buffer reading done=false until the 5-minute TTL evicted it —
            // breaking TASK-011's invariant that `done` agrees with the POST outcome, which
            // held only for 200s.
            //
            // No shipped client renders that stale buffer (index.html stops polling in the
            // POST's finally, which fires on rejection, and a coalesced waiter's POST fails
            // the same way), so this is contract accuracy rather than a live bug — but the
            // invariant is load-bearing for the registry work in J16 and for any non-browser
            // poller, and it costs one try/finally.
            collector.markDone();
        }
    }

    private DiagnosisResult runOnceWithCollector(String incidentNumber, TraceCollector collector, long t0) {
        DiagnosisResult result = diagnoseWithFallback(incidentNumber, collector);

        // FND-36: writebackPosted must reflect what ACTUALLY happened, not just whether
        // writeback was enabled — those diverged the moment either write could fail.
        // Previously an exception from either addWorkNote call propagated straight out
        // of run(), losing the whole diagnosis result (already-produced report, first
        // comment already posted) and surfacing as a raw 500 — for K1 that meant a
        // writeback-only failure looked identical to a diagnosis failure and re-ran the
        // whole (expensive, LLM-backed) diagnosis on a later tick instead of just
        // retrying the write. A partial writeback (first comment posted, second failed)
        // is caught here rather than left non-atomic-and-silent: disclosed in the trace,
        // writebackPosted reports false, and the diagnosis itself is still returned.
        boolean writebackPosted = false;
        if (props.writeback().enabled()) {
            // J17/PCS-3: delivery outcome is tracked PER CALL, not as one boolean. The old
            // shape wrapped both posts in one try, so a failure on the second left
            // writebackPosted=false while the first comment WAS on the ticket. Anything
            // retrying off that boolean would repost the sources note — and the trace could
            // only say "at most one of the two may have posted", which is not a fact anyone
            // can act on.
            boolean sourcesPosted = false;
            try {
                // Automatic, advisory, two comments — sources first so the diagnosis is auditable.
                serviceNow.addWorkNote(incidentNumber, result.report().toSourcesNote());
                result.trace().add("servicenow.addWorkNote → posted 'Sources consulted' comment");
                sourcesPosted = true;
            } catch (RuntimeException e) {
                log.warn("writeback for {}: 'Sources consulted' comment failed ({}: {})",
                        incidentNumber, e.getClass().getSimpleName(), e.getMessage());
                result.trace().add("⚠ 'Sources consulted' comment did NOT post (%s: %s)"
                        .formatted(e.getClass().getSimpleName(), e.getMessage()));
            }
            boolean diagnosisPosted = false;
            try {
                serviceNow.addWorkNote(incidentNumber, result.report().toDiagnosisNote());
                result.trace().add("servicenow.addWorkNote → posted 'First-pass diagnosis' comment (advisory)");
                diagnosisPosted = true;
            } catch (RuntimeException e) {
                log.warn("writeback for {}: 'First-pass diagnosis' comment failed ({}: {})",
                        incidentNumber, e.getClass().getSimpleName(), e.getMessage());
                result.trace().add("⚠ 'First-pass diagnosis' comment did NOT post (%s: %s)"
                        .formatted(e.getClass().getSimpleName(), e.getMessage()));
            }
            // The diagnosis note is the one that carries the value. Sources alone is a
            // citation list with nothing to cite for, so "delivered" means the diagnosis
            // reached the ticket; a lost sources note is degraded, not undelivered.
            writebackPosted = diagnosisPosted;
            if (diagnosisPosted && !sourcesPosted) {
                result.trace().add("⚠ diagnosis posted without its sources comment — the note's "
                        + "\"Sources are in the comment above\" line has nothing to point at");
            }
        } else {
            result.trace().add("writeback disabled (triage.writeback.enabled=false) — comments not posted");
        }

        log.info("diagnosis for {} completed in {} ms ({} steps, writeback={})",
                incidentNumber, System.currentTimeMillis() - t0, result.trace().size(), writebackPosted);
        // TASK-011 (J11/LT4 poll endpoint): mark the run done HERE, not any earlier — this
        // is the first point at which the report, engine label, and writeback outcome are
        // all settled, i.e. the exact moment the POST is about to return its final 200. A
        // GET /api/runs/{runId}/steps poller's `done` flag must agree with that (design
        // doc, binding), so it cannot flip true merely because the last tool-call step
        // resolved — writeback still had to happen after that.
        //
        // FND-77 moved the CALL to a finally in the caller so failure paths mark done too;
        // this success-path ordering is unchanged (the finally runs after this returns, and
        // markDone is idempotent), so the invariant above still holds exactly as written.
        // Reconstruction site 3/3: result.steps() already carries the collector's final
        // snapshot forward from diagnoseWithFallback — nothing emits new steps between
        // there and here (writeback is prose-only, added to `trace`), so no fresh
        // collector.steps() read is needed.
        // J17/PCS-4: record the completion HERE — the one place both triggers' calls meet, and
        // the last point at which the run is known to have finished. Advisory to the poller
        // only; this method never consults it, so a human asking twice always gets two runs.
        recordCompleted(incidentNumber);

        return new DiagnosisResult(result.report(), result.trace(), result.engine(), writebackPosted,
                result.steps(), connectors);
    }

    private DiagnosisResult diagnoseWithFallback(String incidentNumber, TraceCollector collector) {
        if (engine == fallbackEngine) {
            // Deterministic engine IS the active engine — no fallback to fall back to;
            // let a failure (including a timeout) propagate as the real bug it would be.
            // Reconstruction site 1/3: the engine's own DiagnosisResult never carries real
            // steps (it's built via a back-compat constructor) — the collector is the only
            // place they actually accumulate, so carry it forward here explicitly.
            DiagnosisResult result = callWithTimeout(engine, incidentNumber, collector.forAttempt(0));
            return new DiagnosisResult(result.report(), result.trace(), result.engine(),
                    result.writebackPosted(), collector.steps());
        }
        try {
            DiagnosisResult live = callWithTimeout(engine, incidentNumber, collector.forAttempt(0));
            // The primary engine ran; label the result with which one it actually was.
            // Reconstruction site 2/3.
            return new DiagnosisResult(live.report(), live.trace(), DiagnosisResult.Engine.ADK,
                    true, collector.steps());
        } catch (Exception e) {
            log.warn("primary engine failed for {} ({}: {}) — degrading to the deterministic engine",
                    incidentNumber, e.getClass().getSimpleName(), e.getMessage());
            // J11/LT1 Invariant 1: freeze attempt 0's steps (kept, re-tagged ABANDONED —
            // never discarded) and record a FALLBACK_STARTED boundary before attempt 1
            // starts. The primary's virtual thread is not killed (FND-15 is best-effort),
            // so any further writes it makes for attempt 0 land in a dead segment and are
            // dropped by the collector — they can never merge into attempt 1's sequence.
            collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC,
                    "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage()));
            DiagnosisResult fallback;
            try {
                fallback = callWithTimeout(fallbackEngine, incidentNumber, collector.forAttempt(1));
            } catch (RuntimeException fallbackFailure) {
                // FND-90: the net under the net. The catch above covers the PRIMARY engine
                // failing; nothing covered the fallback failing, so a throw from the
                // deterministic engine propagated straight out to an HTTP 500 — no report, no
                // trace, nothing on screen. That is the one outcome the demo runbook's D2
                // exists to prevent ("the demo cannot hard-fail on stage"), and it was
                // reachable at precisely the moment D2 matters: after the primary already
                // failed.
                //
                // Both failures are surfaced, not just the second. Reporting only the fallback's
                // error would hide why the fallback was running at all, and the two together
                // are what a person needs to debug it afterwards.
                log.error("BOTH engines failed for {} — primary {}: {}; fallback {}: {}",
                        incidentNumber, e.getClass().getSimpleName(), e.getMessage(),
                        fallbackFailure.getClass().getSimpleName(), fallbackFailure.getMessage(),
                        fallbackFailure);
                throw new BothEnginesFailedException(incidentNumber, e, fallbackFailure);
            }
            fallback.trace().add(0, "⚠ primary engine did not converge (%s: %s) — degraded to the deterministic engine"
                    .formatted(e.getClass().getSimpleName(), e.getMessage()));
            // Reconstruction site 3/3 (of diagnoseWithFallback; runOnce carries it forward
            // once more for writebackPosted, making 4 sites total in this class).
            return new DiagnosisResult(fallback.report(), fallback.trace(),
                    DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, true, collector.steps());
        }
    }

    /** FND-15: bounds any single engine call to {@code timeoutMs}, on a virtual thread. */
    private DiagnosisResult callWithTimeout(DiagnosisEngine target, String incidentNumber, TraceSink sink) {
        Future<DiagnosisResult> future = engineExecutor.submit(() -> target.diagnose(incidentNumber, sink));
        long timeoutMs = props.orchestrator().timeoutMs();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);   // best-effort; see class javadoc
            throw new DiagnosisTimeoutException(incidentNumber, timeoutMs);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("engine call failed for " + incidentNumber, cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted waiting for engine result for " + incidentNumber, ie);
        }
    }
}
