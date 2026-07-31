package com.company.triage.orchestration;

import com.company.triage.model.Contact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-64: names extracted from free text (ServiceNow ticket prose, Confluence page bodies) —
 * the two J9 sources that were previously ignored in favour of API metadata alone.
 *
 * <p>The risk here is precision, not recall: a false positive sends an engineer to bother an
 * uninvolved colleague, and system names ("Payment Service", "Order Portal") have exactly the
 * same shape as person names. Most of these cases are about NOT extracting things.
 */
class MentionedPeopleTest {

    private static final Set<String> KNOWN_SYSTEMS =
            Set.of("Order Portal", "Payment Service", "Payments Platform Support");

    @Test
    void extractsPeopleNamedInProse() {
        assertThat(MentionedPeople.namesIn(
                "Escalated after speaking with Priya Nair in Payments.", KNOWN_SYSTEMS))
                .contains("Priya Nair");
    }

    /** The whole reason the denylist exists. */
    @Test
    void doesNotMistakeSystemsTeamsOrProcessesForPeople() {
        var text = "Order Portal calls Payment Service; owned by Payments Platform Support. "
                + "Raised via Service Desk against Known Error KB001234 in Production.";
        assertThat(MentionedPeople.namesIn(text, KNOWN_SYSTEMS)).isEmpty();
    }

    /**
     * Systems named on THIS incident are the highest-value denylist entries — they're the exact
     * system-shaped phrases the ticket is about, so the likeliest false positives.
     */
    @Test
    void rejectsSystemNamesSuppliedAsIncidentContextEvenIfNotInTheStaticDenylist() {
        var text = "The Ledger Export job failed. Marcus Chen is looking at it.";
        assertThat(MentionedPeople.namesIn(text, Set.of("Ledger Export")))
                .containsExactly("Marcus Chen");
    }

    @Test
    void extractsEmailsAndHandlesUnambiguously() {
        assertThat(MentionedPeople.handlesIn("ping @priya.nair or priya@example.com"))
                .containsExactlyInAnyOrder("priya.nair", "priya@example.com");
        // An email must not also be harvested as a bare @handle.
        assertThat(MentionedPeople.handlesIn("mail tom@example.com")).containsExactly("tom@example.com");
    }

    @Test
    void incidentContactsCoverBothJournalAuthorsAndPeopleTheyName() {
        List<Contact> contacts = MentionedPeople.fromIncident(
                "INC0010005", "Orders failing at checkout", "Checkout broken",
                List.of("jane.customer: it worked yesterday"),
                List.of("m.chen: Escalated after speaking with Priya Nair in Payments."),
                KNOWN_SYSTEMS);

        assertThat(contacts).extracting(Contact::name)
                .contains("jane.customer", "m.chen", "Priya Nair");
        assertThat(contacts).allSatisfy(c -> assertThat(c.source()).isEqualTo("servicenow"));

        // Authoring a comment is a stronger signal of engagement than being mentioned in one.
        assertThat(contacts).extracting(Contact::reason)
                .contains("commented on this incident", "named in the incident description or comments");
    }

    @Test
    void pageBodyContactsComeFromRunbookProseNotJustPageMetadata() {
        List<Contact> contacts = MentionedPeople.fromPageBody(
                "Payment Reconciliation Runbook", "https://wiki/x",
                "PAYMENT_RECONCILE_MISMATCH means totals diverged. Escalation contact: Marcus Chen.",
                KNOWN_SYSTEMS);

        assertThat(contacts).extracting(Contact::name).containsExactly("Marcus Chen");
        assertThat(contacts).first().satisfies(c -> {
            assertThat(c.source()).isEqualTo("confluence");
            assertThat(c.reason()).contains("Payment Reconciliation Runbook");
        });
    }

    @Test
    void emptyAndNullInputAreSafe() {
        assertThat(MentionedPeople.namesIn(null, KNOWN_SYSTEMS)).isEmpty();
        assertThat(MentionedPeople.namesIn("   ", KNOWN_SYSTEMS)).isEmpty();
        assertThat(MentionedPeople.handlesIn(null)).isEmpty();
        assertThat(MentionedPeople.fromIncident("INC1", null, null, null, null, Set.of())).isEmpty();
        assertThat(MentionedPeople.fromPageBody("t", "u", null, Set.of())).isEmpty();
    }
}
