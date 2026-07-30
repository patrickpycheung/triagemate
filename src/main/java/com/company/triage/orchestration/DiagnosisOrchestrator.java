package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
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
    private final boolean writebackEnabled;
    private final long timeoutMs;
    private final ExecutorService engineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** FND-31: coalesces concurrent runs for the same incident number. */
    private final ConcurrentHashMap<String, CompletableFuture<DiagnosisResult>> inFlight =
            new ConcurrentHashMap<>();

    public DiagnosisOrchestrator(DiagnosisEngine engine,
                                 @Qualifier("deterministicDiagnosisEngine") DiagnosisEngine fallbackEngine,
                                 ServiceNowGateway serviceNow,
                                 @Value("${triage.writeback.enabled:true}") boolean writebackEnabled,
                                 @Value("${triage.orchestrator.timeout-ms:45000}") long timeoutMs) {
        this.engine = engine;
        this.fallbackEngine = fallbackEngine;
        this.serviceNow = serviceNow;
        this.writebackEnabled = writebackEnabled;
        this.timeoutMs = timeoutMs;
    }

    @PreDestroy
    void shutdown() {
        engineExecutor.shutdownNow();
    }

    public DiagnosisResult run(String incidentNumber) {
        CompletableFuture<DiagnosisResult> mine = new CompletableFuture<>();
        CompletableFuture<DiagnosisResult> existing = inFlight.putIfAbsent(incidentNumber, mine);
        if (existing != null) {
            log.info("diagnosis for {} already in flight — waiting for it instead of starting a duplicate (FND-31)",
                    incidentNumber);
            return awaitExisting(incidentNumber, existing);
        }
        try {
            DiagnosisResult result = runOnce(incidentNumber);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(incidentNumber, mine);
        }
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

    private DiagnosisResult runOnce(String incidentNumber) {
        long t0 = System.currentTimeMillis();
        DiagnosisResult result = diagnoseWithFallback(incidentNumber);

        if (writebackEnabled) {
            // Automatic, advisory, two comments — sources first so the diagnosis is auditable.
            serviceNow.addWorkNote(incidentNumber, result.report().toSourcesNote());
            result.trace().add("servicenow.addWorkNote → posted 'Sources consulted' comment");
            serviceNow.addWorkNote(incidentNumber, result.report().toDiagnosisNote());
            result.trace().add("servicenow.addWorkNote → posted 'First-pass diagnosis' comment (advisory)");
        } else {
            result.trace().add("writeback disabled (triage.writeback.enabled=false) — comments not posted");
        }

        log.info("diagnosis for {} completed in {} ms ({} steps, writeback={})",
                incidentNumber, System.currentTimeMillis() - t0, result.trace().size(), writebackEnabled);
        // FND-25: writebackPosted reflects what actually happened in THIS method, not a
        // client-side guess — this is the only place that knows for certain.
        return new DiagnosisResult(result.report(), result.trace(), result.engine(), writebackEnabled);
    }

    private DiagnosisResult diagnoseWithFallback(String incidentNumber) {
        if (engine == fallbackEngine) {
            // Deterministic engine IS the active engine — no fallback to fall back to;
            // let a failure (including a timeout) propagate as the real bug it would be.
            return callWithTimeout(engine, incidentNumber);
        }
        try {
            DiagnosisResult live = callWithTimeout(engine, incidentNumber);
            // The primary engine ran; label the result with which one it actually was.
            return new DiagnosisResult(live.report(), live.trace(), DiagnosisResult.Engine.ADK);
        } catch (Exception e) {
            log.warn("primary engine failed for {} ({}: {}) — degrading to the deterministic engine",
                    incidentNumber, e.getClass().getSimpleName(), e.getMessage());
            DiagnosisResult fallback = callWithTimeout(fallbackEngine, incidentNumber);
            fallback.trace().add(0, "⚠ primary engine did not converge (%s: %s) — degraded to the deterministic engine"
                    .formatted(e.getClass().getSimpleName(), e.getMessage()));
            return new DiagnosisResult(fallback.report(), fallback.trace(),
                    DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC);
        }
    }

    /** FND-15: bounds any single engine call to {@code timeoutMs}, on a virtual thread. */
    private DiagnosisResult callWithTimeout(DiagnosisEngine target, String incidentNumber) {
        Future<DiagnosisResult> future = engineExecutor.submit(() -> target.diagnose(incidentNumber));
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
