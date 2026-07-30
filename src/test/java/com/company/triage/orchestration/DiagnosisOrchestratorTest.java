package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the automatic (no-human) two-comment write-back: sources first, then diagnosis (J5/J8). */
class DiagnosisOrchestratorTest {

    /** Records the work notes posted, in order. */
    static class RecordingServiceNow implements ServiceNowGateway {
        final List<String> notes = Collections.synchronizedList(new ArrayList<>());
        public IncidentContext getIncident(String n) { return null; }
        public List<com.company.triage.model.NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String number, String note) { notes.add(note); }
    }

    private DiagnosisReport sampleReport() {
        return new DiagnosisReport("INC0012345", OffsetDateTime.now(),
                "Checkout order submission fails", "Order submission", "Production",
                new Identifiers("INC-ORD-4471", null, "INC-ORD-4471"),
                List.of(new CandidateSystem("Payment Service", 0.86, List.of("e-log"))),
                new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM, List.of("e-cmdb")),
                List.of(new Evidence("e-log", "sumo", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471", "prod/payment")),
                List.of(new Contact("Priya Nair", "priya.nair@example.com", "confluence+gitlab",
                        "edited the runbook and committed reconcile()", "https://confluence.example.com/x", "recent")),
                List.of(), List.of("user id"), "Check payment_service.reconcile()", Confidence.MEDIUM, true);
    }

    @Test
    void postsTwoAdvisoryCommentsSourcesFirst() {
        var snow = new RecordingServiceNow();
        DiagnosisReport report = sampleReport();
        DiagnosisEngine engine = incident -> new DiagnosisResult(report, new ArrayList<>(List.of("diagnose")));
        DiagnosisEngine unusedFallback = incident -> { throw new AssertionError("fallback must not run"); };

        DiagnosisResult r = new DiagnosisOrchestrator(engine, unusedFallback, snow, true, 5000).run("INC0012345");

        assertThat(snow.notes).hasSize(2);
        assertThat(snow.notes.get(0)).contains("Sources consulted").contains("prod/payment");   // sources first
        assertThat(snow.notes.get(1)).contains("First-pass diagnosis")
                .contains("advisory").contains("No reassignment");                                // diagnosis, advisory
        assertThat(r.trace()).anyMatch(s -> s.contains("Sources consulted"));
    }

    @Test
    void writebackDisabledPostsNothing() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine engine = incident -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        DiagnosisEngine unusedFallback = incident -> { throw new AssertionError("fallback must not run"); };
        new DiagnosisOrchestrator(engine, unusedFallback, snow, false, 5000).run("INC0012345");
        assertThat(snow.notes).isEmpty();
    }

    /** FND-7: a primary engine that fails to converge degrades to the fallback engine
     *  instead of crashing the request, and the degradation is disclosed in the trace. */
    @Test
    void primaryEngineFailureDegradesToFallbackEngine() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine failingPrimary = incident -> {
            throw new IllegalStateException("LLM calls limit exceeded (simulated)");
        };
        DiagnosisReport fallbackReport = sampleReport();
        DiagnosisEngine fallback = incident ->
                new DiagnosisResult(fallbackReport, new ArrayList<>(List.of("deterministic: assembled report")));

        DiagnosisResult r = new DiagnosisOrchestrator(failingPrimary, fallback, snow, true, 5000).run("INC0012345");

        assertThat(r.report()).isSameAs(fallbackReport);
        assertThat(r.trace().get(0)).contains("degraded to the deterministic engine")
                .contains("LLM calls limit exceeded (simulated)");
        assertThat(snow.notes).hasSize(2);   // writeback still happens off the fallback report
    }

    /** When the active engine IS the fallback engine (no -Padk build, or triage.engine=
     *  deterministic), a failure must propagate as a real bug, not be swallowed by a
     *  no-op "fallback" to itself. */
    @Test
    void whenPrimaryIsAlreadyTheFallbackEngineFailuresPropagate() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine onlyEngine = incident -> { throw new IllegalStateException("boom"); };

        var orchestrator = new DiagnosisOrchestrator(onlyEngine, onlyEngine, snow, true, 5000);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> orchestrator.run("INC0012345"));
    }

    // --- FND-15: wall-clock timeout ------------------------------------------------

    /** No fallback available (single engine): a hung call must propagate, not hang forever. */
    @Test
    void engineTimeoutPropagatesWhenNoFallback() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine slow = incident -> {
            sleepUninterruptibly(500);
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        var orchestrator = new DiagnosisOrchestrator(slow, slow, snow, true, 50);   // 50ms << 500ms

        assertThatThrownBy(() -> orchestrator.run("INC0012345"))
                .isInstanceOf(DiagnosisTimeoutException.class)
                .hasMessageContaining("INC0012345");
    }

    /** A hung PRIMARY engine feeds the same FND-7 fallback path as any other failure. */
    @Test
    void engineTimeoutOnPrimaryDegradesToFallback() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine slowPrimary = incident -> {
            sleepUninterruptibly(500);
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        DiagnosisReport fallbackReport = sampleReport();
        DiagnosisEngine fallback = incident ->
                new DiagnosisResult(fallbackReport, new ArrayList<>(List.of("deterministic: ok")));
        var orchestrator = new DiagnosisOrchestrator(slowPrimary, fallback, snow, true, 50);

        DiagnosisResult r = orchestrator.run("INC0012345");

        assertThat(r.report()).isSameAs(fallbackReport);
        assertThat(r.engine()).isEqualTo(DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC);
        assertThat(r.trace().get(0)).contains("DiagnosisTimeoutException")
                .contains("degraded to the deterministic engine");
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- FND-31: concurrent-diagnosis coalescing ------------------------------------

    /**
     * The demo shape this exists for: polling on, presenter also clicks manually.
     * Two callers racing for the SAME incident number must produce exactly one engine
     * run and exactly one writeback (2 notes, not 4) — the second caller gets the
     * first's result instead of starting a duplicate diagnosis.
     */
    @Test
    void concurrentRunsForSameIncidentCoalesceIntoOneEngineCallAndOneWriteback() throws Exception {
        var snow = new RecordingServiceNow();
        AtomicInteger engineCalls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DiagnosisEngine engine = incident -> {
            engineCalls.incrementAndGet();
            started.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).as("release latch reached").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, true, 5000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisResult> first = pool.submit(() -> orchestrator.run("INC0012345"));
            assertThat(started.await(2, TimeUnit.SECONDS))
                    .as("first caller must be inside the engine call before the second starts")
                    .isTrue();

            Future<DiagnosisResult> second = pool.submit(() -> orchestrator.run("INC0012345"));
            Thread.sleep(100);   // give the second caller time to reach the "already in flight" branch
            release.countDown();

            DiagnosisResult r1 = first.get(2, TimeUnit.SECONDS);
            DiagnosisResult r2 = second.get(2, TimeUnit.SECONDS);

            assertThat(engineCalls.get()).isEqualTo(1);
            assertThat(r2).isSameAs(r1);
            assertThat(snow.notes).hasSize(2);   // one diagnosis's worth of writeback, not two
        } finally {
            pool.shutdownNow();
        }
    }

    /** Sequential (non-overlapping) runs of the same incident are NOT coalesced — each is real. */
    @Test
    void sequentialRunsOfTheSameIncidentAreNotCoalesced() {
        var snow = new RecordingServiceNow();
        AtomicInteger engineCalls = new AtomicInteger();
        DiagnosisEngine engine = incident -> {
            engineCalls.incrementAndGet();
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, true, 5000);

        orchestrator.run("INC0012345");
        orchestrator.run("INC0012345");   // fully separate, deliberate re-trigger

        assertThat(engineCalls.get()).isEqualTo(2);
        assertThat(snow.notes).hasSize(4);   // two diagnoses' worth
    }
}
