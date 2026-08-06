package com.company.triage.gateway.mock;

import com.company.triage.gateway.SumoGateway;
import com.company.triage.gateway.fixture.FixtureKeys;
import com.company.triage.gateway.fixture.FixtureSession;
import com.company.triage.gateway.fixture.FixtureStore;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Offline Sumo. Replays RECORDED log lines for a recorded incident; otherwise serves the
 * legacy J7 window (docs/.../verification-s3/sumo-fixture.json — the pre-scoped failure
 * window for INC-ORD-4471, the seeded log↔code correlation of RC3). Respects the
 * request's maxResults cap on both paths.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.sumo", havingValue = "mock", matchIfMissing = true)
public class MockSumoGateway implements SumoGateway {

    private static final String G = "sumo";
    private final FixtureStore fixtures;
    private final FixtureSession session;
    /** Simulated network delay — see {@link MockLatency}. */
    private final MockLatency latency;

    /** No fixtures — the legacy J7 dataset only. See {@link FixtureStore#none()}. */
    public MockSumoGateway() {
        this(FixtureStore.none(), new FixtureSession(), MockLatency.none());
    }

    /**
     * @Autowired is LOAD-BEARING, not decoration. With two constructors and no
     * annotation Spring picks the NO-ARG one, which wires FixtureStore.none() — every
     * fixture lookup then misses and a recorded incident comes back "incident not
     * found". Silent, and invisible to unit tests, which construct explicitly.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MockSumoGateway(FixtureStore fixtures, FixtureSession session, MockLatency latency) {
        this.fixtures = fixtures;
        this.session = session;
        this.latency = latency;
    }

    private static final List<LogEvidence> WINDOW = List.of(
            new LogEvidence("2026-07-23T09:14:21Z", "INFO", "payment_service",
                    "charge start order=INC-ORD-4471 subtotal=10.00 tax_rate=0.25 discount_pct=0.10"),
            new LogEvidence("2026-07-23T09:14:22Z", "ERROR", "payment_service",
                    "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471 expected=11.50 charged=11.25"),
            new LogEvidence("2026-07-23T09:14:22Z", "ERROR", "order_api",
                    "unhandled ValueError: reconcile mismatch order=INC-ORD-4471 trace=payment_service.charge"),
            new LogEvidence("2026-07-23T09:14:23Z", "WARN", "order_api",
                    "returning 500 to client req=/v1/orders/INC-ORD-4471/charge"));

    /**
     * The seeded window belongs to ONE application, and this mock must say so.
     *
     * <p>It used to discriminate on the query TERM alone and ignore {@code sourceCategory}
     * entirely — which was survivable only while the engine derived a per-incident search
     * term. Once the term became the constant {@code ERROR} (operator instruction,
     * 2026-08-05), a term-only filter returned this payment window for <b>every</b> incident,
     * and a Ledger Export ticket came back recommending {@code payment_service.py:44}. That
     * is FND-63's failure class exactly: narrating something that did not happen.
     *
     * <p>Against real Sumo the contamination is impossible — {@code _sourceCategory} is
     * composed per application, so a ledger incident searches the ledger scope and sees ledger
     * lines. The bug was the mock being LESS discriminating than the system it stands in for,
     * the same mock-fidelity gap that hid FND-47 and FND-61. So scope is now the primary
     * filter here too, and the term filters within it — which is what the real query does.
     */
    @Override
    public List<LogEvidence> search(LogSearchRequest request) {
        latency.pause();   // stand in for the network the real connector crosses
        // Recorded lines replay against the SCOPE + TERM only — the window moves every run
        // (the engine searches the last 24h), so it is excluded from the key by design; see
        // FixtureKeys. The cap is still applied below, because it is the caller's bound.
        fixtures.requireCapturedOrUnavailable(session.current(), G, "Sumo Logic");
        if (fixtures.hasIncident(session.current())) {
            List<LogEvidence> recorded = fixtures.<List<LogEvidence>>find(session.current(), G,
                    "search", FixtureKeys.forSumo(request), new TypeReference<List<LogEvidence>>() {})
                    .orElseGet(List::of);
            return recorded.stream().limit(Math.max(1, request.maxResults())).toList();
        }
        String scope = request.sourceCategory() == null ? "" : request.sourceCategory().toLowerCase();
        // The seeded window is the payment/order estate. A scope naming any other application
        // legitimately has no lines here.
        boolean scopeMatchesSeededEstate = scope.isBlank()
                || scope.contains("payment") || scope.contains("order");
        if (!scopeMatchesSeededEstate) {
            return List.of();
        }
        String term = request.query() == null ? "" : request.query().toLowerCase();
        return WINDOW.stream()
                .filter(e -> term.isBlank()
                        || e.message().toLowerCase().contains(term)
                        || e.level().equalsIgnoreCase(term)          // the ERROR severity filter
                        || term.contains("reconcile") || term.contains("4471") || term.contains("error"))
                .limit(Math.max(1, request.maxResults()))
                .collect(Collectors.toList());
    }
}
