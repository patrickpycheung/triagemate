package com.company.triage.orchestration;

import com.company.triage.config.ConnectorModeProvider;
import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import com.company.triage.orchestration.trace.InMemoryRunTraceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-016 (J11 §LT7) — the ONE test that exercises the real {@code @Autowired} 6-arg
 * {@link DiagnosisOrchestrator} constructor (the one Spring actually wires) together
 * with a real {@link ConnectorModeProvider}, closing the gap left by
 * {@code DiagnosisOrchestratorTest} (which only ever uses the 4/5-arg back-compat
 * constructors, all of which bypass {@link ConnectorModeProvider} entirely via its
 * no-arg constructor) and by the JSON-contract regression test (which mocks {@code
 * DiagnosisOrchestrator} out and only proves {@link DiagnosisResult}'s hardcoded
 * fallback map round-trips through JSON).
 *
 * <p>Kept as its own small focused class rather than folded into {@code
 * DiagnosisOrchestratorTest} (which is about the two-comment write-back flow, J5/J8)
 * or {@code ConnectorModeProviderTest} (which is a pure unit test of the provider with
 * no orchestrator involved) — this test is specifically about the wiring between them.
 */
class DiagnosisOrchestratorConnectorModesTest {

    private static TriageProperties props() {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(5000), base.agent(), base.trigger(),
                base.servicenow(), base.sumo(), base.gitlab());
    }

    static class NoOpServiceNow implements ServiceNowGateway {
        public IncidentContext getIncident(String n) { return null; }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String number, String note) { }
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
    void runResultConnectorsReflectMixedRealAndMockModesFromTheRealSixArgConstructor() {
        // FND-10 scenario: servicenow=real, everything else defaults to mock.
        MockEnvironment env = new MockEnvironment()
                .withProperty("triage.connectors.servicenow", "real");
        ConnectorModeProvider connectorModeProvider = new ConnectorModeProvider(env);

        var snow = new NoOpServiceNow();
        DiagnosisReport report = sampleReport();
        DiagnosisEngine engine = (incident, sink) -> new DiagnosisResult(report, new ArrayList<>(List.of("diagnose")));
        DiagnosisEngine unusedFallback = (incident, sink) -> { throw new AssertionError("fallback must not run"); };

        // The real @Autowired 6-arg constructor — not one of the 4/5-arg back-compat ones.
        var orchestrator = new DiagnosisOrchestrator(engine, unusedFallback, snow, props(),
                new InMemoryRunTraceRegistry(), connectorModeProvider);

        DiagnosisResult result = orchestrator.run("INC0010005");

        assertThat(result.connectors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "servicenow", "real",
                "confluence", "mock",
                "sumo", "mock",
                "gitlab", "mock"));
    }
}
