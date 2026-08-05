package com.company.triage.gateway.real;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FND-83 — the Confluence base-URL contract.
 *
 * <p>This gateway had <b>zero</b> tests (J22), which is how the 404 in
 * {@code docs/Siyad_Findings.md} §1 reached a live run: the request paths add {@code /wiki}
 * themselves, so a base URL that already ends in {@code /wiki} yields {@code /wiki/wiki/…}.
 * Both spellings are things a person will reasonably configure, so both must work.
 *
 * <p>Deliberately a plain unit test of the normalisation rather than a
 * {@code MockRestServiceServer} round trip: this gateway builds its own {@code RestClient}
 * internally (no injected builder), so there is no seam to bind a mock server to. Giving it
 * one is J22's job; this test pins the rule that actually broke, today.
 */
class RealConfluenceGatewayTest {

    @Test
    void stripsTheWikiContextPathSoItIsNotDoubled() {
        assertEquals("https://auspost.atlassian.net",
                RealConfluenceGateway.siteRoot("https://auspost.atlassian.net/wiki"));
    }

    @Test
    void leavesABareSiteRootAlone() {
        assertEquals("https://auspost.atlassian.net",
                RealConfluenceGateway.siteRoot("https://auspost.atlassian.net"));
    }

    @Test
    void toleratesTrailingSlashesAndSurroundingWhitespace() {
        assertEquals("https://auspost.atlassian.net",
                RealConfluenceGateway.siteRoot("  https://auspost.atlassian.net/wiki/  "));
        assertEquals("https://auspost.atlassian.net",
                RealConfluenceGateway.siteRoot("https://auspost.atlassian.net//"));
    }

    @Test
    void isCaseInsensitiveOnTheContextPath() {
        assertEquals("https://auspost.atlassian.net",
                RealConfluenceGateway.siteRoot("https://auspost.atlassian.net/WIKI"));
    }

    /** A site whose host merely CONTAINS "wiki" must not be truncated. */
    @Test
    void doesNotStripWikiFromAHostname() {
        assertEquals("https://wiki.example.com",
                RealConfluenceGateway.siteRoot("https://wiki.example.com"));
    }

    @Test
    void nullStaysNull() {
        assertNull(RealConfluenceGateway.siteRoot(null));
    }
}
