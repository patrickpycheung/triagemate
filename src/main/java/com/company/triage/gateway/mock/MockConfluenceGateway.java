package com.company.triage.gateway.mock;

import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.fixture.FixtureKeys;
import com.company.triage.gateway.fixture.FixtureSession;
import com.company.triage.gateway.fixture.FixtureStore;
import com.company.triage.model.Contact;
import com.company.triage.model.KnowledgeDoc;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Offline Confluence. Replays recorded search results when a fixture bundle exists for the
 * incident under diagnosis; otherwise serves the legacy J7 runbook.
 *
 * <p>Once a bundle exists it is AUTHORITATIVE, including when it recorded zero pages. The
 * fallback is deliberately not reached in that case: a real search that found nothing is
 * evidence, and quietly substituting the payment runbook for it would manufacture a
 * citation the estate never returned.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.confluence", havingValue = "mock", matchIfMissing = true)
public class MockConfluenceGateway implements ConfluenceGateway {

    private static final String G = "confluence";
    private final FixtureStore fixtures;
    private final FixtureSession session;

    /** No fixtures — the legacy J7 dataset only. See {@link FixtureStore#none()}. */
    public MockConfluenceGateway() {
        this(FixtureStore.none(), new FixtureSession());
    }

    /**
     * @Autowired is LOAD-BEARING, not decoration. With two constructors and no
     * annotation Spring picks the NO-ARG one, which wires FixtureStore.none() — every
     * fixture lookup then misses and a recorded incident comes back "incident not
     * found". Silent, and invisible to unit tests, which construct explicitly.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MockConfluenceGateway(FixtureStore fixtures, FixtureSession session) {
        this.fixtures = fixtures;
        this.session = session;
    }

    @Override
    public List<KnowledgeDoc> search(String query) {
        fixtures.requireCapturedOrUnavailable(session.current(), G, "Confluence");
        if (fixtures.hasIncident(session.current())) {
            return fixtures.<List<KnowledgeDoc>>find(session.current(), G, "search",
                    FixtureKeys.of(query), new TypeReference<List<KnowledgeDoc>>() {})
                    .orElseGet(List::of);
        }
        String q = query == null ? "" : query.toLowerCase();
        if (q.contains("reconcile") || q.contains("payment") || q.contains("order")
                || q.contains("discount") || q.contains("checkout") || q.contains("500")) {
            return List.of(new KnowledgeDoc(
                    "KB001234",
                    "Order Payment Reconciliation — Known Errors & Runbook",
                    "https://confluence.example.com/display/PAY/Order+Payment+Reconciliation",
                    "PAYMENT_RECONCILE_MISMATCH means the expected total and the charged "
                            + "amount diverged. Common cause: a percentage discount applied AFTER "
                            + "tax in the gateway while the expected total discounts BEFORE tax. "
                            + "Owned by Payments Platform Support. See payment_service reconcile(). "
                            // FND-64: runbooks routinely name an escalation contact in prose;
                            // the page's author/editor metadata never captures it.
                            + "Escalation contact: Marcus Chen."));
        }
        return List.of();
    }

    /** Who authored / last edited the cited runbook (J9). */
    @Override
    public List<Contact> contributors(KnowledgeDoc doc) {
        fixtures.requireCapturedOrUnavailable(session.current(), G, "Confluence");
        if (fixtures.hasIncident(session.current())) {
            return fixtures.<List<Contact>>find(session.current(), G, "contributors",
                    FixtureKeys.of(doc == null ? "" : doc.id()), new TypeReference<List<Contact>>() {})
                    .orElseGet(List::of);
        }
        if (doc == null || !"KB001234".equals(doc.id())) {
            return List.of();
        }
        return List.of(
                new Contact("Priya Nair", "priya.nair@example.com", "confluence",
                        "last edited the payment reconciliation runbook", doc.url(),
                        "last edited 2026-07-20"),
                new Contact("Tom Alvarez", "tom.alvarez@example.com", "confluence",
                        "original author of the reconciliation runbook", doc.url(),
                        "created 2025-11-03"));
    }
}
