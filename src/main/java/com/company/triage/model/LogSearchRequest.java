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
        String term = safeTerm(query);
        if (!term.isEmpty()) {
            sb.append(' ').append(term);
        }
        return sb.toString();
    }

    /**
     * J18/GEC-5 — the search term may narrow the app-composed scope, never widen it.
     *
     * <p>{@code _sourceCategory} above is composed by the app from a project slug and an
     * allowlisted environment, precisely so the model cannot choose where to look. That bound
     * is only as strong as what follows it: this is one query string, and in Sumo's search
     * expression a bare {@code or} rejoins at the top level. A term of
     * {@code or _sourceCategory=*} would turn a scoped search into an estate-wide one, through
     * a parameter whose whole purpose is to filter <em>within</em> the scope.
     *
     * <p>Constrained here rather than in the instruction, because FND-44 already records that
     * prompt-only guardrails are prompt-only. A term is one bare token — the shape of an error
     * code, a logger name or an identifier, which is everything this parameter is for.
     * Anything else is dropped, and dropping degrades the search to the scope alone rather
     * than failing the run: a broader-than-intended search of the RIGHT scope is a weaker
     * result, not an unsafe one.
     */
    static String safeTerm(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        String trimmed = rawQuery.trim();
        return trimmed.matches("[A-Za-z0-9_.:\\-]+") ? trimmed : "";
    }
}
