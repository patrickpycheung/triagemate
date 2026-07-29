package com.company.triage.model;

import java.time.OffsetDateTime;

/**
 * A bounded Sumo Logic search request (J6/J8). The orchestrator supplies the scope,
 * window and result cap; the model only fills the query term. Guardrails
 * (allowlist + max results) are enforced before this reaches the gateway.
 */
public record LogSearchRequest(
        String sourceCategory,
        String query,
        OffsetDateTime fromTime,
        OffsetDateTime toTime,
        int maxResults
) {}
