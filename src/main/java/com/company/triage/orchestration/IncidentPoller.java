package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.NewIncident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * K1 — the outbound trigger. ServiceNow (cloud) cannot reach a corp-network laptop, so
 * instead of waiting to be pushed to, the app <b>polls</b> ServiceNow over the same
 * outbound HTTPS channel it already uses to read and comment on tickets. No tunnel, no
 * MID Server, no inbound firewall hole. Decided in DDS {@code servicenow-local-trigger}
 * (which supersedes {@code servicenow-auto-trigger}'s "defer it, use the manual trigger").
 *
 * <p><b>Off by default.</b> {@code triage.trigger.poll.enabled=false} — the demo drives the
 * manual {@code POST /api/diagnose/{number}} endpoint (K3), and a poller that woke up
 * mid-presentation and started diagnosing unrelated tickets would be a stage hazard.
 *
 * <h2>Never diagnose the same incident twice (FND-1)</h2>
 * <ol>
 *   <li><b>Query by creation, not update</b>: {@code sys_created_on > cursor}. Every run
 *       posts two work notes (J5), each bumping {@code sys_updated_on}; an updated-since
 *       query would therefore re-select every ticket this app touches and re-run the whole
 *       diagnosis forever. Creation time is immutable. This is the structural fix.</li>
 *   <li><b>In-flight set</b>: a number is claimed before work starts and only released
 *       after it finishes, so a concurrent or re-entrant tick cannot pick it up mid-run.</li>
 *   <li><b>Completed set</b>: bounded FIFO of numbers already done in this process, so even
 *       a cursor that fails to advance cannot cause a second diagnosis.</li>
 * </ol>
 * J5's "skip if an identical AI note already exists" is a further layer, but it only dedupes
 * the <i>comment</i> — by then the expensive, ToS-sensitive model run has already happened.
 *
 * <h2>Never skip an incident</h2>
 * Two rules, both necessary:
 * <ul>
 *   <li>The cursor advances to a <b>handled incident's {@code createdAt}</b>, never to
 *       "now". Advancing to now silently drops anything created while the batch was being
 *       processed — a diagnosis takes seconds and a live agent run can take much longer, so
 *       that window is real.</li>
 *   <li>It advances only across an <b>unbroken run of handled incidents</b>, oldest first.
 *       A plain "newest handled" mark is not enough: if an early incident fails but a later
 *       one succeeds, the cursor would move past the failure and it would never be retried.
 *       So the first unhandled incident freezes the cursor; later successes in the same
 *       batch get re-queried next tick and short-circuited by the completed-set. That costs
 *       one cheap query and cannot lose an incident.</li>
 * </ul>
 * If a batch handles nothing the cursor does not move at all, and the next tick retries the
 * same window.
 *
 * <h2>Overlapping runs</h2>
 * {@code fixedDelay} measures from the previous run's <i>completion</i>, and Spring's default
 * scheduler is single-threaded, so ticks cannot overlap and the interval does <b>not</b> need
 * to exceed processing time. That is an easy invariant to break, though (switching to
 * {@code fixedRate}, or configuring a scheduler pool), so {@link #running} enforces it
 * explicitly rather than relying on it.
 *
 * <p><b>Known limitation, deliberate</b>: cursor and completed-set are in-process only. A
 * restart re-seeds the cursor to "now", so incidents created while the app was down are
 * skipped rather than re-triaged. Skipping is the safe direction; durable state is the
 * documented next step (see CDS {@code J10-incident-poller}).
 */
@Component
@ConditionalOnProperty(name = "triage.trigger.poll.enabled", havingValue = "true")
public class IncidentPoller {

    private static final Logger log = LoggerFactory.getLogger(IncidentPoller.class);

    private final ServiceNowGateway serviceNow;
    private final DiagnosisOrchestrator orchestrator;
    private final int batchLimit;
    private final int completedCap;

    /** High-water mark: only incidents created strictly after this are considered. */
    private volatile OffsetDateTime cursor;

    /** Claimed for the duration of a diagnosis — layer 2. */
    private final Set<String> inFlight = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Finished in this process — layer 3, bounded FIFO. */
    private final Set<String> completed = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Guards against overlapping ticks — see class javadoc. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IncidentPoller(ServiceNowGateway serviceNow,
                          DiagnosisOrchestrator orchestrator,
                          @Value("${triage.trigger.poll.batch-limit:10}") int batchLimit,
                          @Value("${triage.trigger.poll.completed-cap:500}") int completedCap,
                          @Value("${triage.engine:deterministic}") String configuredEngine,
                          @Value("${triage.trigger.poll.unattended-llm-ack:false}") boolean unattendedLlmAck) {
        this.serviceNow = serviceNow;
        this.orchestrator = orchestrator;
        this.batchLimit = batchLimit;
        this.completedCap = completedCap;
        this.cursor = OffsetDateTime.now();
        log.info("K1 poller enabled — polling incidents created after {} (batch limit {})",
                cursor, batchLimit);
        // FND-45: this bean existing at all means poll.enabled=true (its @ConditionalOnProperty
        // gate). Combined with triage.engine=adk, that is exactly the unattended, programmatic
        // LLM use the C6 ToS ruling gates — previously documented in J10's prose but not
        // enforced anywhere. WARN, don't refuse (matches FND-49's precedent: a hackathon build
        // shouldn't fail to boot over this) — but an explicit ack property means someone had to
        // actually set it, turning a silent gap into a decision on record.
        if ("adk".equalsIgnoreCase(configuredEngine) && !unattendedLlmAck) {
            log.warn("K1 poller is running WITH triage.engine=adk — this is unattended, "
                    + "programmatic LLM use, which the C6 ToS ruling gates (see "
                    + "docs/discovery/copilot-cli-runtime). Set "
                    + "triage.trigger.poll.unattended-llm-ack=true once that's cleared to "
                    + "silence this warning.");
        }
    }

    @Scheduled(fixedDelayString = "${triage.trigger.poll.interval-ms:30000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("poll: previous run still in progress — skipping this tick "
                    + "(processing is slower than the poll interval)");
            return;
        }
        try {
            pollOnce();
        } finally {
            running.set(false);
        }
    }

    private void pollOnce() {
        List<NewIncident> found;
        try {
            found = serviceNow.findIncidentsCreatedSince(cursor, batchLimit);
        } catch (Exception e) {
            // A transient ServiceNow/network failure must not kill the scheduler thread.
            // The cursor is untouched, so the next tick retries the same window.
            log.warn("poll failed ({}: {}) — will retry next tick",
                    e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (found.isEmpty()) return;

        // FND-41: sort defensively rather than trust the gateway's ordering. The cursor-
        // advance logic below assumes oldest-first (RealServiceNowGateway does query
        // ORDERBYsys_created_on, but nothing here re-checked that) — an out-of-order
        // batch would silently corrupt the "unbroken handled prefix" invariant this
        // class exists to guarantee.
        found = found.stream().sorted(java.util.Comparator.comparing(NewIncident::createdAt)).toList();

        log.info("poll: {} new incident(s)", found.size());

        // The cursor may only advance across an unbroken run of handled incidents, oldest
        // first. A plain "newest handled" high-water mark is WRONG: if an early incident
        // fails but a later one succeeds, the cursor would move past the failure and it
        // would never be retried. So the moment anything is left unhandled, the cursor
        // stops moving — later successes in the same batch are simply re-queried next tick
        // and short-circuited by the completed-set, which costs one cheap query and cannot
        // lose an incident.
        OffsetDateTime advanceTo = null;
        boolean unhandledSeen = false;

        for (NewIncident incident : found) {
            // FND-50 (completed 2026-07-31, third pass): normalize here too. FND-37 first
            // put this in DiagnosisController; FND-50 moved it into DiagnosisOrchestrator.run()
            // so K1 got it — but FND-1's three dedupe layers below still keyed on the RAW
            // gateway string, so `inc0000001` and `INC0000001` counted as different incidents
            // for the in-flight/completed sets even though run() coalesced them as one.
            // Normalize once, here, before any of the three layers see it.
            String number = incident.number().trim().toUpperCase();
            boolean handled = false;

            if (completed.contains(number)) {
                log.debug("poll: {} already diagnosed in this process — skipping", number);
                handled = true;                     // done previously; safe to advance over
            } else if (!inFlight.add(number)) {
                // Claimed by another run — not ours to advance past.
                log.debug("poll: {} already in flight — skipping", number);
            } else {
                try {
                    DiagnosisResult result = orchestrator.run(number);
                    if (result.degraded()) {
                        // Nobody is watching a UI on an unattended run, so this log is the
                        // only place the degradation surfaces (FND-8).
                        log.warn("poll: {} diagnosed by the DEGRADED (deterministic) path — the live agent failed",
                                number);
                    } else {
                        log.info("poll: {} diagnosed via {}", number, result.engine());
                    }
                    markCompleted(number);
                    handled = true;
                } catch (Exception e) {
                    // One bad incident must neither stop the batch nor be lost: it stays out
                    // of the completed-set and blocks the cursor, so a later tick retries it.
                    log.error("poll: {} failed to diagnose ({}: {}) — will retry on a later tick",
                            number, e.getClass().getSimpleName(), e.getMessage());
                } finally {
                    inFlight.remove(number);
                }
            }

            if (handled && !unhandledSeen) {
                advanceTo = incident.createdAt();   // extend the contiguous handled prefix
            } else if (!handled) {
                unhandledSeen = true;              // freeze the cursor here
            }
        }

        if (advanceTo != null && advanceTo.isAfter(cursor)) {
            cursor = advanceTo;
            log.debug("poll: cursor advanced to {}", cursor);
        }
    }

    private void markCompleted(String number) {
        synchronized (completed) {
            completed.add(number);
            if (completed.size() > completedCap) {
                var it = completed.iterator();   // LinkedHashSet → insertion order → FIFO
                while (completed.size() > completedCap && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    // --- visible for tests -------------------------------------------------------
    OffsetDateTime cursor() { return cursor; }
    boolean isInFlight(String number) { return inFlight.contains(number); }
    boolean isCompleted(String number) { return completed.contains(number); }
}
