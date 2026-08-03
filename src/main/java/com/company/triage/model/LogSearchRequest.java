package com.company.triage.model;

import java.time.OffsetDateTime;

/**
 * A bounded Sumo Logic search request (J6/J8). The app composes {@code sourceCategory}
 * from a configured pattern (project slug + environment) and supplies the window, index
 * and result cap; the model only fills the free-text query term. Guardrails
 * (environment allowlist, window clamp, max results) are enforced before this reaches
 * the gateway.
 *
 * @param index the Sumo index to constrain to, appended as {@code and _index=<index>}.
 *              Blank/null omits the clause.
 */
public record LogSearchRequest(
        String sourceCategory,
        String index,
        String query,
        OffsetDateTime fromTime,
        OffsetDateTime toTime,
        int maxResults
) {
    /** Back-compat overload for callers with no index (mock/test paths). */
    public LogSearchRequest(String sourceCategory, String query,
                            OffsetDateTime fromTime, OffsetDateTime toTime, int maxResults) {
        this(sourceCategory, null, query, fromTime, toTime, maxResults);
    }

    /** The full Sumo query string: scope, optional index clause, then the search term. */
    public String toSumoQuery() {
        StringBuilder sb = new StringBuilder("_sourceCategory=").append(sourceCategory);
        if (index != null && !index.isBlank()) {
            sb.append(" and _index=").append(index);
        }
        if (query != null && !query.isBlank()) {
            sb.append(' ').append(query);
        }
        return sb.toString();
    }
}
