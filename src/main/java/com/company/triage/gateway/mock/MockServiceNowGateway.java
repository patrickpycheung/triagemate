package com.company.triage.gateway.mock;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.NewIncident;
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
 * vague incident — INC0010005 / order INC-ORD-4471 — whose real cause is the seeded
 * payment reconcile bug (discount applied after tax). Reuses the S3′ fixture universe
 * so the log↔code citation lands on payment_service.py:44.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.servicenow", havingValue = "mock", matchIfMissing = true)
public class MockServiceNowGateway implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(MockServiceNowGateway.class);
    private final List<String> postedNotes = new ArrayList<>();

    /** One-shot: lets the offline K1 poller see a single "new" incident. See below. */
    private final java.util.concurrent.atomic.AtomicBoolean newIncidentAvailable =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    /** The primary incident this ground-truth dataset models (J7) — the strong case. */
    static final String KNOWN_INCIDENT = "INC0010005";

    /**
     * J28/PGC-8 — the <b>abstention</b> incident: a real-looking ticket whose similar
     * incidents were closed without anyone recording what was wrong.
     *
     * <p>It exists because abstention is the <em>common</em> production path, not an edge
     * case: real {@code close_notes} are frequently "Issue resolved" or blank. Before this,
     * the mock modelled only the strong case, so the demo could only ever show a confident
     * cause — selling a version of the feature nobody would meet against real data. That is
     * the same mock-fidelity blind spot that hid FND-84/85/86/87.
     *
     * <p>It is also the better demo beat: <i>"and here's what it does when it doesn't know —
     * it says so, and tells you what it looked at."</i>
     */
    static final String ABSTENTION_INCIDENT = "INC0010009";

    @Override
    public IncidentContext getIncident(String number) {
        // FND-54: previously this echoed ANY number into the seeded context, so a typo on
        // stage returned HTTP 200 with a complete, confident diagnosis of the payment-reconcile
        // bug headed with an incident that does not exist — writeback logged, trace full, no
        // warning. That is strictly worse than the TypeError FND-48 replaced, and it is the
        // FND-8 failure class (asserting something untrue) in its purest form. It also made
        // FND-48's 404 path unreachable in the demo config, since only the REAL gateway threw.
        // The dataset models exactly one incident (J7); say so rather than fabricate.
        String n = number == null ? "" : number.trim();
        if (ABSTENTION_INCIDENT.equalsIgnoreCase(n)) {
            return abstentionIncident(number);
        }
        if (!KNOWN_INCIDENT.equalsIgnoreCase(n)) {
            throw new com.company.triage.gateway.IncidentNotFoundException(number);
        }
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
                // FND-64: journal entries carry their author (as RealServiceNowGateway now
                // renders them, "sys_created_by: text"), and the work note names a person —
                // so the J9 name extraction has real ServiceNow signal to find, matching what
                // a real ticket looks like.
                List.of("jane.customer: it worked yesterday, now some checkouts error out"),
                List.of("m.chen: Escalated after speaking with Priya Nair in Payments — "
                        + "she owns the reconcile path."),
                "Order Portal",
                List.of("Service Desk (initial)")
        );
    }

    /**
     * Simulates <b>exactly one newly-arrived incident</b>, then nothing.
     *
     * <p>The fixture incident's {@code openedAt} is a fixed date in the past, so comparing
     * it against the poller's "started just now" cursor would return empty forever and the
     * K1 poller could never be exercised offline. Instead the first call reports
     * {@code INC0010005} as new and every later call reports nothing — which is precisely
     * the behaviour that matters to verify: the poller triages a new incident <b>once</b>
     * and then goes quiet, even though the run posts work notes (FND-1).
     */
    @Override
    public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
        if (limit <= 0 || !newIncidentAvailable.compareAndSet(true, false)) {
            return List.of();
        }
        log.info("mock: reporting INC0010005 as newly created (one-shot, offline poller demo)");
        // createdAt just after the cursor: what a genuinely-new incident looks like.
        return List.of(new NewIncident("INC0010005", since.plusSeconds(1)));
    }

    /**
     * J28/PGC-8 — the abstention fixture. A perfectly ordinary ticket; the difference is
     * entirely in what its precedents recorded (see {@link #findSimilarIncidents}).
     */
    private static IncidentContext abstentionIncident(String number) {
        return new IncidentContext(
                number,
                "Reports timing out for some users in the morning",
                "Two users said the daily reconciliation report spins and then errors. "
                        + "Both were on the VPN. Not reproducible from the office network.",
                "d.okafor",
                "Software",
                "Performance",
                OffsetDateTime.parse("2026-08-04T08:15:00+10:00"),
                "Production",
                "Service Desk",
                List.of("d.okafor: happens most mornings, fine by lunchtime"),
                List.of(),
                "Reporting Service",
                List.of("Service Desk (initial)")
        );
    }

    @Override
    public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
        // J28/PGC-8: this incident's precedents were closed WITHOUT resolution notes — the
        // ordinary state of a real queue. The ranker still finds them (they match on symptom
        // and CI), so this is not "no similar incidents": it is "similar incidents exist and
        // none of them recorded what was wrong", which is exactly the case where the honest
        // answer is "not established" and the denominator is the useful part.
        if (ABSTENTION_INCIDENT.equalsIgnoreCase(incident.number() == null ? "" : incident.number().trim())) {
            return List.of(
                    new ResolvedIncident("INC0009918",
                            "Reporting slow for VPN users",
                            "Reporting Platform", "Closed - No fault found", "", 0.74),
                    new ResolvedIncident("INC0009655",
                            "Daily report timed out",
                            "Reporting Platform", "Closed - Resolved by caller", null, 0.61)
            );
        }
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
