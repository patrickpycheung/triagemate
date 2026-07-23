package com.company.triage.gateway.mock;

import com.company.triage.gateway.SumoGateway;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mock Sumo (J7 dataset) — mirrors docs/.../verification-s3/sumo-fixture.json, the
 * pre-scoped failure window for INC-ORD-4471. Returns the seeded log lines the agent
 * must correlate back to source (RC3). Respects the request's maxResults cap.
 */
@Component
@Profile("mock")
public class MockSumoGateway implements SumoGateway {

    private static final List<LogEvidence> WINDOW = List.of(
            new LogEvidence("2026-07-23T09:14:21Z", "INFO", "payment_service",
                    "charge start order=INC-ORD-4471 subtotal=10.00 tax_rate=0.25 discount_pct=0.10"),
            new LogEvidence("2026-07-23T09:14:22Z", "ERROR", "payment_service",
                    "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471 expected=11.50 charged=11.25"),
            new LogEvidence("2026-07-23T09:14:22Z", "ERROR", "order_api",
                    "unhandled ValueError: reconcile mismatch order=INC-ORD-4471 trace=payment_service.charge"),
            new LogEvidence("2026-07-23T09:14:23Z", "WARN", "order_api",
                    "returning 500 to client req=/v1/orders/INC-ORD-4471/charge"));

    @Override
    public List<LogEvidence> search(LogSearchRequest request) {
        String term = request.query() == null ? "" : request.query().toLowerCase();
        return WINDOW.stream()
                .filter(e -> term.isBlank()
                        || e.message().toLowerCase().contains(term)
                        || term.contains("reconcile") || term.contains("4471") || term.contains("error"))
                .limit(Math.max(1, request.maxResults()))
                .collect(Collectors.toList());
    }
}
