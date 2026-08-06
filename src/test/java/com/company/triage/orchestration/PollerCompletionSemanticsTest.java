package com.company.triage.orchestration;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.mock.MockServiceNowGateway;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J17/PCS-1, PCS-2, PCS-5 — an unattended run is complete when it is diagnosed AND delivered.
 *
 * <p>On the K1 path there is no UI and no human: the two advisory comments <b>are</b> the entire
 * output. Completion used to mean "{@code orchestrator.run()} returned without throwing", which
 * is equally true of a run that delivered nothing — and marking that complete retired the
 * incident from the poller permanently, discarding work that had already been paid for with a
 * full agent pass.
 */
class PollerCompletionSemanticsTest {

    /** Serves one new incident; its work-note writes fail the first {@code failures} times. */
    static class FlakyWriteSnow implements ServiceNowGateway {
        private final MockServiceNowGateway delegate = new MockServiceNowGateway();
        private int failuresLeft;
        final List<String> posted = new ArrayList<>();

        FlakyWriteSnow(int failures) { this.failuresLeft = failures; }

        public IncidentContext getIncident(String n) { return delegate.getIncident(n); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) {
            return delegate.findSimilarIncidents(c);
        }
        public Optional<ServiceOwnership> findOwnership(String a) { return delegate.findOwnership(a); }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
            return List.of(new NewIncident("INC0010005",
                    OffsetDateTime.parse("2026-08-05T10:00:00+10:00")));
        }
        public void addWorkNote(String number, String note) {
            if (failuresLeft-- > 0) throw new IllegalStateException("ServiceNow 503");
            posted.add(note);
        }
    }

    /** Counts engine runs and reports delivery as failed, holding a real report. */
    static class UndeliveringOrchestrator extends DiagnosisOrchestrator {
        private static final DiagnosisEngine STUB =
                (i, sink) -> new DiagnosisResult(null, new ArrayList<>(), DiagnosisResult.Engine.DETERMINISTIC);
        final List<String> diagnosed = new ArrayList<>();

        UndeliveringOrchestrator(ServiceNowGateway snow) {
            super(STUB, STUB, snow, props());
        }

        @Override
        public DiagnosisResult run(String incidentNumber) {
            diagnosed.add(incidentNumber);
            return new DiagnosisResult(sampleReport(), new ArrayList<>(List.of("fake")),
                    DiagnosisResult.Engine.DETERMINISTIC, false);   // diagnosed, NOT delivered
        }
    }

    private static TriageProperties props() {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(false),
                base.orchestrator(), base.agent(),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                base.servicenow(), base.sumo(), base.gitlab());
    }

    private static DiagnosisReport sampleReport() {
        return new DiagnosisReport("INC0010005", OffsetDateTime.now(), "symptom", "fn",
                "Production", new Identifiers("id", null, "id"),
                List.of(new CandidateSystem("Payment Service", 0.8, List.of("e-log"))),
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e-log")),
                List.of(new Evidence("e-log", "sumo", "line", "l")),
                List.of(), List.of(), List.of(), "next", Confidence.MEDIUM, true, null, null);
    }

    @Test
    void aDiagnosedButUndeliveredIncidentIsNotRetiredAsComplete() {
        FlakyWriteSnow snow = new FlakyWriteSnow(Integer.MAX_VALUE);   // never delivers
        UndeliveringOrchestrator orch = new UndeliveringOrchestrator(snow);
        IncidentPoller p = new IncidentPoller(snow, orch, props());

        p.poll();

        assertThat(orch.diagnosed)
                .as("it was diagnosed once")
                .containsExactly("INC0010005");
        assertThat(snow.posted)
                .as("and delivered nothing — which is the case that used to count as complete")
                .isEmpty();
    }

    @Test
    void aHeldDiagnosisIsRedeliveredWithoutRunningTheEngineAgain() {
        // 0 seeded failures: the orchestrator stub reports the delivery as failed without
        // itself calling addWorkNote, so this models "ServiceNow recovered by the next tick".
        FlakyWriteSnow snow = new FlakyWriteSnow(0);
        UndeliveringOrchestrator orch = new UndeliveringOrchestrator(snow);
        IncidentPoller p = new IncidentPoller(snow, orch, props());

        p.poll();                                   // diagnose → delivery fails → held
        int runsAfterFirstTick = orch.diagnosed.size();

        p.poll();                                   // redelivery, from the held report

        assertThat(snow.posted)
                .as("both advisory comments eventually reach the ticket")
                .hasSize(2);
        assertThat(orch.diagnosed)
                .as("redelivery must NOT re-run the engine: the report is already in hand, and "
                        + "a re-run spends another live agent pass to reproduce it")
                .hasSize(runsAfterFirstTick);
    }

    @Test
    void redeliverySendsTheSourcesCommentBeforeTheDiagnosis() {
        FlakyWriteSnow snow = new FlakyWriteSnow(0);
        UndeliveringOrchestrator orch = new UndeliveringOrchestrator(snow);
        IncidentPoller p = new IncidentPoller(snow, orch, props());

        p.poll();
        p.poll();

        assertThat(snow.posted.get(0))
                .as("sources first, so the diagnosis that cites them is auditable — the same "
                        + "order the live path uses")
                .contains("Sources consulted");
        assertThat(snow.posted.get(1)).contains("First-pass diagnosis");
    }
}
