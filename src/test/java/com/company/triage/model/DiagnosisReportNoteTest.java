package com.company.triage.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the J4 → ServiceNow rendering contract, in particular the one field that must
 * <b>not</b> cross that boundary.
 */
class DiagnosisReportNoteTest {

    private DiagnosisReport reportWithContacts() {
        return new DiagnosisReport(
                "INC0010005", OffsetDateTime.now(),
                "Checkout order submission fails", "Order submission", "Production",
                new Identifiers("INC-ORD-4471", null, "INC-ORD-4471"),
                List.of(new CandidateSystem("Payment Service", 0.86, List.of("e-log"))),
                new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM, List.of("e-cmdb")),
                List.of(new Evidence("e-log", "sumo",
                        "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471", "prod/payment")),
                List.of(
                        new Contact("Priya Nair", "priya.nair@example.com", "confluence+gitlab",
                                "edited the runbook and committed reconcile()",
                                "https://confluence.example.com/x", "recent"),
                        new Contact("Sam Okafor", "sam.okafor@example.com", "gitlab",
                                "last committed payment_service.py",
                                "https://gitlab.example.com/y", "recent")),
                List.of(), List.of("user id"),
                "Check payment_service.reconcile()", Confidence.MEDIUM, true, null, null);
    }

    /**
     * The next actions render as a NUMBERED worklist, not one run-on line, and every step is
     * something the report already established.
     */
    @Test
    void nextActionsAreNumberedAndDrawnFromEvidencedFields() {
        DiagnosisReport r = withResolution(
                new LikelyResolution(
                        new ResolutionStep(ResolutionVerb.CONSULT_RUNBOOK, "Resolved - Known Error",
                                "INC0011455", List.of("e-sim-INC0011455")),
                        new ResolutionStep(ResolutionVerb.CHECK, "Resolved - Code Fix",
                                "INC0011902", List.of("e-sim-INC0011902"))));

        String note = r.toDiagnosisNote();

        assertThat(note).contains("Recommended next actions:");
        assertThat(note).contains("1. Check payment_service.reconcile()");
        // Precedent steps cite the ticket they came from — a step a reader can check.
        assertThat(note).contains("2. Mitigation: similar incidents were closed as "
                + "Resolved - Known Error — see INC0011455.");
        assertThat(note).contains("3. Permanent fix: similar incidents were closed as "
                + "Resolved - Code Fix — see INC0011902.");
        // The single-sentence form this replaced.
        assertThat(note).doesNotContain("Recommended next check:");
    }

    /** A thin run yields a SHORT list, never padded with filler steps. */
    @Test
    void anAbsentResolutionContributesNoSteps() {
        String note = withResolution(null).toDiagnosisNote();

        assertThat(note).contains("1. Check payment_service.reconcile()");
        assertThat(note).doesNotContain("similar incidents were closed as");
    }

    /**
     * Gap steps are derived from the STRUCTURED fields, not from the missingInformation
     * prose — which mixes absent data with disclosures about the run ("N other systems
     * appeared but nothing evidences them"), and an imperative in front of a disclosure is a
     * step nobody can perform.
     */
    @Test
    void gapStepsComeFromStructuredFieldsNotFromTheProseList() {
        // The base report HAS both a correlation id and an environment, so neither gap step
        // applies — the list stays at the one thing actually worth doing.
        String complete = withResolution(null).toDiagnosisNote();
        assertThat(complete).doesNotContain("Capture a correlation/transaction id");
        assertThat(complete).doesNotContain("Confirm the environment");

        // Strip both and the steps appear — driven by the FIELDS, not by the prose list,
        // whose contents are identical in the two cases.
        String thin = withoutCorrelationIdOrEnvironment().toDiagnosisNote();
        assertThat(thin).contains("Capture a correlation/transaction id");
        assertThat(thin).contains("Confirm the environment");

        // Neither report turns a disclosure-shaped missingInformation entry into an order.
        assertThat(complete).doesNotContain("Fill the gap");
        assertThat(thin).doesNotContain("Fill the gap");
    }

    private DiagnosisReport withoutCorrelationIdOrEnvironment() {
        DiagnosisReport b = reportWithContacts();
        return new DiagnosisReport(b.incidentNumber(), b.generatedAt(), b.reportedSymptom(),
                b.affectedFunction(), null,
                new Identifiers(null, b.identifiers().errorCode(), b.identifiers().orderId()),
                b.candidateSystems(), b.suggestedAssignment(), b.evidence(), b.suggestedContacts(),
                b.contradictingEvidence(), b.missingInformation(), b.recommendedNextAction(),
                b.confidenceOverall(), b.advisory(), b.likelyCause(), b.likelyResolution());
    }

    /** Gaps list one per line: each is separately actionable, and a comma-joined run wraps. */
    @Test
    void missingInformationIsListedOnePerLine() {
        String note = withResolution(null).toDiagnosisNote();

        assertThat(note).contains("Still missing:\n  - user id");
    }

    private DiagnosisReport withResolution(LikelyResolution resolution) {
        DiagnosisReport base = reportWithContacts();
        return new DiagnosisReport(base.incidentNumber(), base.generatedAt(),
                base.reportedSymptom(), base.affectedFunction(), base.environment(),
                base.identifiers(), base.candidateSystems(), base.suggestedAssignment(),
                base.evidence(), base.suggestedContacts(), base.contradictingEvidence(),
                base.missingInformation(), base.recommendedNextAction(), base.confidenceOverall(),
                base.advisory(), base.likelyCause(), resolution);
    }

    /**
     * FND-2 — {@code suggestedContacts} (J9) is UI-only and must never be rendered into
     * either ServiceNow comment.
     *
     * <p>Naming individuals in an incident journal is customer-visible and permanently
     * retained, and these names were inferred from wiki edits and commit history — not from
     * any statement about fault. Before this test the exclusion held only because neither
     * note-builder happened to reference the field: correct by coincidence, not by contract.
     * This test is what makes it an invariant, so a future edit that helpfully adds
     * "who to talk to" into the work note fails here instead of leaking names to a customer.
     */
    @Test
    void suggestedContactsNeverAppearInServiceNowNotes() {
        DiagnosisReport r = reportWithContacts();
        String sources = r.toSourcesNote();
        String diagnosis = r.toDiagnosisNote();

        for (Contact c : r.suggestedContacts()) {
            assertThat(sources)
                    .as("contact name '%s' must not reach the ServiceNow sources note", c.name())
                    .doesNotContain(c.name());
            assertThat(diagnosis)
                    .as("contact name '%s' must not reach the ServiceNow diagnosis note", c.name())
                    .doesNotContain(c.name());
            if (c.handle() != null) {
                assertThat(sources).doesNotContain(c.handle());
                assertThat(diagnosis).doesNotContain(c.handle());
            }
        }
    }

    /** The things that SHOULD be in the notes, so the test above can't pass by rendering nothing. */
    @Test
    void notesStillCarryEvidenceAndAssignment() {
        DiagnosisReport r = reportWithContacts();

        assertThat(r.toSourcesNote())
                .contains("Sources consulted")
                .contains("PAYMENT_RECONCILE_MISMATCH");     // the evidence did render
        assertThat(r.toDiagnosisNote())
                .contains("First-pass diagnosis")
                .contains("Payments Platform Support")        // the assignment did render
                .contains("advisory");
    }
}
