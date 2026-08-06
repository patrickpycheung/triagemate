package com.company.triage.orchestration.trace;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.CandidateSystem;
import com.company.triage.model.Confidence;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J16/RTR-1's load-bearing consequence: with LT4 rule 4 ("no header ⇒ no buffer") retired,
 * the unattended K1 poller DOES write to the trace registry on every tick — where before it
 * structurally could not. Rule 4 was what kept an unattended, indefinitely-running demo from
 * growing that map, so retiring it moves the entire burden onto {@link
 * InMemoryRunTraceRegistry}'s cap and TTL. This test is the proof that the bound actually
 * carries it.
 *
 * <p>Driven through {@code DiagnosisOrchestrator#run(String)} — the exact single-argument
 * overload {@code IncidentPoller} calls — rather than through {@code IncidentPollerTest},
 * whose orchestrator is a stub that overrides {@code run} and therefore never reaches a
 * registry at all; asserting entry counts there would assert nothing.
 */
class UnattendedRunBufferBoundTest {

    private static TriageProperties props() {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(false),
                new TriageProperties.Orchestrator(5000), base.agent(), base.trigger(),
                base.servicenow(), base.sumo(), base.gitlab());
    }

    static class Snow implements ServiceNowGateway {
        public IncidentContext getIncident(String n) { return null; }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String number, String note) { }
    }

    private static DiagnosisReport sampleReport() {
        return new DiagnosisReport("INC0010005", OffsetDateTime.parse("2026-07-29T12:00:00Z"),
                "Checkout order submission fails", "Order submission", "Production",
                new Identifiers("INC-ORD-4471", null, "INC-ORD-4471"),
                List.of(new CandidateSystem("Payment Service", 0.86, List.of("e-log"))),
                new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM, List.of("e-cmdb")),
                List.of(new Evidence("e-log", "sumo", "PAYMENT_RECONCILE_MISMATCH", "prod/payment")),
                List.of(), List.of(), List.of("user id"), "Check payment_service.reconcile()",
                Confidence.MEDIUM, true, null, null);
    }

    private static DiagnosisOrchestrator orchestrator(InMemoryRunTraceRegistry registry) {
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(sampleReport(), new ArrayList<>());
        return new DiagnosisOrchestrator(engine, engine, new Snow(), props(), registry);
    }

    @Test
    void oneUnattendedRunLeavesExactlyOneRegistryEntry() {
        var registry = new InMemoryRunTraceRegistry();

        orchestrator(registry).run("INC0010005");   // the K1 overload: no runId

        assertThat(registry.size())
                .as("RTR-1: an unattended run registers a buffer — exactly one, not zero and not two")
                .isEqualTo(1);
    }

    @Test
    void manyUnattendedTicksNeverExceedTheRetentionCap() {
        var registry = new InMemoryRunTraceRegistry();
        var orchestrator = orchestrator(registry);

        // Far more distinct incidents than the cap, all headerless: the shape a long-running
        // unattended demo produces now that rule 4 no longer suppresses registration.
        for (int i = 0; i < InMemoryRunTraceRegistry.MAX_RETAINED_RUNS * 5; i++) {
            orchestrator.run("INC00100%02d".formatted(i));
        }

        assertThat(registry.size())
                .as("the cap, not the trigger type, is what bounds this map now")
                .isLessThanOrEqualTo(InMemoryRunTraceRegistry.MAX_RETAINED_RUNS);
    }

    @Test
    void repeatedTicksOnTheSameIncidentAlsoStayBounded() {
        var registry = new InMemoryRunTraceRegistry();
        var orchestrator = orchestrator(registry);

        // Sequential runs of the SAME incident are deliberately separate events (they do not
        // coalesce), so each mints its own server runId — this is the K1 re-poll shape, and
        // it must not be a per-tick leak either.
        for (int i = 0; i < InMemoryRunTraceRegistry.MAX_RETAINED_RUNS * 5; i++) {
            orchestrator.run("INC0010005");
        }

        assertThat(registry.size()).isLessThanOrEqualTo(InMemoryRunTraceRegistry.MAX_RETAINED_RUNS);
    }
}
