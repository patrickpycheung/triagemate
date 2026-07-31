package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.Contact;
import com.company.triage.model.DiagnosisReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (offline) smoke test of the bounded flow over the mock dataset (J7).
 * Proves the demo-critical outcomes without any network or LLM.
 */
class DeterministicDiagnosisEngineTest {

    private final DeterministicDiagnosisEngine engine = new DeterministicDiagnosisEngine(
            new MockServiceNowGateway(), new MockConfluenceGateway(),
            new MockSumoGateway(), new MockGitLabGateway(),
            TriagePropertiesFixture.deterministic());

    /**
     * FND-54: the mock used to echo ANY number into the seeded context, so a stage typo
     * produced a confident, complete diagnosis of an incident that does not exist — and it
     * made FND-48's 404 unreachable in the demo config. The dataset models exactly one
     * incident; anything else must be a clean not-found.
     */
    @Test
    void unknownIncidentNumberIsRejectedNotFabricated() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.diagnose("INC9999999"))
                .isInstanceOf(com.company.triage.gateway.IncidentNotFoundException.class)
                .hasMessageContaining("INC9999999");
    }

    @Test
    void diagnosesTheSeededIncidentEndToEnd() {
        DiagnosisResult result = engine.diagnose("INC0012345");
        DiagnosisReport r = result.report();

        // Advisory, never mutating
        assertThat(r.advisory()).isTrue();

        // Correct app in the candidate shortlist
        assertThat(r.candidateSystems()).extracting("name").contains("Payment Service");

        // Correct owning team suggested
        assertThat(r.suggestedAssignment().group()).isEqualTo("Payments Platform Support");

        // Log↔code citation resolved to payment_service.py:44 (RC3)
        assertThat(r.evidence()).anySatisfy(e ->
                assertThat(e.summary()).contains("payment_service.py:44"));

        // Evidence spans multiple sources (ServiceNow + Confluence + Sumo + GitLab)
        assertThat(r.evidence()).extracting("source")
                .contains("servicenow-incident", "confluence", "sumo", "gitlab");

        // Who-to-talk-to (J9): contacts gathered from wiki authors + recent committers
        assertThat(r.suggestedContacts()).isNotEmpty();
        assertThat(r.suggestedContacts()).extracting(Contact::source)
                .anyMatch(s -> s.contains("confluence"))
                .anyMatch(s -> s.contains("gitlab"));

        // Someone in BOTH the runbook history and the file's git history is merged and
        // ranked first (Priya edited KB001234 and committed payment_service.py).
        Contact top = r.suggestedContacts().get(0);
        assertThat(top.name()).isEqualTo("Priya Nair");
        assertThat(top.source()).isEqualTo("confluence+gitlab");

        // Trace shows the tools were actually consulted
        assertThat(result.trace()).anyMatch(s -> s.startsWith("sumo.search"));
        assertThat(result.trace()).anyMatch(s -> s.startsWith("contacts:"));
    }
}
