package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J13/ECI-5 — a typed field is populated only from a signal that determined THAT field.
 *
 * <p>The engine used to write the same string into {@code correlationId} and {@code orderId}
 * whatever it was, with {@code errorCode} hardcoded {@code null}. So a UUID — a correlation id
 * by construction — was also reported as an order id, and the report asserted a business
 * reference it had never seen.
 *
 * <p>{@link Identifiers}' own javadoc says any field may be null, so {@code null} is the
 * honest value for something the ticket did not determine. A wrong TYPE is not.
 */
class TypedIdentifiersTest {

    /** Serves one incident whose description carries whatever identifier the test wants. */
    static class TicketSaying implements ServiceNowGateway {
        private final MockServiceNowGateway delegate = new MockServiceNowGateway();
        private final String text;
        TicketSaying(String text) { this.text = text; }

        public IncidentContext getIncident(String n) {
            IncidentContext base = delegate.getIncident("INC0010005");
            return new IncidentContext(n, "Export failing", text, base.caller(),
                    base.category(), base.subcategory(), base.openedAt(), base.environment(),
                    base.currentAssignment(), List.of(), List.of(), base.configurationItem(),
                    List.of());
        }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) {
            return delegate.findSimilarIncidents(c);
        }
        public Optional<ServiceOwnership> findOwnership(String a) { return delegate.findOwnership(a); }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) { return List.of(); }
        public void addWorkNote(String n, String note) {}
    }

    private DiagnosisReport diagnose(String ticketText) {
        return new DeterministicDiagnosisEngine(new TicketSaying(ticketText),
                new MockConfluenceGateway(), new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic()).diagnose("INC0010005").report();
    }

    @Test
    void aDashedBusinessReferenceIsAnOrderIdAndNotAlsoACorrelationId() {
        Identifiers ids = diagnose("The failure mentions BATCH-778812 in the export log.").identifiers();

        assertThat(ids.orderId()).isEqualTo("BATCH-778812");
        assertThat(ids.correlationId())
                .as("nothing here determined a correlation id")
                .isNull();
    }

    @Test
    void aUuidIsACorrelationIdAndNotAlsoAnOrderId() {
        Identifiers ids = diagnose("trace 3f2504e0-4f89-11d3-9a0c-0305e82c3301 failed").identifiers();

        assertThat(ids.correlationId()).isEqualTo("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
        assertThat(ids.orderId())
                .as("a UUID is not a business reference — reporting it as one asserts a fact "
                        + "the ticket never carried")
                .isNull();
    }

    @Test
    void aTicketWithNoIdentifierAtAllReportsNeither() {
        Identifiers ids = diagnose("It just does not work, no reference given.").identifiers();

        assertThat(ids.orderId()).isNull();
        assertThat(ids.correlationId()).isNull();
    }
}
