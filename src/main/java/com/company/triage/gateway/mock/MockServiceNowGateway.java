package com.company.triage.gateway.mock;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.fixture.FixtureKeys;
import com.company.triage.gateway.fixture.FixtureSession;
import com.company.triage.gateway.fixture.FixtureStore;
import com.fasterxml.jackson.core.type.TypeReference;
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
 * Offline ServiceNow. Serves RECORDED responses first (see {@link FixtureStore}), and
 * falls back to the hand-written demo dataset (J7) for the two incidents that predate
 * recording.
 *
 * <p>Recorded fixtures are the preferred path and the reason this class changed: the
 * hand-written dataset — INC0010005 / order INC-ORD-4471, the seeded payment-reconcile
 * bug — is a coherent story about a system that does not exist. It proves the ENGINE
 * works and proves nothing about the estate, so a mocked run's plausibility was not
 * evidence. A fixture is what the live instance actually returned, so replaying it
 * exercises the same parsing, the same empty fields and the same surprises as a real run.
 *
 * <p>The legacy dataset is kept rather than deleted: INC0010005 is what the e2e suite
 * drives and what the offline stage walkthrough narrates, and INC0010009 is the J28/PGC-8
 * abstention beat. Both must keep working with no network and no fixtures on disk.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.servicenow", havingValue = "mock", matchIfMissing = true)
