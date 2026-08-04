package com.company.triage.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FND-17: J4 always claimed "a validator the agent's final step must satisfy" that
 * "rejects a report with an empty candidateSystems or a dangling evidenceRef" — proves
 * that claim is now true. Jackson deserialization alone accepts schema-shaped JSON that
 * violates both.
 */
class DiagnosisReportValidatorTest {

    private static final Evidence E1 = new Evidence("e1", "sumo", "log line", "link");
    private static final Evidence E2 = new Evidence("e2", "confluence", "kb article", "link");

    private DiagnosisReport report(List<CandidateSystem> candidates, List<Evidence> evidence,
                                    SuggestedAssignment assignment) {
        return new DiagnosisReport("INC0010005", OffsetDateTime.now(), "symptom", "function",
                "Production", new Identifiers("id", null, "id"), candidates,
                assignment, evidence, List.of(), List.of(), List.of(),
                "next action", Confidence.MEDIUM, true);
    }

    @Test
    void aWellFormedReportPasses() {
        var report = report(
                List.of(new CandidateSystem("Order Portal", 0.8, List.of("e1"))),
                List.of(E1, E2),
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e2")));

        assertThatCode(() -> DiagnosisReportValidator.validate(report)).doesNotThrowAnyException();
    }

    @Test
    void emptyCandidateSystemsIsRejected() {
        var report = report(List.of(), List.of(E1),
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e1")));

        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report))
                .isInstanceOf(DiagnosisReportInvalidException.class)
                .hasMessageContaining("candidateSystems is empty");
    }

    @Test
    void emptyEvidenceIsRejected() {
        var report = report(List.of(new CandidateSystem("Order Portal", 0.8, List.of())),
                List.of(), new SuggestedAssignment("Team", Confidence.MEDIUM, List.of()));

        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report))
                .hasMessageContaining("evidence is empty");
    }

    @Test
    void danglingEvidenceRefOnACandidateIsRejected() {
        var report = report(
                List.of(new CandidateSystem("Order Portal", 0.8, List.of("e1", "e99"))),
                List.of(E1),   // e99 does not exist
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e1")));

        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report))
                .hasMessageContaining("dangling evidenceRef 'e99'")
                .hasMessageContaining("Order Portal");
    }

    @Test
    void danglingEvidenceRefOnSuggestedAssignmentIsRejected() {
        var report = report(
                List.of(new CandidateSystem("Order Portal", 0.8, List.of("e1"))),
                List.of(E1),
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e-nonexistent")));

        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report))
                .hasMessageContaining("dangling evidenceRef 'e-nonexistent'")
                .hasMessageContaining("suggestedAssignment");
    }

    /** A model retrying on one message benefits from seeing every problem, not just the first. */
    @Test
    void multipleProblemsAreAllReportedTogether() {
        var report = report(List.of(), List.of(),
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e-ghost")));

        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report))
                .hasMessageContaining("candidateSystems is empty")
                .hasMessageContaining("evidence is empty")
                .hasMessageContaining("dangling evidenceRef 'e-ghost'");
    }

    @Test
    void nullEvidenceRefsListIsTreatedAsEmptyNotAsAnError() {
        var report = report(
                List.of(new CandidateSystem("Order Portal", 0.8, null)),
                List.of(E1), null);

        assertThatCode(() -> DiagnosisReportValidator.validate(report)).doesNotThrowAnyException();
    }
}
