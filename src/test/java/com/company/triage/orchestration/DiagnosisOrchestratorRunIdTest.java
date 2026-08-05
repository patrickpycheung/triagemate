package com.company.triage.orchestration;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import com.company.triage.orchestration.trace.RunTraceRegistry;
import com.company.triage.orchestration.trace.TraceCollector;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-009 (J11/LT4 {@code runId} protocol): request-side header handling in
 * {@code DiagnosisOrchestrator#run(String, String)}.
 *
 * <p>Verifies the three binding rules from {@code docs/design-java/concepts/
 * J11-live-thinking-trace/README.md} §LT4:
 * <ul>
 *   <li>rule 4, "no header ⇒ no buffer" — {@code run(String)} (K1's call, and K3's
 *       header-less call) never touches the {@link RunTraceRegistry} at all;</li>
 *   <li>a real (non-NOOP) {@link TraceCollector} backs a run when a runId is supplied,
 *       registered under that runId;</li>
 *   <li>rule 5, FND-31 coalescing — a coalesced caller's runId gets handed to {@link
 *       RunTraceRegistry#alias}, without regressing the existing coalescing behaviour
 *       proven by {@code DiagnosisOrchestratorTest}.</li>
 * </ul>
 */
class DiagnosisOrchestratorRunIdTest {

    private static TriageProperties props(long timeoutMs) {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(timeoutMs), base.agent(), base.trigger(),
                base.servicenow(), base.sumo(), base.gitlab());
    }

    static class RecordingServiceNow implements ServiceNowGateway {
        final List<String> notes = Collections.synchronizedList(new ArrayList<>());
        public IncidentContext getIncident(String n) { return null; }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String number, String note) { notes.add(note); }
    }

    private static DiagnosisReport sampleReport() {
        return new DiagnosisReport("INC0010005", OffsetDateTime.parse("2026-07-29T12:00:00Z"),
                "Checkout order submission fails", "Order submission", "Production",
                new Identifiers("INC-ORD-4471", null, "INC-ORD-4471"),
                List.of(new CandidateSystem("Payment Service", 0.86, List.of("e-log"))),
                new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM, List.of("e-cmdb")),
                List.of(new Evidence("e-log", "sumo", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471", "prod/payment")),
                List.of(new Contact("Priya Nair", "priya.nair@example.com", "confluence+gitlab",
                        "edited the runbook and committed reconcile()", "https://confluence.example.com/x", "recent")),
                List.of(), List.of("user id"), "Check payment_service.reconcile()", Confidence.MEDIUM, true);
    }

    /** Records every call so tests can assert on both invocation and arguments without
     *  a mocking framework dependency in this test class. */
    static class RecordingRunTraceRegistry implements RunTraceRegistry {
        final Map<String, TraceCollector> registered = new ConcurrentHashMap<>();
        final List<String[]> aliasCalls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public TraceCollector register(String runId, String incidentNumber) {
            TraceCollector collector = new TraceCollector();
            registered.put(runId, collector);
            return collector;
        }

        @Override
        public void alias(String runId, String incidentNumber) {
            aliasCalls.add(new String[] {runId, incidentNumber});
        }

        @Override
        public TraceCollector lookup(String runId) {
            TraceCollector collector = registered.get(runId);
            if (collector == null) {
                throw new com.company.triage.orchestration.trace.RunNotFoundException(runId);
            }
            return collector;
        }
    }

    // --- rule 4: "no header ⇒ no buffer" --------------------------------------------

    @Test
    void headerlessRunNeverTouchesTheRegistry() {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        DiagnosisResult r = orchestrator.run("INC0010005");   // the K1/no-header overload

        assertThat(registry.registered).as("K1-shaped call must allocate zero buffer entries").isEmpty();
        assertThat(registry.aliasCalls).isEmpty();
        // The response is unaffected — steps() is still populated from the ordinary,
        // unregistered TraceCollector runOnce() always builds.
        assertThat(r.report()).isNotNull();
    }

    @Test
    void twoArgOverloadWithNullRunIdBehavesIdenticallyToHeaderlessCall() {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        orchestrator.run("INC0010005", null);

        assertThat(registry.registered).isEmpty();
        assertThat(registry.aliasCalls).isEmpty();
    }

    // --- real TraceSink/TraceCollector backs a runId'd run --------------------------

    @Test
    void headerPresentRegistersARealCollectorForTheRunId() {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        DiagnosisResult r = orchestrator.run("INC0010005", "run-abc-123");

        assertThat(registry.registered).containsOnlyKeys("run-abc-123");
        assertThat(r.report()).isNotNull();
    }

    @Test
    void blankRunIdIsTreatedAsAbsent() {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        orchestrator.run("INC0010005", "   ");

        assertThat(registry.registered).isEmpty();
    }

    // --- response body identical with/without a runId (this task only touches the
    //     request side) --------------------------------------------------------------

    @Test
    void reportAndTraceAreIdenticalWithAndWithoutARunId() {
        var snow = new RecordingServiceNow();
        var registryA = new RecordingRunTraceRegistry();
        var registryB = new RecordingRunTraceRegistry();
        DiagnosisEngine engineA = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        DiagnosisEngine engineB = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));

        DiagnosisResult withoutHeader = new DiagnosisOrchestrator(engineA, engineA, snow, props(5000), registryA)
                .run("INC0010005");
        DiagnosisResult withHeader = new DiagnosisOrchestrator(engineB, engineB, snow, props(5000), registryB)
                .run("INC0010005", "run-xyz");

        assertThat(withHeader.report()).isEqualTo(withoutHeader.report());
        assertThat(withHeader.trace()).isEqualTo(withoutHeader.trace());
        assertThat(withHeader.engine()).isEqualTo(withoutHeader.engine());
        assertThat(withHeader.writebackPosted()).isEqualTo(withoutHeader.writebackPosted());
    }

    // --- rule 5: FND-31 coalescing hands B's runId to the registry ------------------

    @Test
    void coalescedCallerRunIdIsAliasedToTheCanonicalIncident() throws Exception {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
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
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisResult> first = pool.submit(() -> orchestrator.run("INC0010005", "run-A"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            Future<DiagnosisResult> second = pool.submit(() -> orchestrator.run("INC0010005", "run-B"));
            Thread.sleep(100);   // give the second caller time to reach the "already in flight" branch
            release.countDown();

            DiagnosisResult r1 = first.get(2, TimeUnit.SECONDS);
            DiagnosisResult r2 = second.get(2, TimeUnit.SECONDS);

            assertThat(engineCalls.get()).as("FND-31 coalescing must not regress").isEqualTo(1);
            assertThat(r2).isSameAs(r1);
            // Only the canonical caller (A) registers a buffer entry; B never runs an
            // engine, so it must never call register() — only alias().
            assertThat(registry.registered).containsOnlyKeys("run-A");
            assertThat(registry.aliasCalls).contains(new String[] {"run-B", "INC0010005"});
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void coalescedCallerWithNoRunIdNeverCallsAlias() throws Exception {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        AtomicInteger engineCalls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DiagnosisEngine engine = (incident, sink) -> {
            engineCalls.incrementAndGet();
            started.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisResult> first = pool.submit(() -> orchestrator.run("INC0010005", "run-A"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // K1-shaped: the coalesced (second) caller sends no runId at all.
            Future<DiagnosisResult> second = pool.submit(() -> orchestrator.run("INC0010005"));
            Thread.sleep(100);
            release.countDown();

            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            assertThat(engineCalls.get()).isEqualTo(1);
            assertThat(registry.aliasCalls).as("a caller with no runId must never alias").isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    // --- FND-76 / FND-77: every exit path completes the future and marks the buffer done ---

    /**
     * FND-76 — an {@link Error} raised on the owner thread must not leave a coalesced waiter
     * blocked forever.
     *
     * <p>{@code run()} completed its future on normal return and in {@code catch
     * (RuntimeException)}, while the {@code finally} removed the map entry unconditionally.
     * A {@code Throwable} that is not a {@code RuntimeException} escaped past both, leaving
     * the future permanently incomplete with nobody left to complete it — and {@code
     * awaitExisting} blocks in an UNTIMED {@code get()}. Since {@code IncidentPoller} is
     * single-threaded, one such waiter stops K1 polling for the rest of the process
     * lifetime, silently.
     *
     * <p>Asserts the waiter is FAILED rather than hung: a bounded {@code get()} that times
     * out is exactly the bug, so the timeout here is the assertion.
     */
    @Test
    void anErrorOnTheOwnerThreadFailsWaitersInsteadOfHangingThem() throws Exception {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        CountDownLatch ownerInside = new CountDownLatch(1);
        CountDownLatch waiterArrived = new CountDownLatch(1);

        DiagnosisEngine engine = (incident, sink) -> {
            ownerInside.countDown();
            try {
                waiterArrived.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new StackOverflowError("simulated Error escaping the run");
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> owner = pool.submit(() -> orchestrator.run("INC0010005"));
            assertThat(ownerInside.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> waiter = pool.submit(() -> orchestrator.run("INC0010005"));
            Thread.sleep(50);            // let the waiter reach awaitExisting
            waiterArrived.countDown();

            // The waiter must terminate. Before FND-76 this get() timed out — the future
            // was never completed by anyone.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> waiter.get(3, TimeUnit.SECONDS))
                    .as("a coalesced waiter must fail, not hang, when the owner dies with an Error")
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * FND-77 — a run that FAILED is still a run that is over, so its registered buffer must
     * read {@code done}.
     *
     * <p>{@code markDone()} sat only on the success path, so a failed run's buffer stayed
     * {@code done=false} until the 5-minute TTL evicted it, breaking TASK-011's invariant
     * that {@code done} agrees with the POST outcome.
     */
    @Test
    void aFailedRunStillMarksItsBufferDone() {
        var snow = new RecordingServiceNow();
        var registry = new RecordingRunTraceRegistry();
        DiagnosisEngine failing = (incident, sink) -> {
            throw new IllegalStateException("engine failed");
        };
        var orchestrator = new DiagnosisOrchestrator(failing, failing, snow, props(5000), registry);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> orchestrator.run("INC0010005", "run-abc"))
                .isInstanceOf(RuntimeException.class);

        TraceCollector collector = registry.registered.get("run-abc");
        assertThat(collector).as("the run registered a buffer").isNotNull();
        assertThat(collector.isDone())
                .as("a failed run's buffer must read done, not linger ACTIVE until TTL")
                .isTrue();
    }
}
