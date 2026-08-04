package com.company.triage.model;

import com.company.triage.config.TriagePropertiesFixture;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact Sumo query string this app sends.
 *
 * <p>Both clauses are load-bearing against the real estate, and the {@code _index} one is
 * the easy one to lose: without it a search against the corporate instance returns zero
 * rows <i>every time</i>, which looks like "no logs for this incident" rather than like a
 * malformed query. That failure is silent and completely plausible, so it gets a test.
 *
 * <p>Verified against the live AU instance 2026-08-03: this exact shape returned 29,068
 * messages for {@code delivery-hazards/ptest} over 24h, and 20 (the cap) over 30 minutes.
 */
class LogSearchRequestQueryTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-08-03T00:00:00Z");

    @Test
    void queryCarriesBothTheSourceCategoryAndTheIndexClause() {
        LogSearchRequest req = new LogSearchRequest(
                "IDT/ITServices/Tomcat/delivery-hazards/ptest/AppEvt_delivery-hazards",
                "Global_Standard_Infrequent",
                "ORD-4471", T0, T0.plusMinutes(30), 20);

        assertThat(req.toSumoQuery()).isEqualTo(
                "_sourceCategory=IDT/ITServices/Tomcat/delivery-hazards/ptest/AppEvt_delivery-hazards"
                        + " and _index=Global_Standard_Infrequent ORD-4471");
    }

    @Test
    void theCategoryComposedFromConfigMatchesTheEstateConvention() {
        // The pattern in application.yml, expanded — this is what the app will actually
        // send for an incident on delivery-hazards in ptest.
        String category = TriagePropertiesFixture.sumo()
                .sourceCategoryFor("delivery-hazards", "ptest");

        assertThat(category)
                .isEqualTo("IDT/ITServices/Tomcat/delivery-hazards/ptest/AppEvt_delivery-hazards");
    }

    @Test
    void aBlankIndexOmitsTheClauseRatherThanEmittingAnEmptyOne() {
        // `_index=` with nothing after it is not a valid filter — an unset index must drop
        // the clause entirely, not send a broken one.
        LogSearchRequest req = new LogSearchRequest("cat", "", "q", T0, T0.plusMinutes(1), 5);

        assertThat(req.toSumoQuery()).isEqualTo("_sourceCategory=cat q");
        assertThat(req.toSumoQuery()).doesNotContain("_index");
    }

    @Test
    void anEmptySearchTermStillProducesAValidScopedQuery() {
        // "everything in this category+index for this window" is a legitimate search.
        LogSearchRequest req = new LogSearchRequest("cat", "idx", "", T0, T0.plusMinutes(1), 5);

        assertThat(req.toSumoQuery()).isEqualTo("_sourceCategory=cat and _index=idx");
    }
}
