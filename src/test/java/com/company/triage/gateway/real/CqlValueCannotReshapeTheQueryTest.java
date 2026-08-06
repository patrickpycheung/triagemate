package com.company.triage.gateway.real;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J18/GEC-5, Confluence half — answered 2026-08-06.
 *
 * <p>The card asked whether a backslash can terminate the quoted CQL literal, and hoped to
 * record a <em>closed</em> finding. It cannot be closed: it can.
 *
 * <p>Quotes were already neutralised, but a trailing backslash escapes the closing quote the
 * gateway itself adds, so {@code foo\} produces
 * {@code siteSearch ~ "foo\" AND type = page"} — the {@code \"} reads as an escaped quote
 * inside the literal, the string runs on, and {@code AND type = page} is swallowed.
 *
 * <p>The damage is not a syntax error. It is the silent loss of KQR-3's page-type filter, which
 * is how attachments and database objects were being cited as evidence in the first place — so
 * the failure mode is a search that looks like it worked and quietly widens what counts as a
 * runbook.
 */
class CqlValueCannotReshapeTheQueryTest {

    @Test
    void aTrailingBackslashCannotEscapeTheClosingQuote() {
        assertThat(RealConfluenceGateway.cqlValue("Delivery Hazards\\"))
                .as("the AND type = page clause must not become part of the string literal")
                .doesNotContain("\\");
    }

    @Test
    void anEmbeddedQuoteStillCannotCloseTheLiteralEarly() {
        assertThat(RealConfluenceGateway.cqlValue("Delivery \" Hazards")).doesNotContain("\"");
    }

    @Test
    void anOrdinarySystemNameIsUntouched() {
        assertThat(RealConfluenceGateway.cqlValue("Delivery Hazards")).isEqualTo("Delivery Hazards");
        assertThat(RealConfluenceGateway.cqlValue("Order Portal")).isEqualTo("Order Portal");
    }

    @Test
    void nullIsSafe() {
        assertThat(RealConfluenceGateway.cqlValue(null)).isEmpty();
    }
}
