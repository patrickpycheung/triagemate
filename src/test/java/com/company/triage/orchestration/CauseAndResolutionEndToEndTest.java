package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J28 end-to-end on the deterministic engine — the guaranteed demo path.
 *
 * <p>Covers both beats the demo needs: the incident whose precedents recorded what was wrong,
 * and the incident whose precedents did not. The second is not an edge case being tidied up —
 * real {@code close_notes} are frequently blank or "Issue resolved", so abstention is what
 * this feature does much of the time against a real queue, and it is the case the mock
 * profile could not show at all before PGC-8.
 */
class CauseAndResolutionEndToEndTest {

    private final DeterministicDiagnosisEngine engine = new DeterministicDiagnosisEngine(
            new MockServiceNowGateway(), new MockConfluenceGateway(),
            new MockSumoGateway(), new MockGitLabGateway(),
            TriagePropertiesFixture.deterministic());

    @Test
    void quotesThePriorResolutionNoteVerbatimAndWhole() {
        DiagnosisReport report = engine.diagnose("INC0010005").report();

        LikelyCause cause = report.likelyCause();
        assertThat(cause).isNotNull();

        // Asserted against the fixture rather than a hand-copied literal: a copy would keep
        // passing while the production path quietly started paraphrasing, which is the exact
        // failure the "quote, never paraphrase" rule exists to prevent.
        String fixtureNote = new MockServiceNowGateway()
                .findSimilarIncidents(new MockServiceNowGateway().getIncident("INC0010005"))
                .stream().filter(r -> r.number().equals(cause.citedArtifacts().get(0)))
                .findFirst().orElseThrow().resolutionNotes().trim();

        assertThat(cause.quotedFinding())
                .as("the note must be reproduced whole — close_notes carries cause and fix in "
                        + "one sentence and splitting it would be inference, not extraction")
                .isEqualTo(fixtureNote);
        assertThat(cause.basis()).isEqualTo(InferenceBasis.PRIOR_RESOLUTION);
        assertThat(cause.citedArtifacts()).isNotEmpty();
        assertThat(cause.supportingCount()).isLessThanOrEqualTo(cause.consideredCount());
    }

    @Test
    void theCauseCitesEvidenceThatActuallyContainsTheQuote() {
        DiagnosisReport report = engine.diagnose("INC0010005").report();
        LikelyCause cause = report.likelyCause();

        String citedSummary = report.evidence().stream()
                .filter(e -> cause.evidenceRefs().contains(e.id()))
                .map(Evidence::summary).findFirst().orElseThrow();

        assertThat(citedSummary)
                .as("CR-8 is only meaningful if the evidence carries what we quote — this is "
                        + "also what makes the Sources note one click from the claim")
                .contains(cause.quotedFinding());
    }

    @Test
    void theResolutionSectionCarriesOnlyClosedVocabularyValues() {
        DiagnosisReport report = engine.diagnose("INC0010005").report();
        LikelyResolution res = report.likelyResolution();
        assertThat(res).isNotNull();

        for (ResolutionStep step : new ResolutionStep[]{res.mitigation(), res.permanentFix()}) {
            if (step == null) continue;
            assertThat(step.verb()).isNotNull();
            assertThat(step.resolutionCode())
                    .as("close_code is a controlled vocabulary; free text must never reach the "
                            + "section that tells a human what to do")
                    .isNotBlank()
                    .doesNotContain("\n");
            assertThat(step.citedArtifact()).startsWith("INC");
        }
    }

    @Test
    void abstainsWhenNoSimilarIncidentRecordedWhatWasWrong() {
        DiagnosisReport report = engine.diagnose("INC0010009").report();

        assertThat(report.likelyCause())
                .as("similar incidents exist but none carried resolution notes — the honest "
                        + "answer is 'not established', not a guess assembled from close codes")
                .isNull();
        assertThat(report.likelyResolution())
                .as("a close CODE alone says the ticket was closed, not what was DONE — "
                        + "'Closed - No fault found' must never render as a mitigation")
                .isNull();

        assertThat(report.toDiagnosisNote())
                .contains("not established")
                .contains("similar resolved incidents recorded what was wrong");
    }

    @Test
    void theAbstainingReportIsStillAFullValidReport() {
        DiagnosisReport report = engine.diagnose("INC0010009").report();

        // The engine validates internally, so reaching here proves it. Assert the useful
        // parts survive: abstaining on cause must not degrade the rest of the diagnosis.
        assertThat(report.candidateSystems()).isNotEmpty();
        assertThat(report.evidence()).isNotEmpty();
        assertThat(report.suggestedAssignment()).isNotNull();
        assertThat(report.advisory()).isTrue();
    }
}
