package com.company.triage.agent;

import com.company.triage.gateway.*;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J27 / FND-86 — the agent must never receive this app's own advisory notes.
 *
 * <p>FND-67 documents the app feeding on itself: once the real gateway began reading the
 * journal, a re-diagnosis drew keywords, identifiers and contact names out of its OWN prior
 * diagnosis, drifting further each run. The fix filtered {@code isAiAuthoredNote} in
 * {@code IncidentSignals} and {@code MentionedPeople} — both <b>deterministic-path</b>
 * helpers. The agent path was left uncovered: {@code getIncident()} returned the raw record
 * while {@code AdkDiagnosisEngine}'s instruction directs the model to read "the ticket
 * conversation (comments / work notes)".
 *
 * <p>This test pins the boundary, not the prompt. A prompt instruction is advice the model
 * may ignore; the tool layer is the only place the guarantee can actually hold — the same
 * reasoning that makes J8's capability bound architectural rather than instructional.
 */
class TriageMateToolsJournalFilterTest {

    private static final String AI_WORK_NOTE =
            "[AI Triage · First-pass diagnosis — advisory only]\n\n"
                    + "What appears to have happened: users receive HTTP 403 on submit\n"
                    + "Likely involved systems: Order Portal (82%), Identity Gateway (68%)";

    private static final String AI_SOURCES_NOTE =
            "svc.triage: [AI Triage · Sources consulted]\nEvidence gathered for this incident";

    /** Serves one incident whose journals already contain a previous run's output. */
    static class SelfPoisonedServiceNow implements ServiceNowGateway {
        public IncidentContext getIncident(String n) {
            return new IncidentContext(
                    n, "Users cannot submit orders", "Submitting an order returns 403.",
                    "a.caller", "Software", "Application",
                    OffsetDateTime.parse("2026-08-05T10:00:00+10:00"),
                    "Production", "Service Desk",
                    List.of("Customer rang again, still failing.", AI_SOURCES_NOTE),
                    List.of(AI_WORK_NOTE, "Checked the gateway logs, nothing obvious."),
                    "Order Portal", List.of());
        }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String n, String note) {}
    }

    static class NoopConfluence implements ConfluenceGateway {
        public List<KnowledgeDoc> search(String query) { return List.of(); }
    }
    static class NoopSumo implements SumoGateway {
        public List<LogEvidence> search(LogSearchRequest r) { return List.of(); }
    }
    static class NoopGitLab implements GitLabGateway {
        public List<CodeSearchResult> searchCode(String project, String term) { return List.of(); }
    }

    private void wire() {
        TriageMateTools.wire(new SelfPoisonedServiceNow(), new NoopConfluence(), new NoopSumo(),
                new NoopGitLab(),
                new com.company.triage.config.TriageProperties.Sumo(
                        "IDT/{project}/{environment}", java.util.Map.of(), "Global",
                        List.of("prod"), 20, 30),
                List.of("order-payments/payment-service"));
        TriageMateTools.bindIncident("INC0010005");
    }

    @Test
    void theAgentNeverSeesThisAppsOwnNotes() {
        wire();
        try {
            IncidentContext seenByModel = TriageMateTools.getIncident();

            assertThat(seenByModel.workNotes())
                    .as("a prior run's diagnosis must not reach the model as ticket conversation")
                    .noneMatch(DiagnosisReport::isAiAuthoredNote)
                    .containsExactly("Checked the gateway logs, nothing obvious.");

            assertThat(seenByModel.comments())
                    .as("the sources note is ours too — and the gateway prefixes each line with "
                            + "its author, so the marker is not at position 0")
                    .noneMatch(DiagnosisReport::isAiAuthoredNote)
                    .containsExactly("Customer rang again, still failing.");
        } finally {
            TriageMateTools.clearIncident();
        }
    }

    @Test
    void humanContentAndTheRestOfTheRecordSurvive() {
        wire();
        try {
            IncidentContext seenByModel = TriageMateTools.getIncident();

            assertThat(seenByModel.shortDescription()).isEqualTo("Users cannot submit orders");
            assertThat(seenByModel.configurationItem()).isEqualTo("Order Portal");
            assertThat(seenByModel.environment()).isEqualTo("Production");
            assertThat(seenByModel.comments()).hasSize(1);
            assertThat(seenByModel.workNotes()).hasSize(1);
        } finally {
            TriageMateTools.clearIncident();
        }
    }
}
