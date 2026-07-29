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
                List.of(), List.of("user id"), "Check payment_service.reconcile()", Confidence.MEDIUM, true);
    }

    @Test
    void postsTwoAdvisoryCommentsSourcesFirst() {
        var snow = new RecordingServiceNow();
        DiagnosisReport report = sampleReport();
        DiagnosisEngine engine = incident -> new DiagnosisResult(report, new ArrayList<>(List.of("diagnose")));

        DiagnosisResult r = new DiagnosisOrchestrator(engine, snow, true).run("INC0012345");

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
        new DiagnosisOrchestrator(engine, snow, false).run("INC0012345");
        assertThat(snow.notes).isEmpty();
    }
}
