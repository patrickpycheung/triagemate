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
                "INC0012345", OffsetDateTime.now(),
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
                "Check payment_service.reconcile()", Confidence.MEDIUM, true);
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
