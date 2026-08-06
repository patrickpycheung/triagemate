package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.Evidence;
import com.company.triage.model.KnowledgeDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J25/KQR-2 — a returned Confluence page must clear a relevance floor before it is cited.
 *
 * <p>KQR-1 fixed the query (`siteSearch ~`, which is what backs Confluence's own UI search,
 * rather than `text ~`, which has no relevance ranking). This bounds what we do with the
 * answer. Against the live AusPost instance a five-result response for a delivery-hazards
 * incident included a Teradata data-model PDF, and <b>all five were cited as evidence</b>.
 *
 * <p>Both directions matter and both are pinned here. Dropping the junk is the point, but the
 * first cut of the scorer also dropped the demo's own runbook — an over-strict filter is not
 * a safer failure, it is a quieter one: the report simply stops citing the page that explains
 * the incident, and nothing says so.
 */
class KnowledgeRelevanceFloorTest {

    private DeterministicDiagnosisEngine engineReturning(List<KnowledgeDoc> pages) {
        ConfluenceGateway stub = query -> pages;
        return new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), stub, new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());
    }

    private static final KnowledgeDoc RELEVANT = new KnowledgeDoc(
            "KB001234",
            "Order Payment Reconciliation — Known Errors & Runbook",
            "https://confluence.example.com/x",
            "PAYMENT_RECONCILE_MISMATCH means the expected total and the charged amount diverged.");

    /** The real one, from the live instance: a data-model appendix that shares no terms. */
    private static final KnowledgeDoc IRRELEVANT = new KnowledgeDoc(
            "KB009999",
            "Teradata Physical Data Model — Appendix C",
            "https://confluence.example.com/y",
            "Column definitions and partitioning strategy for the enterprise warehouse.");

    @Test
    void anUnrelatedPageIsNotCitedAsEvidence() {
        DiagnosisReport report = engineReturning(List.of(IRRELEVANT)).diagnose("INC0010005").report();

        assertThat(report.evidence())
                .extracting(Evidence::id)
                .as("a page sharing no terms with the system or the symptom is not evidence, "
                        + "however confidently Confluence ranked it")
                .doesNotContain("e-kb-KB009999");
    }

    @Test
    void theRunbookThatExplainsTheIncidentIsStillCited() {
        DiagnosisReport report = engineReturning(List.of(RELEVANT)).diagnose("INC0010005").report();

        assertThat(report.evidence())
                .extracting(Evidence::id)
                .as("over-strict is not a safe failure — it silently removes the one page that "
                        + "explains the incident")
                .contains("e-kb-KB001234");
    }

    @Test
    void theRelevantPageSurvivesAlongsideTheIrrelevantOne() {
        DiagnosisReport report =
                engineReturning(List.of(IRRELEVANT, RELEVANT)).diagnose("INC0010005").report();

        assertThat(report.evidence()).extracting(Evidence::id)
                .contains("e-kb-KB001234")
                .doesNotContain("e-kb-KB009999");
    }

    @Test
    void whenNothingClearsTheFloorTheReportSaysSoRatherThanLookingLikeAnEmptySearch() {
        DiagnosisReport report = engineReturning(List.of(IRRELEVANT)).diagnose("INC0010005").report();

        assertThat(report.missingInformation())
                .as("'no runbook exists' and 'runbooks came back and none matched' are different "
                        + "facts, and only one of them is a reason to go looking manually")
                .anyMatch(m -> m.contains("none matched the symptom terms"));
    }

    @Test
    void anEmptySearchDoesNotClaimPagesWereFilteredOut() {
        DiagnosisReport report = engineReturning(List.of()).diagnose("INC0010005").report();

        assertThat(report.missingInformation())
                .noneMatch(m -> m.contains("none matched the symptom terms"));
    }
}
