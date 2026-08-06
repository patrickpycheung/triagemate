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
 * J17/PCS-4 — the orchestrator records, the poller consults.
 *
 * <p>The orchestrator is "the ONE place their calls meet", so it is the only component that can
 * see both triggers. Without this, K1 re-diagnoses an incident a presenter ran from the UI
 * seconds earlier: a second full agent pass, and a second pair of advisory comments on a ticket
 * that already has them.
 *
 * <p>The asymmetry is deliberate and is the harder half to get right. It is advisory to the
 * POLLER only — a human clicking Diagnose twice has asked for a second run and must get one.
 */
class CrossTriggerCompletionTest {

    static class OneNewIncident implements ServiceNowGateway {
        private final MockServiceNowGateway delegate = new MockServiceNowGateway();
        final List<String> notes = new ArrayList<>();
        public IncidentContext getIncident(String n) { return delegate.getIncident("INC0010005"); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) {
            return List.of(new NewIncident("INC0010005", OffsetDateTime.parse("2026-08-05T10:00:00+10:00")));
        }
        public void addWorkNote(String n, String note) { notes.add(note); }
    }

    static class CountingOrchestrator extends DiagnosisOrchestrator {
        private static final DiagnosisEngine STUB =
                (i, sink) -> new DiagnosisResult(null, new ArrayList<>(), DiagnosisResult.Engine.DETERMINISTIC);
        final List<String> runs = new ArrayList<>();
        CountingOrchestrator(ServiceNowGateway snow) { super(STUB, STUB, snow, props()); }

        @Override
        public DiagnosisResult run(String incidentNumber) {
            runs.add(incidentNumber);
            return super.run(incidentNumber);
        }
    }

    private static TriageProperties props() {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(false),
                base.orchestrator(), base.agent(),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                base.servicenow(), base.sumo(), base.gitlab());
    }

    @Test
    void thePollerSkipsAnIncidentTheOtherTriggerAlreadyDiagnosed() {
        OneNewIncident snow = new OneNewIncident();
        CountingOrchestrator orch = new CountingOrchestrator(snow);

        // The presenter clicks Diagnose (K3).
        orch.run("INC0010005");
        int afterManual = orch.runs.size();

        // The poller then picks the same incident up (K1).
        new IncidentPoller(snow, orch, props()).poll();

        assertThat(orch.runs)
                .as("K1 must not spend a second full agent pass on work K3 just did, nor post a "
                        + "second pair of advisory comments to a ticket that already has them")
                .hasSize(afterManual);
    }

    @Test
    void aHumanAskingTwiceStillGetsTwoRuns() {
        OneNewIncident snow = new OneNewIncident();
        CountingOrchestrator orch = new CountingOrchestrator(snow);

        orch.run("INC0010005");
        orch.run("INC0010005");

        assertThat(orch.runs)
                .as("advisory to the POLLER only — a presenter clicking Diagnose a second time "
                        + "on stage has asked for a second run and must get one")
                .hasSize(2);
    }

    @Test
    void completionIsRecordedUnderTheNormalisedNumberSoTriggersAgreeOnIdentity() {
        OneNewIncident snow = new OneNewIncident();
        CountingOrchestrator orch = new CountingOrchestrator(snow);

        orch.run("  inc0010005  ");   // what ServiceNow hands K1 vs what a human types

        assertThat(orch.wasRecentlyCompleted("INC0010005"))
                .as("FND-37/FND-50 normalisation, applied here too — otherwise the two triggers "
                        + "disagree about being the same ticket and the skip never fires")
                .isTrue();
    }
}
