package com.company.triage.orchestration;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import com.company.triage.orchestration.trace.Platform;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceStep;
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

    private static TriageProperties props(boolean writebackEnabled, long timeoutMs) {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(writebackEnabled),
                new TriageProperties.Orchestrator(timeoutMs), base.agent(), base.trigger(),
                base.servicenow(), base.sumo(), base.gitlab());
    }

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
        return new DiagnosisReport("INC0010005", OffsetDateTime.now(),
                "Checkout order submission fails", "Order submission", "Production",
                new Identifiers("INC-ORD-4471", null, "INC-ORD-4471"),
                List.of(new CandidateSystem("Payment Service", 0.86, List.of("e-log"))),
                new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM, List.of("e-cmdb")),
                List.of(new Evidence("e-log", "sumo", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471", "prod/payment")),
                List.of(new Contact("Priya Nair", "priya.nair@example.com", "confluence+gitlab",
                        "edited the runbook and committed reconcile()", "https://confluence.example.com/x", "recent")),
                List.of(), List.of("user id"), "Check payment_service.reconcile()", Confidence.MEDIUM, true, null, null);
    }

    @Test
    void postsTwoAdvisoryCommentsSourcesFirst() {
        var snow = new RecordingServiceNow();
        DiagnosisReport report = sampleReport();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(report, new ArrayList<>(List.of("diagnose")));
        DiagnosisEngine unusedFallback = (incident, sink) -> { throw new AssertionError("fallback must not run"); };

        DiagnosisResult r = new DiagnosisOrchestrator(engine, unusedFallback, snow, props(true, 5000)).run("INC0010005");

        assertThat(snow.notes).hasSize(2);
        assertThat(snow.notes.get(0)).contains("Sources consulted").contains("prod/payment");   // sources first
        assertThat(snow.notes.get(1)).contains("First-pass diagnosis")
                .contains("advisory").contains("No reassignment");                                // diagnosis, advisory
        assertThat(r.trace()).anyMatch(s -> s.contains("Sources consulted"));
        assertThat(r.writebackPosted()).as("FND-25: real writes actually happened").isTrue();
    }

    /**
     * FND-36: previously an exception from the SECOND {@code addWorkNote} call
     * propagated straight out of {@code run()}, losing the whole diagnosis result (the
     * report was already produced, the first comment already posted) and reporting
     * {@code writebackPosted} from the config flag rather than what actually happened.
     * A partial writeback must not lose the diagnosis, and must disclose itself.
     */
    static class SecondWriteFailsServiceNow implements ServiceNowGateway {
        final List<String> notes = Collections.synchronizedList(new ArrayList<>());
        public IncidentContext getIncident(String n) { return null; }
        public List<com.company.triage.model.NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String number, String note) {
            notes.add(note);
            if (notes.size() == 2) throw new RuntimeException("ServiceNow PATCH failed (simulated)");
        }
    }

    /**
     * FND-50: normalization must live in run() itself, not just the controller — K1
     * (IncidentPoller) calls run() directly with ServiceNow's raw value, so a caller that
     * differs only in case/whitespace from K3's normalized call must still coalesce
     * (FND-31), not start a second diagnosis.
     */
    @Test
    void differentlyCasedIncidentNumbersStillCoalesce() throws Exception {
        var engineCalls = new AtomicInteger(0);
        var latch = new CountDownLatch(1);
        DiagnosisEngine engine = (incident, sink) -> {
            engineCalls.incrementAndGet();
            try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        };
        var snow = new RecordingServiceNow();
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(true, 5000));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisResult> first = pool.submit(() -> orchestrator.run(" inc0010005 "));
            Thread.sleep(100);   // let the first caller register in the coalescing map
            Future<DiagnosisResult> second = pool.submit(() -> orchestrator.run("INC0010005"));
            latch.countDown();

            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            assertThat(engineCalls.get()).as("differently-cased calls for the same incident must coalesce").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void partialWritebackFailureIsDisclosedNotLost() {
        var snow = new SecondWriteFailsServiceNow();
        DiagnosisReport report = sampleReport();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(report, new ArrayList<>(List.of("diagnose")));
        DiagnosisEngine unusedFallback = (incident, sink) -> { throw new AssertionError("fallback must not run"); };

        DiagnosisResult r = new DiagnosisOrchestrator(engine, unusedFallback, snow, props(true, 5000)).run("INC0010005");

        assertThat(snow.notes).hasSize(2);   // first write landed, second was attempted and failed
        assertThat(r.report()).isSameAs(report);   // diagnosis itself is not lost
        assertThat(r.writebackPosted()).as("FND-36: a partial write is not a success").isFalse();
        assertThat(r.trace()).anyMatch(s -> s.contains("writeback failed partway through"));
    }

    @Test
    void writebackDisabledPostsNothing() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        DiagnosisEngine unusedFallback = (incident, sink) -> { throw new AssertionError("fallback must not run"); };
        DiagnosisResult r = new DiagnosisOrchestrator(engine, unusedFallback, snow, props(false, 5000)).run("INC0010005");
        assertThat(snow.notes).isEmpty();
        // FND-25: the UI reads this field, not report content, to decide whether to
        // claim comments were posted — must be false when writeback is off.
        assertThat(r.writebackPosted()).isFalse();
    }

    /** FND-7: a primary engine that fails to converge degrades to the fallback engine
     *  instead of crashing the request, and the degradation is disclosed in the trace. */
    @Test
    void primaryEngineFailureDegradesToFallbackEngine() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine failingPrimary = (incident, sink) -> {
            throw new IllegalStateException("LLM calls limit exceeded (simulated)");
        };
        DiagnosisReport fallbackReport = sampleReport();
        DiagnosisEngine fallback = (incident, sink) ->
                new DiagnosisResult(fallbackReport, new ArrayList<>(List.of("deterministic: assembled report")));

        DiagnosisResult r = new DiagnosisOrchestrator(failingPrimary, fallback, snow, props(true, 5000)).run("INC0010005");

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
        DiagnosisEngine onlyEngine = (incident, sink) -> { throw new IllegalStateException("boom"); };

        var orchestrator = new DiagnosisOrchestrator(onlyEngine, onlyEngine, snow, props(true, 5000));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> orchestrator.run("INC0010005"));
    }

    // --- FND-15: wall-clock timeout ------------------------------------------------

    /** No fallback available (single engine): a hung call must propagate, not hang forever. */
    @Test
    void engineTimeoutPropagatesWhenNoFallback() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine slow = (incident, sink) -> {
            sleepUninterruptibly(500);
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        var orchestrator = new DiagnosisOrchestrator(slow, slow, snow, props(true, 50));   // 50ms << 500ms

        assertThatThrownBy(() -> orchestrator.run("INC0010005"))
                .isInstanceOf(DiagnosisTimeoutException.class)
                .hasMessageContaining("INC0010005");
    }

    /** A hung PRIMARY engine feeds the same FND-7 fallback path as any other failure. */
    @Test
    void engineTimeoutOnPrimaryDegradesToFallback() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine slowPrimary = (incident, sink) -> {
            sleepUninterruptibly(500);
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        DiagnosisReport fallbackReport = sampleReport();
        DiagnosisEngine fallback = (incident, sink) ->
                new DiagnosisResult(fallbackReport, new ArrayList<>(List.of("deterministic: ok")));
        var orchestrator = new DiagnosisOrchestrator(slowPrimary, fallback, snow, props(true, 50));

        DiagnosisResult r = orchestrator.run("INC0010005");

        assertThat(r.report()).isSameAs(fallbackReport);
        assertThat(r.engine()).isEqualTo(DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC);
        assertThat(r.trace().get(0)).contains("DiagnosisTimeoutException")
                .contains("degraded to the deterministic engine");
    }

    /**
     * TASK-003 (J11/LT1 Invariant 1): on an FND-7 degrade, the primary attempt's steps
     * must NOT be discarded — they're frozen (ABANDONED) and kept, a FALLBACK_STARTED
     * boundary is recorded, and the fallback attempt's own steps restart {@code seq} at
     * 0 within their own segment. Modeled directly on {@code engineTimeoutOnPrimaryDegradesToFallback}
     * above, extended with real sink emissions from both engines so the segment-per-attempt
     * behavior is actually observable.
     */
    @Test
    void engineTimeoutOnPrimaryDegradesToFallbackPreservesAbandonedStepsAndMarksFallbackBoundary() {
        var snow = new RecordingServiceNow();
        DiagnosisEngine slowPrimary = (incident, sink) -> {
            sink.before(new TraceStep(0, 0, "primary-call-1", Platform.SERVICENOW, "search_incidents",
                    "Searching ServiceNow…", null, StepState.ACTIVE, System.currentTimeMillis(), null,
                    DiagnosisResult.Engine.ADK));
            sleepUninterruptibly(500);
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        DiagnosisReport fallbackReport = sampleReport();
        DiagnosisEngine fallback = (incident, sink) -> {
            sink.before(new TraceStep(0, 0, "det-0", Platform.SERVICENOW, "servicenow.getIncident",
                    "Fetching incident…", null, StepState.ACTIVE, System.currentTimeMillis(), null,
                    DiagnosisResult.Engine.DETERMINISTIC));
            sink.after(new TraceStep(0, 0, "det-0", Platform.SERVICENOW, "servicenow.getIncident",
                    "Fetching incident…", "OK", StepState.DONE, System.currentTimeMillis(), 5L,
                    DiagnosisResult.Engine.DETERMINISTIC));
            return new DiagnosisResult(fallbackReport, new ArrayList<>(List.of("deterministic: ok")));
        };
        var orchestrator = new DiagnosisOrchestrator(slowPrimary, fallback, snow, props(true, 50));

        DiagnosisResult r = orchestrator.run("INC0010005");

        assertThat(r.report()).isSameAs(fallbackReport);
        assertThat(r.engine()).isEqualTo(DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC);

        List<TraceStep> steps = r.steps();

        // Attempt 0's step is present (never discarded) and re-tagged ABANDONED.
        TraceStep abandoned = steps.stream().filter(s -> s.attempt() == 0).findFirst()
                .orElseThrow(() -> new AssertionError("attempt 0 step missing from r.steps(): " + steps));
        assertThat(abandoned.callId()).isEqualTo("primary-call-1");
        assertThat(abandoned.state()).as("frozen segment must be marked ABANDONED").isEqualTo(StepState.ABANDONED);

        // A FALLBACK_STARTED boundary row is present, introducing attempt 1.
        TraceStep boundary = steps.stream()
                .filter(s -> TraceCollector.FALLBACK_STARTED_TOOL.equals(s.tool()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FALLBACK_STARTED boundary in r.steps(): " + steps));
        assertThat(boundary.attempt()).isEqualTo(1);

        // Attempt 1's real steps restart seq at 0 within their own segment — note the
        // fallback engine itself passed attempt=0 on its TraceStep (engines don't know
        // retry state); the orchestrator's collector re-stamps it to 1 regardless.
        List<TraceStep> attempt1Steps = steps.stream()
                .filter(s -> s.attempt() == 1 && !TraceCollector.FALLBACK_STARTED_TOOL.equals(s.tool()))
                .toList();
        assertThat(attempt1Steps).isNotEmpty();
        assertThat(attempt1Steps.get(0).seq()).isZero();
        assertThat(attempt1Steps.get(0).callId()).isEqualTo("det-0");
        assertThat(attempt1Steps.get(0).state()).isEqualTo(StepState.DONE);

        // Ordering: abandoned attempt-0 row(s), then the boundary, then attempt-1 rows.
        int abandonedIdx = steps.indexOf(abandoned);
        int boundaryIdx = steps.indexOf(boundary);
        int attempt1Idx = steps.indexOf(attempt1Steps.get(0));
        assertThat(abandonedIdx).isLessThan(boundaryIdx);
        assertThat(boundaryIdx).isLessThan(attempt1Idx);
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
        DiagnosisEngine engine = (incident, sink) -> {
            engineCalls.incrementAndGet();
            started.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).as("release latch reached").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(true, 5000));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisResult> first = pool.submit(() -> orchestrator.run("INC0010005"));
            assertThat(started.await(2, TimeUnit.SECONDS))
                    .as("first caller must be inside the engine call before the second starts")
                    .isTrue();

            Future<DiagnosisResult> second = pool.submit(() -> orchestrator.run("INC0010005"));
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
        DiagnosisEngine engine = (incident, sink) -> {
            engineCalls.incrementAndGet();
            return new DiagnosisResult(sampleReport(), new ArrayList<>());
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(true, 5000));

        orchestrator.run("INC0010005");
        orchestrator.run("INC0010005");   // fully separate, deliberate re-trigger

        assertThat(engineCalls.get()).isEqualTo(2);
        assertThat(snow.notes).hasSize(4);   // two diagnoses' worth
    }
}
