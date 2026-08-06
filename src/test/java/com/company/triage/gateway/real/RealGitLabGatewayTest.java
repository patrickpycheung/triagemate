package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.GatewayUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * J22 — the real GitLab connector's REQUEST SHAPE, proved offline.
 *
 * <p>This class had <b>zero</b> tests, and that is not incidental: the gateway built its own
 * {@code RestClient} internally, so there was no seam to bind a mock server to. The cost was a
 * live defect — the project id was pre-encoded with {@code URLEncoder} (<code>group/name</code>
 * → <code>group%2Fname</code>) and then passed to {@code build()}, whose TEMPLATE_AND_VALUES
 * encoding escaped the percent a second time (<code>group%252Fname</code>). GitLab resolves
 * that to a project that does not exist, so <b>every real-mode code search would have 404'd on
 * stage</b>, with the blanket {@code catch} in {@code recentCommitters} turning it into a
 * silent empty list.
 *
 * <p>The rule these tests pin: caller-derived text is always a URI <i>variable</i>, never
 * spliced into the template and never pre-encoded. One encoder, one pass.
 */
class RealGitLabGatewayTest {

    private static final IntegrationProperties.Endpoint ENDPOINT =
            new IntegrationProperties.Endpoint("https://gitlab.example.com", null, null, "glpat-xxx");
    private static final IntegrationProperties PROPS =
            new IntegrationProperties(null, null, null, ENDPOINT);

    private record Fixture(RealGitLabGateway gateway, MockRestServiceServer server) {}

    /** J18/GEC-2: the allowlist is a gateway-level bound now, so the gateway needs it. */
    private static final com.company.triage.config.TriageProperties ALLOWLIST =
            allowlistOf("order-payments/payment-service");

    private static com.company.triage.config.TriageProperties allowlistOf(String... projects) {
        var base = com.company.triage.config.TriagePropertiesFixture.deterministic();
        return new com.company.triage.config.TriageProperties(base.engine(), base.writeback(),
                base.orchestrator(), base.agent(), base.trigger(), base.servicenow(), base.sumo(),
                new com.company.triage.config.TriageProperties.GitLab(java.util.List.of(projects)));
    }

