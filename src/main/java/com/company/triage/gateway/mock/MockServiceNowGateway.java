package com.company.triage.gateway.mock;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ground-truth demo dataset (J7) for the offline demo. Models one deliberately
 * vague incident — INC0012345 / order INC-ORD-4471 — whose real cause is the seeded
 * payment reconcile bug (discount applied after tax). Reuses the S3′ fixture universe
 * so the log↔code citation lands on payment_service.py:44.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.servicenow", havingValue = "mock", matchIfMissing = true)
public class MockServiceNowGateway implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(MockServiceNowGateway.class);
    private final List<String> postedNotes = new ArrayList<>();

    @Override
    public IncidentContext getIncident(String number) {
        return new IncidentContext(
                number,
                "Orders sometimes don't go through at checkout",
                "A few customers reported that when they try to submit an order it just "
                        + "fails with an error and the order is not placed. Happens intermittently. "
                        + "One example order id they gave was INC-ORD-4471.",
                "jane.customer",
                "Software",
                "Application error",
                OffsetDateTime.parse("2026-07-23T09:20:00+10:00"),
                "Production",
                "Service Desk",
                List.of("Caller: 'it worked yesterday, now some checkouts error out'"),
                List.of(),
                "Order Portal",
                List.of("Service Desk (initial)")
        );
    }

    @Override
    public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
        return List.of(
                new ResolvedIncident("INC0011902",
                        "Checkout 500 error — payment reconcile mismatch on discounted orders",
                        "Payments Platform Support", "Resolved - Code Fix",
                        "Discount was applied after tax in the gateway; reconcile check failed. "
                                + "Fixed order of operations in payment_service.", 0.91),
                new ResolvedIncident("INC0011455",
                        "Order submission fails for orders with a percentage discount",
                        "Payments Platform Support", "Resolved - Known Error",
                        "PAYMENT_RECONCILE_MISMATCH on discounted+taxed orders. Workaround then code fix.",
                        0.78)
        );
    }

    @Override
    public Optional<ServiceOwnership> findOwnership(String applicationName) {
        String a = applicationName == null ? "" : applicationName.toLowerCase();
        if (a.contains("payment") || a.contains("order")) {
            return Optional.of(new ServiceOwnership(
                    applicationName, "Payments Platform Support",
                    "Order & Payments", "cmdb_ci_service"));
        }
        return Optional.empty();
    }

    @Override
    public void addWorkNote(String number, String workNote) {
        if (postedNotes.contains(workNote)) {           // idempotency
            log.info("[mock ServiceNow] identical AI work note already present on {} — skipping", number);
            return;
        }
        postedNotes.add(workNote);
        log.info("[mock ServiceNow] advisory work note posted to {}:\n{}", number, workNote);
    }
}
