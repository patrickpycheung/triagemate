package com.company.triage.gateway.real;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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

    // --- J22: request shape, proved offline against a bound mock server -----------------

    private static final com.company.triage.config.IntegrationProperties PROPS =
            new com.company.triage.config.IntegrationProperties(null,
                    new com.company.triage.config.IntegrationProperties.Endpoint(
                            "https://auspost.atlassian.net", "svc@example.com", "token", null),
                    null, null);

    private record Fixture(RealConfluenceGateway gateway,
                           org.springframework.test.web.client.MockRestServiceServer server) {}

    private Fixture build() {
        org.springframework.web.client.RestClient.Builder builder =
                org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new RealConfluenceGateway(builder, PROPS), server);
    }

    /**
     * FND-83's failure, pinned end to end: the gateway adds {@code /wiki} itself, so the
     * request path must contain it exactly once regardless of how the base URL was spelled.
     */
    @Test
    void searchRequestsTheWikiPathExactlyOnce() {
        var f = build();
        f.server().expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.containsString("/wiki/rest/api/content/search")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/wiki/wiki"))))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"results\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));

        f.gateway().search("checkout failing");

        f.server().verify();
    }

    /** The CQL query is a query PARAMETER, so a term with metacharacters cannot restructure the URL. */
    @Test
    void theSearchTermIsCarriedAsAnEncodedQueryParameter() {
        var f = build();
        f.server().expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("&limit=999"))))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("{\"results\":[]}", org.springframework.http.MediaType.APPLICATION_JSON));

        f.gateway().search("checkout&limit=999");

        f.server().verify();
    }

    @Test
    void parsesTitleIdAndLinkFromASearchResult() {
        var f = build();
        f.server().expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.containsString("/content/search")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("""
                        {"results":[{"id":"KB001234","title":"Payment reconcile runbook",
                          "_links":{"webui":"/spaces/OPS/pages/KB001234"},
                          "body":{"view":{"value":"<p>Escalation contact: Marcus Chen</p>"}}}]}""",
                                org.springframework.http.MediaType.APPLICATION_JSON));

        var docs = f.gateway().search("payment reconcile");

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).id()).isEqualTo("KB001234");
        assertThat(docs.get(0).title()).isEqualTo("Payment reconcile runbook");
        assertThat(docs.get(0).url()).isEqualTo("/spaces/OPS/pages/KB001234");
        assertThat(docs.get(0).snippet()).contains("Marcus Chen").doesNotContain("<p>");
    }

    /**
     * Documents CURRENT behaviour, and it is behaviour J25/KQR-4 will deliberately change:
     * an HTTP error is swallowed into an empty list, so "the search failed" is indistinguishable
     * from "the search found nothing". That is what turned FND-83's 404 into a clean-looking
     * empty result and a `missingInformation` line asserting no page matched.
     */
    @Test
    void anHttpErrorIsCurrentlySwallowedIntoAnEmptyList_seeJ25() {
        var f = build();
        f.server().expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(org.hamcrest.Matchers.containsString("/content/search")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThat(f.gateway().search("anything"))
                .as("today: indistinguishable from a successful empty search — J25/KQR-4 fixes this")
                .isEmpty();
    }
}
