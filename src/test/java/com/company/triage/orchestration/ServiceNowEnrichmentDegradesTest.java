package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;
import com.company.triage.orchestration.trace.StepState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FND-90 — J14/FRI-5 for the two ServiceNow enrichment calls it named and never got.
 *
 * <p>FRI-5's mechanism lists four aborting call sites: {@code sumo.search},
 * {@code gitLab.searchCode}, {@code serviceNow.findSimilarIncidents} and
 * {@code findOwnership}. The first two were wrapped (and Confluence besides); the ServiceNow
 * pair never was, while J14 was marked <i>"Built — all six FRI rules landed"</i>.
 *
 * <p>Why this mattered more than an ordinary missing guard: this engine is <b>D2</b>, the
 * fallback the demo runbook keeps hot because <i>"the demo cannot hard-fail on stage"</i>, and
 * the orchestrator had no catch under it. A ServiceNow hiccup here produced an HTTP 500 with
 * no report — at exactly the moment the fallback was supposed to be rescuing the run.
 *
 * <p>{@code getIncident} is deliberately NOT covered by any of this: without the ticket there
 * is nothing to diagnose. These two are enrichment, and FRI-5 draws that line explicitly.
 */
class ServiceNowEnrichmentDegradesTest {

    /** A mock ServiceNow that answers getIncident normally and fails one enrichment call. */
    private static ServiceNowGateway failing(boolean onSimilar, boolean onOwnership) {
        MockServiceNowGateway delegate = new MockServiceNowGateway();
        return new ServiceNowGateway() {
            @Override public IncidentContext getIncident(String number) { return delegate.getIncident(number); }

            @Override public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
                if (onSimilar) throw new GatewayUnavailableException("ServiceNow",
                        new IllegalStateException("503 from the instance"));
                return delegate.findSimilarIncidents(incident);
            }

            @Override public Optional<ServiceOwnership> findOwnership(String applicationName) {
                if (onOwnership) throw new GatewayUnavailableException("ServiceNow",
                        new IllegalStateException("503 from the instance"));
                return delegate.findOwnership(applicationName);
            }

            @Override public List<com.company.triage.model.NewIncident> findIncidentsCreatedSince(
                    java.time.OffsetDateTime since, int limit) {
                return delegate.findIncidentsCreatedSince(since, limit);
            }

            @Override public void addWorkNote(String number, String workNote) {
                delegate.addWorkNote(number, workNote);
            }
        };
    }

    private DiagnosisResult diagnoseWith(ServiceNowGateway serviceNow, RecordingTraceSink sink) {
        return new DeterministicDiagnosisEngine(serviceNow, new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic()).diagnose("INC0010005", sink);
    }

    @Test
    void anUnreachableSimilarIncidentSearchDoesNotEndTheRun() {
        assertThatCode(() -> diagnoseWith(failing(true, false), new RecordingTraceSink()))
                .as("D2 is the fallback; a throw here became a 500 with no report at all")
                .doesNotThrowAnyException();
    }

    @Test
    void anUnreachableOwnershipLookupDoesNotEndTheRun() {
        assertThatCode(() -> diagnoseWith(failing(false, true), new RecordingTraceSink()))
                .doesNotThrowAnyException();
    }

    /** FRI-5 signal 2 — the REPORT admits the gap, not only the trace. */
    @Test
    void theReportSaysPrecedentWasNotCompared() {
        var result = diagnoseWith(failing(true, false), new RecordingTraceSink());

        assertThat(result.report().missingInformation())
                .as("'no precedent found' and 'we could not look' are opposite conclusions")
                .anyMatch(m -> m.contains("no past incidents were compared"));
    }

    @Test
    void theReportSaysOwnershipWasNotLookedUp() {
        var result = diagnoseWith(failing(false, true), new RecordingTraceSink());

        assertThat(result.report().missingInformation())
                .anyMatch(m -> m.contains("CMDB owner was not looked up"));
    }

    /** FRI-5 signal 1 — the trace line must not report a count it never obtained. */
    @Test
    void theTraceSaysCouldNotSearchRatherThanZeroHits() {
        var result = diagnoseWith(failing(true, false), new RecordingTraceSink());

        assertThat(result.trace())
                .anyMatch(l -> l.contains("findSimilarIncidents") && l.contains("COULD NOT SEARCH"))
                .noneMatch(l -> l.contains("findSimilarIncidents → 0 hits"));
    }

    /** FRI-5 signal 3 — the step resolves FAILED, so the live trace shows the degradation. */
    @Test
    void theStepResolvesFailedNotDone() {
        var sink = new RecordingTraceSink();

        diagnoseWith(failing(true, false), sink);

        assertThat(sink.terminalStates("servicenow.findSimilarIncidents"))
                .containsExactly(StepState.FAILED);
    }

    /** A degraded run must still produce a valid, renderable report — the whole point of D2. */
    @Test
    void theRunStillProducesAReport() {
        var result = diagnoseWith(failing(true, true), new RecordingTraceSink());

        assertThat(result.report()).isNotNull();
        assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");
        assertThat(result.report().candidateSystems()).isNotNull();
    }
}
