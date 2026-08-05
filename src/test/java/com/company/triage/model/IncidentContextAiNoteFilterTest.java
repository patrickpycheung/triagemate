package com.company.triage.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J27 / FND-86: the app must not read its own advisory notes back as human conversation.
 *
 * <p>FND-67 established this for the deterministic path. The ADK path was never covered —
 * {@code TriageMateTools.getIncident()} handed the raw {@link IncidentContext} to the model
 * while {@code AdkDiagnosisEngine}'s instruction explicitly directs it to read "the ticket
 * conversation (comments / work notes)". These tests pin the filtering primitive that closes
 * it at the tool boundary.
 */
class IncidentContextAiNoteFilterTest {

    private static IncidentContext withJournals(List<String> comments, List<String> workNotes) {
        return new IncidentContext(
                "INC0010005", "Users cannot submit orders", "detail", "a.caller",
                "Software", "Application", OffsetDateTime.parse("2026-08-05T10:00:00+10:00"),
                "Production", "Service Desk", comments, workNotes, "Order Portal", List.of());
    }

    @Test
    void stripsThisAppsOwnNotesAndKeepsHumanOnes() {
        IncidentContext raw = withJournals(
                List.of("Customer rang again, still failing."),
                List.of("[AI Triage · First-pass diagnosis — advisory only]\n\nWhat appears to have happened: 403 on submit",
                        "Checked the gateway logs, nothing obvious."));

        IncidentContext filtered = raw.withoutAiAuthoredNotes();

        assertThat(filtered.workNotes())
                .as("the app's own advisory note must not come back as ticket conversation")
                .containsExactly("Checked the gateway logs, nothing obvious.");
        assertThat(filtered.comments())
                .as("human comments are untouched")
                .containsExactly("Customer rang again, still failing.");
    }

    @Test
    void matchesThePrefixAnywhereBecauseTheGatewayPrependsTheAuthor() {
        // RealServiceNowGateway renders each journal line as "sys_created_by: <text>",
        // so the marker is not at position 0.
        IncidentContext raw = withJournals(
                List.of("svc.triage: [AI Triage · Sources consulted]\nEvidence gathered…"),
                List.of());

        assertThat(raw.withoutAiAuthoredNotes().comments()).isEmpty();
    }

    @Test
    void filtersBothJournalsIndependentlyAndIsNullSafe() {
        IncidentContext raw = withJournals(null, null);

        IncidentContext filtered = raw.withoutAiAuthoredNotes();

        assertThat(filtered.comments()).isNull();
        assertThat(filtered.workNotes()).isNull();
    }

    @Test
    void leavesEverythingElseOnTheRecordUntouched() {
        IncidentContext raw = withJournals(List.of("human"), List.of("human too"));

        IncidentContext filtered = raw.withoutAiAuthoredNotes();

        assertThat(filtered.number()).isEqualTo(raw.number());
        assertThat(filtered.shortDescription()).isEqualTo(raw.shortDescription());
        assertThat(filtered.configurationItem()).isEqualTo(raw.configurationItem());
        assertThat(filtered.openedAt()).isEqualTo(raw.openedAt());
    }
}
