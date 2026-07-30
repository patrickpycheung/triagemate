package com.company.triage.gateway;

import com.company.triage.model.IncidentContext;
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

    List<ResolvedIncident> findSimilarIncidents(IncidentContext incident);

    Optional<ServiceOwnership> findOwnership(String applicationName);

    /**
     * Posts an advisory, clearly-labelled work note. Never touches assignment,
     * state or priority. Implementations must be idempotent (skip if an identical
     * AI note already exists).
     */
    void addWorkNote(String number, String workNote);
}