    private Fixture build() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new RealGitLabGateway(builder, PROPS, ALLOWLIST), server);
    }

    /**
     * The regression. {@code %2F} is the correct single encoding of the slash in a GitLab
     * project path; {@code %252F} is the double-encoded form that 404s.
     */
    @Test
    void projectIdIsEncodedExactlyOnce() {
        var f = build();
        f.server().expect(requestTo(containsString("/api/v4/projects/order-payments%2Fpayment-service/search")))
                .andExpect(requestTo(not(containsString("%252F"))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", "glpat-xxx"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        f.gateway().searchCode("order-payments/payment-service", "PAYMENT_RECONCILE_MISMATCH");

        f.server().verify();
    }

    @Test
    void searchCodeParsesFilePathAndLineFromTheBlobHit() {
        var f = build();
        f.server().expect(requestTo(containsString("/search")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"path":"src/payment_service.py","startline":44,
                          "data":"raise PaymentError(PAYMENT_RECONCILE_MISMATCH)"}]""",
                        MediaType.APPLICATION_JSON));

        var hits = f.gateway().searchCode("order-payments/payment-service", "PAYMENT_RECONCILE_MISMATCH");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).filePath()).isEqualTo("src/payment_service.py");
        assertThat(hits.get(0).line()).isEqualTo(44);
        assertThat(hits.get(0).project()).isEqualTo("order-payments/payment-service");
    }

    /** An empty result set is a valid answer, not an error. */
    @Test
    void anEmptySearchResultYieldsNoHits() {
        var f = build();
        f.server().expect(requestTo(containsString("/search")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(f.gateway().searchCode("order-payments/payment-service", "NOPE")).isEmpty();
    }

    /** The committer lookup encodes the project id the same single way. */
    @Test
    void recentCommittersEncodesTheProjectIdOnceToo() {
        var f = build();
        f.server().expect(requestTo(containsString("/api/v4/projects/order-payments%2Fpayment-service/repository/tags")))
                .andExpect(requestTo(not(containsString("%252F"))))
                .andRespond(withSuccess("""
                        [{"name":"v1.4.0","commit":{"committed_date":"2026-07-01T00:00:00Z"}}]""",
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/repository/commits")))
                .andExpect(requestTo(not(containsString("%252F"))))
                .andRespond(withSuccess("""
                        [{"author_name":"Priya Nair","author_email":"priya.nair@example.com",
                          "committed_date":"2026-07-20T10:00:00Z"}]""",
                        MediaType.APPLICATION_JSON));

        var contacts = f.gateway().recentCommitters("order-payments/payment-service", "src/payment_service.py");

        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).name()).isEqualTo("Priya Nair");
        assertThat(contacts.get(0).signal()).contains("since v1.4.0");
        f.server().verify();
    }

    /**
     * The fallback, measured on the real estate: {@code delivery-hazards} was tagged more
     * recently than any implicated file last changed, so the since-filtered query returned
     * nothing for all three files and the report named no engineers at all. A file nobody has
     * touched since the last release still has someone who knows it.
     */
    @Test
    void fallsBackToTheMostRecentCommittersWhenNothingLandedSinceTheTag() {
        var f = build();
        f.server().expect(requestTo(containsString("/repository/tags")))
                .andRespond(withSuccess("""
                        [{"name":"v1.4.0","commit":{"committed_date":"2026-07-01T00:00:00Z"}}]""",
                        MediaType.APPLICATION_JSON));
        // bounded query — empty, which is what the real run got
        f.server().expect(requestTo(containsString("since=2026-07-01")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        // the fallback: same path, no time bound
        f.server().expect(requestTo(allOf(containsString("/repository/commits"),
                        not(containsString("since=")))))
                .andRespond(withSuccess("""
                        [{"author_name":"Priya Nair","author_email":"priya.nair@example.com",
                          "committed_date":"2026-01-14T10:00:00Z"}]""",
                        MediaType.APPLICATION_JSON));

        var contacts = f.gateway().recentCommitters("order-payments/payment-service",
                "src/payment_service.py");

        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).name()).isEqualTo("Priya Nair");
        // The two cases must stay tellable apart — a six-month-old name must not read as
        // someone who touched this yesterday.
        assertThat(contacts.get(0).signal())
                .contains("last touched 2026-01-14")
                .contains("nothing since v1.4.0");
        assertThat(contacts.get(0).reason()).startsWith("last changed");
        f.server().verify();
    }

    /** No second call when the first query was already unbounded — there is nothing to widen. */
    @Test
    void anUntaggedRepoDoesNotIssueARedundantSecondQuery() {
        var f = build();
        f.server().expect(requestTo(containsString("/repository/tags")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        f.server().expect(org.springframework.test.web.client.ExpectedCount.once(),
                        requestTo(containsString("/repository/commits")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(f.gateway().recentCommitters("order-payments/payment-service", "src/x.py"))
                .isEmpty();

        f.server().verify();   // fails if a third request was made
    }

    /**
     * A search term with regex/URL metacharacters must not corrupt the query string —
     * the term is incident-derived (an error token lifted from a log line), so it is
     * untrusted text reaching a URL.
     */
    @Test
    void aSearchTermWithMetacharactersIsEncodedNotSplicedIn() {
        var f = build();
        f.server().expect(requestTo(containsString("/search")))
                .andExpect(requestTo(not(containsString("&scope=admin"))))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        f.gateway().searchCode("order-payments/payment-service", "TOKEN&scope=admin");

        f.server().verify();
    }

    @Test
    void searchCodePropagatesErrorsAsGatewayUnavailableException() {
        var f = build();
        f.server().expect(requestTo(containsString("/search")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound());

        assertThatThrownBy(() -> f.gateway().searchCode("order-payments/payment-service", "TOKEN"))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageContaining("GitLab is unreachable")
                .hasMessageContaining("404");
    }
}
