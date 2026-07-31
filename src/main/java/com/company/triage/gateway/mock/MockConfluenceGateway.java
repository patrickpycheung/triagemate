package com.company.triage.gateway.mock;

import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.model.Contact;
import com.company.triage.model.KnowledgeDoc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Mock Confluence (J7 dataset): one runbook that documents the known error (J6). */
@Component
@ConditionalOnProperty(name = "triage.connectors.confluence", havingValue = "mock", matchIfMissing = true)
public class MockConfluenceGateway implements ConfluenceGateway {

    @Override
    public List<KnowledgeDoc> search(String query) {
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
