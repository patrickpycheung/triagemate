package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the automatic (no-human) two-comment write-back: sources first, then diagnosis (J5/J8). */
class DiagnosisOrchestratorTest {

    /** Records the work notes posted, in order. */
    static class RecordingServiceNow implements ServiceNowGateway {
        final List<String> notes = new ArrayList<>();
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

        DiagnosisResult r = new DiagnosisOrchestrator(engine, unusedFallback, snow, true).run("INC0012345");

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
        new DiagnosisOrchestrator(engine, unusedFallback, snow, false).run("INC0012345");
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

        DiagnosisResult r = new DiagnosisOrchestrator(failingPrimary, fallback, snow, true).run("INC0012345");

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

        var orchestrator = new DiagnosisOrchestrator(onlyEngine, onlyEngine, snow, true);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> orchestrator.run("INC0012345"));
    }
}
