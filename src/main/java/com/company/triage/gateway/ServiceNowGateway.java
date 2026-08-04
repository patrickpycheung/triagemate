package com.company.triage.gateway;

import com.company.triage.model.IncidentContext;
import com.company.triage.model.NewIncident;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;

import java.util.List;
import java.util.Optional;

/**
 * ServiceNow — the primary evidence source (J5), not just the trigger. Reads the
 * incident, similar resolved incidents (strongest routing signal) and CMDB
 * ownership; the single write is an advisory work note.
 *
 * <p>Real impl: ServiceNow REST Table API (incident, cmdb_ci), least-privilege
 * read role + a guarded write. Mock impl serves the ground-truth demo dataset.
 */
public interface ServiceNowGateway {

    IncidentContext getIncident(String number);

    /**
     * Incident numbers <b>created</b> strictly after {@code since}, oldest first, capped
     * at {@code limit}. Backs the K1 outbound poller (the trigger: ServiceNow cannot reach
     * a corp-network laptop, so the app polls out over the same HTTPS channel it already
     * uses).
     *
     * <p><b>Created, not updated — this is the FND-1 fix, not an arbitrary choice.</b>
     * Polling on {@code sys_updated_on} is self-triggering: the app posts two work notes
     * per run (J5), each of which bumps {@code sys_updated_on} past the cursor, so the next
     * poll re-selects the incident it just commented on and re-runs the whole diagnosis —
     * burning LLM calls in a loop. {@code sys_created_on} is immutable, so our own writes
     * can never re-select a ticket. This is also what the original {@code C-T3: insert-only}
     * trigger constraint always intended.
     *
     * <p>Trade-off accepted: an incident that becomes eligible <i>later</i> (re-categorised
     * into scope, say) is not picked up. Correct for "triage newly-created incidents"; if
     * eligibility-on-update is ever needed it must come with an explicit
     * "don't re-select what we wrote" guard, not a switch to {@code sys_updated_on}.
     *
     * <p>Returns each incident's {@code sys_created_on} alongside its number, because the
     * poller advances its cursor to the newest timestamp it actually handled. Advancing to
     * "now" instead would silently skip anything created while the batch was still being
     * processed.
     */
    List<NewIncident> findIncidentsCreatedSince(java.time.OffsetDateTime since, int limit);

    List<ResolvedIncident> findSimilarIncidents(IncidentContext incident);

    Optional<ServiceOwnership> findOwnership(String applicationName);

    /**
     * Posts an advisory, clearly-labelled work note. Never touches assignment,
     * state or priority. Implementations must be idempotent (skip if an identical
     * AI note already exists).
     */
    void addWorkNote(String number, String workNote);
}
