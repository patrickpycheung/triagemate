package com.company.triage.gateway;

import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;

import java.util.List;

/**
 * Sumo Logic — bounded log search (J6/J8). The request carries an allowlisted
 * scope, a fixed window and a result cap; the gateway must never run an unbounded
 * query. Real impl: Sumo Search-Job API (submit → poll → fetch) with the correct
 * regional endpoint.
 */
public interface SumoGateway {
    List<LogEvidence> search(LogSearchRequest request);
}
