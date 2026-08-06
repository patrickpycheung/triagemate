package com.company.triage.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J18/GEC-5 — the search term narrows within the app-composed scope, never widens it.
 *
 * <p>{@code _sourceCategory} is composed by the app from a project slug and an allowlisted
 * environment, specifically so the model cannot choose where to look. That bound is only as
 * strong as what follows it in the same query string: in Sumo's search expression a bare
 * {@code or} rejoins at the top level, so a term could turn a scoped search into an
 * estate-wide one through the very parameter meant to filter within the scope.
 */
class SumoTermCannotWidenTheScopeTest {

    private static LogSearchRequest withTerm(String term) {
        return new LogSearchRequest("prod/order-portal", "idx", term,
                OffsetDateTime.parse("2026-08-05T10:00:00Z"),
                OffsetDateTime.parse("2026-08-05T10:30:00Z"), 20);
    }

    @Test
    void aBareOrCannotRejoinAtTheTopLevel() {
        String q = withTerm("or _sourceCategory=*").toSumoQuery();

        assertThat(q)
                .as("the composed scope must survive intact")
                .contains("_sourceCategory=prod/order-portal");
        assertThat(q)
                .as("and nothing may follow it that reopens the search")
                .doesNotContain("or ")
                .doesNotContain("*");
    }

    @Test
    void anOrdinaryErrorTokenIsPassedThroughUntouched() {
        assertThat(withTerm("PAYMENT_RECONCILE_MISMATCH").toSumoQuery())
                .as("this is what the parameter is FOR — an error code, a logger, an id")
                .contains("PAYMENT_RECONCILE_MISMATCH");
    }

    @Test
    void aLoggerNameAndAnIdentifierAreBothStillUsable() {
        assertThat(withTerm("com.company.payment.Reconciler").toSumoQuery())
                .contains("com.company.payment.Reconciler");
        assertThat(withTerm("INC-ORD-4471").toSumoQuery()).contains("INC-ORD-4471");
    }

    @Test
    void aRejectedTermDegradesToTheScopeRatherThanFailingTheRun() {
        String q = withTerm("ERROR or *").toSumoQuery();

        assertThat(q)
                .as("a broader-than-intended search of the RIGHT scope is a weaker result, "
                        + "not an unsafe one — and this engine is the fallback that may not throw")
                .isEqualTo("_sourceCategory=prod/order-portal and _index=idx");
    }
}
