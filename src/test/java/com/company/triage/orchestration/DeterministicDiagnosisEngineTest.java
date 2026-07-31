package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.Contact;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.KnowledgeDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /**
     * FND-59: the Confluence query used to be the fixed literal
     * "checkout order payment reconcile 500" for every incident — it only ever looked
     * correct because it happened to match the one seeded demo incident's keywords via
     * MockConfluenceGateway's containment check. This pins the query to what the incident
     * actually says (shortDescription + configurationItem), same as the orderId/scope/window
     * derivations the engine already does for similar-incidents and Sumo. A spy gateway
     * captures the exact string passed, independent of what any mock's matching logic accepts.
     */
    @Test
    void confluenceQueryIsDerivedFromTheIncidentNotHardcoded() {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var delegate = new MockConfluenceGateway();
        ConfluenceGateway spy = new ConfluenceGateway() {
            @Override
            public List<KnowledgeDoc> search(String query) {
                captured.set(query);
                return delegate.search(query);   // keep the downstream evidence/justification wiring intact
            }
        };
        var engineWithSpy = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), spy,
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        engineWithSpy.diagnose("INC0012345");

        // Derived from the seeded incident's own shortDescription + configurationItem —
        // not the old hardcoded literal, which contained none of these terms.
        assertThat(captured.get())
                .contains("checkout")          // from shortDescription
                .contains("Order Portal")      // from configurationItem
                .doesNotContain("reconcile", "discount", "500");   // the old fixed literal's terms
    }
}
