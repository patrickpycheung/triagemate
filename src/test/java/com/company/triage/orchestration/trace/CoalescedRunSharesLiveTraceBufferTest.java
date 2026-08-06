package com.company.triage.orchestration.trace;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.CandidateSystem;
import com.company.triage.model.Confidence;
import com.company.triage.model.Contact;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.Evidence;
import com.company.triage.model.Identifiers;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.NewIncident;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;
import com.company.triage.model.SuggestedAssignment;
import com.company.triage.orchestration.DiagnosisEngine;
import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisResult;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-010, requirement 6: a coalesced caller B (TASK-009's FND-31 {@code alias()} capture)
 * must read the exact SAME live segment as canonical caller A — not merely finish with an
 * equal-looking {@code DiagnosisResult}. This lives in the {@code trace} package (rather than
 * extending {@code DiagnosisOrchestratorTest}/{@code DiagnosisOrchestratorRunIdTest} directly)
 * so it can use the real, now-bounded {@link InMemoryRunTraceRegistry} and its
 * package-private {@code peek()} to inspect BOTH runIds' views into the buffer independently
 * — including mid-run, before the shared {@code DiagnosisResult} object would trivially make
 * "parity" true by object identity alone.
 */
class CoalescedRunSharesLiveTraceBufferTest {

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
                List.of(), List.of("user id"), "Check payment_service.reconcile()", Confidence.MEDIUM, true, null, null);
    }

    @Test
    void coalescedCallerBReadsTheSameLiveSegmentAsCanonicalCallerA() throws Exception {
        var snow = new RecordingServiceNow();
        var registry = new InMemoryRunTraceRegistry();
        CountDownLatch beforeStepWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        DiagnosisEngine engine = (incident, sink) -> {
            sink.before(new TraceStep(0, 0, "call-1", Platform.SERVICENOW, "getIncident",
                    "Looking up incident", null, StepState.ACTIVE, System.currentTimeMillis(), null,
                    DiagnosisResult.Engine.DETERMINISTIC));
            beforeStepWritten.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).as("release latch reached").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.after(new TraceStep(0, 0, "call-1", Platform.SERVICENOW, "getIncident",
                    "Looking up incident", "Found incident", StepState.DONE, System.currentTimeMillis(), 5L,
                    DiagnosisResult.Engine.DETERMINISTIC));
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DiagnosisResult> callerA = pool.submit(() -> orchestrator.run("INC0010005", "run-A"));
            assertThat(beforeStepWritten.await(2, TimeUnit.SECONDS)).isTrue();

            // Caller A's mid-run buffer already shows the in-progress ACTIVE step.
            TraceCollector viewFromAMidRun = registry.peek("run-A");
            assertThat(viewFromAMidRun).isNotNull();
            assertThat(viewFromAMidRun.steps()).hasSize(1);
            assertThat(viewFromAMidRun.steps().get(0).state()).isEqualTo(StepState.ACTIVE);

            // Caller B coalesces onto the same in-flight incident (DiagnosisOrchestrator's
            // inFlight map) and, per TASK-009, calls alias("run-B", incidentNumber).
            Future<DiagnosisResult> callerB = pool.submit(() -> orchestrator.run("INC0010005", "run-B"));
            Thread.sleep(100); // let B reach the awaitExisting/alias branch — same pattern used
                                // by DiagnosisOrchestratorRunIdTest's existing coalescing tests

            TraceCollector viewFromA = registry.peek("run-A");
            TraceCollector viewFromB = registry.peek("run-B");
            assertThat(viewFromB)
                    .as("B must be aliased onto A's live collector, not a fresh/empty one")
                    .isSameAs(viewFromA);
            assertThat(viewFromB.steps())
                    .as("B must already observe A's in-progress step, not a stale/empty snapshot")
                    .isEqualTo(viewFromA.steps());

            // Release the engine; the "after" write lands in the shared segment.
            release.countDown();
            DiagnosisResult resultA = callerA.get(2, TimeUnit.SECONDS);
            DiagnosisResult resultB = callerB.get(2, TimeUnit.SECONDS);

            assertThat(resultB).isSameAs(resultA); // FND-31 coalescing itself, unregressed

            // Parity through the REGISTRY (not just DiagnosisResult identity, which would
            // pass trivially even if the buffer mapping had gone stale): both runIds must
            // still resolve to a collector holding the completed step.
            assertThat(registry.peek("run-B").steps()).isEqualTo(registry.peek("run-A").steps());
            assertThat(registry.peek("run-A").steps()).hasSize(1); // after replaced before by callId
            assertThat(registry.peek("run-A").steps().get(0).state()).isEqualTo(StepState.DONE);
            assertThat(registry.peek("run-A").steps().get(0).result()).isEqualTo("Found incident");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * J16/RTR-5 — the shape no existing test covered: a <b>headerless owner</b> (the K1
     * poller, which sends no {@code X-Triage-Run-Id}) with a <b>runId-bearing waiter</b> (a
     * presenter clicking Diagnose mid-poll). This is the documented FND-14/FND-31
     * mixed-trigger case, and under the retired LT4 rule 4 it was the one arrangement where
     * coalescing produced NO live trace at all: the owner registered nothing, so the waiter
     * had nothing to alias onto and its poll returned an empty buffer forever while a real
     * run was in progress behind it.
     *
     * <p>Under RTR-1 the owner runs beneath a server-minted {@code srv-} runId, so RTR-2's
     * alias has a real target. Asserted MID-RUN, before any shared {@code DiagnosisResult}
     * exists, so nothing here can pass by result identity alone.
     */
    @Test
    void waiterOnAHeaderlessOwnerReadsTheOwnersLiveSegment() throws Exception {
        var snow = new RecordingServiceNow();
        var registry = new InMemoryRunTraceRegistry();
        CountDownLatch beforeStepWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        DiagnosisEngine engine = (incident, sink) -> {
            sink.before(new TraceStep(0, 0, "call-1", Platform.SERVICENOW, "getIncident",
                    "Looking up incident", null, StepState.ACTIVE, System.currentTimeMillis(), null,
                    DiagnosisResult.Engine.DETERMINISTIC));
            beforeStepWritten.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).as("release latch reached").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.after(new TraceStep(0, 0, "call-1", Platform.SERVICENOW, "getIncident",
                    "Looking up incident", "Found incident", StepState.DONE, System.currentTimeMillis(), 5L,
                    DiagnosisResult.Engine.DETERMINISTIC));
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("diagnose")));
        };
        var orchestrator = new DiagnosisOrchestrator(engine, engine, snow, props(5000), registry);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Owner: the K1 shape — the single-arg overload, no runId anywhere.
            Future<DiagnosisResult> owner = pool.submit(() -> orchestrator.run("INC0010005"));
            assertThat(beforeStepWritten.await(2, TimeUnit.SECONDS)).isTrue();

            // Waiter: the K3 shape — a browser-minted runId it intends to poll on.
            Future<DiagnosisResult> waiter = pool.submit(() -> orchestrator.run("INC0010005", "run-B"));
            Thread.sleep(150); // let the waiter reach the aliasTo/awaitExisting branch

            TraceCollector viewFromWaiter = registry.peek("run-B");
            assertThat(viewFromWaiter)
                    .as("RTR-1+RTR-2: the waiter must be bound to the headerless owner's live buffer")
                    .isNotNull();
            assertThat(viewFromWaiter.steps())
                    .as("and must already see the owner's in-progress step, mid-run")
                    .hasSize(1);
            assertThat(viewFromWaiter.steps().get(0).state()).isEqualTo(StepState.ACTIVE);
            assertThat(viewFromWaiter.isDone())
                    .as("the shared buffer is still live while the owner runs").isFalse();

            release.countDown();
            DiagnosisResult ownerResult = owner.get(2, TimeUnit.SECONDS);
            DiagnosisResult waiterResult = waiter.get(2, TimeUnit.SECONDS);

            assertThat(waiterResult).as("FND-31 coalescing itself, unregressed").isSameAs(ownerResult);
            assertThat(registry.peek("run-B").steps()).hasSize(1);
            assertThat(registry.peek("run-B").steps().get(0).state()).isEqualTo(StepState.DONE);
            assertThat(registry.peek("run-B").isDone())
                    .as("and the waiter observes the run finishing through the same buffer").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