public class MockServiceNowGateway implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(MockServiceNowGateway.class);
    private final List<String> postedNotes = new ArrayList<>();

    private static final String G = "servicenow";
    private final FixtureStore fixtures;
    private final FixtureSession session;
    /** Simulated network delay — see {@link MockLatency}. */
    private final MockLatency latency;

    /** Recorded incident replayed under an unrecorded number; blank restores FND-54. */
    @org.springframework.beans.factory.annotation.Value("${triage.connectors.mock-stand-in:}")
    private String standInIncident;

    /** No fixtures — the legacy J7 dataset only. See {@link FixtureStore#none()}. */
    public MockServiceNowGateway() {
        this(FixtureStore.none(), new FixtureSession(), MockLatency.none());
    }

    /**
     * @Autowired is LOAD-BEARING, not decoration. With two constructors and no
     * annotation Spring picks the NO-ARG one, which wires FixtureStore.none() — every
     * fixture lookup then misses and a recorded incident comes back "incident not
     * found". Silent, and invisible to unit tests, which construct explicitly.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MockServiceNowGateway(FixtureStore fixtures, FixtureSession session, MockLatency latency) {
        this.fixtures = fixtures;
        this.session = session;
        this.latency = latency;
    }

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
        latency.pause();   // stand in for the network the real connector crosses
        // FND-54: previously this echoed ANY number into the seeded context, so a typo on
        // stage returned HTTP 200 with a complete, confident diagnosis of the payment-reconcile
        // bug headed with an incident that does not exist — writeback logged, trace full, no
        // warning. That is strictly worse than the TypeError FND-48 replaced, and it is the
        // FND-8 failure class (asserting something untrue) in its purest form. It also made
        // FND-48's 404 path unreachable in the demo config, since only the REAL gateway threw.
        // The dataset models exactly one incident (J7); say so rather than fabricate.
        String n = number == null ? "" : number.trim();
        session.set(n);
        // Recorded fixture wins when one exists for this exact incident. Checked BEFORE the
        // known-incident guard below, because a recorded incident IS a known incident — the
        // guard's job is to reject numbers we have no data for, and a fixture is data.
        var recorded = fixtures.<IncidentContext>find(
                n, G, "getIncident", FixtureKeys.of(n), new TypeReference<IncidentContext>() {});
        if (recorded.isPresent()) {
            log.info("mock: serving RECORDED ServiceNow incident {}", n);
            return recorded.get();
        }
        if (ABSTENTION_INCIDENT.equalsIgnoreCase(n)) {
            return abstentionIncident(number);
        }
        if (!KNOWN_INCIDENT.equalsIgnoreCase(n)) {
            IncidentContext standIn = standInFor(n);
            if (standIn == null) {
                throw new com.company.triage.gateway.IncidentNotFoundException(number);
            }
            return standIn;
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
     * Offline demo convenience: let an UNRECORDED number be diagnosed anyway, by replaying a
     * recorded incident's evidence under the number that was typed.
     *
     * <p>This deliberately relaxes FND-54, which made the mock reject unknown numbers. That
     * guard was right for its context — a typo on stage used to return a confident diagnosis
     * of a bug, headed with an incident that does not exist, and asserting something untrue is
     * the worst failure this app has. The relaxation is bounded so that failure cannot return:
     *
     * <ul>
     *   <li>it applies to the MOCK gateway only — {@code RealServiceNowGateway} still 404s, so
     *       nothing invented can ever reach a real ticket or a real writeback;</li>
     *   <li>the substitution is ANNOUNCED, at WARN, naming both numbers. The demo is honest
     *       about being a demo; what FND-54 actually forbids is doing this silently;</li>
     *   <li>it is switchable — {@code triage.connectors.mock-stand-in} to a different incident,
     *       or blank to restore the strict FND-54 behaviour exactly.</li>
     * </ul>
     *
     * <p>The session is re-latched to the STAND-IN's number, not the typed one, so every other
     * connector replays the same bundle. Without that, Confluence/Sumo/GitLab would look up the
     * typed number, find no fixture, and either fall back to the unrelated J7 payment story or
     * report themselves unavailable — a diagnosis assembled from two different incidents.
     *
     * @return the stand-in context carrying the requested number, or null when no stand-in is
     *         configured or recorded (caller then throws, preserving FND-54)
     */
    private IncidentContext standInFor(String requestedNumber) {
        if (standInIncident == null || standInIncident.isBlank()) return null;
        var base = fixtures.<IncidentContext>find(standInIncident, G, "getIncident",
                FixtureKeys.of(standInIncident), new TypeReference<IncidentContext>() {});
        if (base.isEmpty()) return null;

        log.warn("mock: {} is not recorded — replaying {}'s evidence under that number "
                        + "(offline demo; triage.connectors.mock-stand-in=<blank> to disable)",
                requestedNumber, standInIncident);
        session.set(standInIncident);
        IncidentContext b = base.get();
        return new IncidentContext(requestedNumber, b.shortDescription(), b.description(),
                b.caller(), b.category(), b.subcategory(), b.openedAt(), b.environment(),
                b.currentAssignment(), b.comments(), b.workNotes(), b.configurationItem(),
                b.reassignmentHistory());
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
        latency.pause();   // stand in for the network the real connector crosses
        // The SESSION, not incident.number(): under a stand-in the context deliberately
        // carries the number the operator typed, while the recordings are filed under the
        // stand-in's. Keying off the context here would miss every fixture and silently fall
        // through to the payment-reconcile precedents below.
        String number = session.current();
        if (fixtures.hasIncident(number)) {
            // A recorded incident never falls through to the payment-reconcile precedents
            // below — see FixtureStore#hasIncident. No recorded precedents means the live
            // instance found none, which is itself the answer (J28/PGC-8 abstention).
            return fixtures.<List<ResolvedIncident>>find(number, G, "findSimilarIncidents",
                    FixtureKeys.of(number), new TypeReference<List<ResolvedIncident>>() {})
                    .orElseGet(List::of);
        }
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
        latency.pause();   // stand in for the network the real connector crosses
        // Recorded as the unwrapped value, so an absent CMDB entry replays as a real absence
        // (null → Optional.empty) rather than as "no fixture, fall back to the demo answer".
        if (fixtures.hasIncident(session.current())) {
            return fixtures.<ServiceOwnership>find(session.current(), G, "findOwnership",
                    FixtureKeys.of(applicationName), new TypeReference<ServiceOwnership>() {});
        }
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
