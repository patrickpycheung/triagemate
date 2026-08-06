package com.company.triage.gateway.real;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J18/GEC-3 — a value cannot change the shape of the encoded query it lands in.
 *
 * <p>ServiceNow encoded queries are a little language: {@code ^} is AND, {@code ^OR} ORs with
 * the preceding condition, {@code ^NQ} starts a new query. Every value this gateway
 * interpolates comes from the incident — the {@code cmdb_ci}, the subject line — i.e. text a
 * reporter typed, and on a public-facing queue text an outsider can influence.
 *
 * <p>The damage is not a crash. A CI named {@code X^ORactive=true} is a perfectly valid wider
 * query that quietly returns rows the triage was never scoped to see, arriving through the
 * same field in the same shape as legitimate ones.
 */
class EncodedQueryValueTest {

    @Test
    void aCaretCannotSmuggleAnAdditionalCondition() {
        assertThat(RealServiceNowGateway.queryValue("Order Portal^ORactive=true"))
                .as("^ is AND and ^OR widens the query — neither may survive into the wire form")
                .doesNotContain("^")
                .doesNotContain("=");
    }

    @Test
    void aNewQueryOperatorCannotStartASecondQuery() {
        assertThat(RealServiceNowGateway.queryValue("Order Portal^NQstate=1"))
                .doesNotContain("^");
    }

    @Test
    void ordinaryNamesArePreservedIntact() {
        assertThat(RealServiceNowGateway.queryValue("Order Portal")).isEqualTo("Order Portal");
        assertThat(RealServiceNowGateway.queryValue("Delivery Hazards")).isEqualTo("Delivery Hazards");
        assertThat(RealServiceNowGateway.queryValue("Track-and-Trace")).isEqualTo("Track-and-Trace");
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(RealServiceNowGateway.queryValue(null)).isEmpty();
        assertThat(RealServiceNowGateway.queryValue("   ")).isEmpty();
    }

    /**
     * Stripped, not escaped — the encoded-query grammar has no escape sequence, so there is no
     * way to say "a literal caret" to ServiceNow. A CI whose real name contains one therefore
     * searches slightly wrong, which is a visible miss. The alternative is a silently broadened
     * query, which is not.
     */
    @Test
    void strippingLeavesAUsableRemainderRatherThanRejectingOutright() {
        assertThat(RealServiceNowGateway.queryValue("A^B")).isEqualTo("A B");
    }
}
