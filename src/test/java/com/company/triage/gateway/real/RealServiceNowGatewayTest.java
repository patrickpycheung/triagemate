package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * FND-14: {@code addWorkNote} must be idempotent against the REAL ServiceNow instance,
 * not just the mock. Asserted against actual HTTP requests via {@link MockRestServiceServer}
 * rather than an extracted predicate — the bug was in the request/response wiring
 * (unconditional PATCH), so the regression test needs to exercise that wiring.
 */
class RealServiceNowGatewayTest {

    private static final IntegrationProperties.Endpoint ENDPOINT =
            new IntegrationProperties.Endpoint("https://dev12345.service-now.com", "svc", "secret", null);
    private static final IntegrationProperties PROPS =
            new IntegrationProperties(ENDPOINT, null, null, null);

    private record Fixture(RealServiceNowGateway gateway, MockRestServiceServer server) {}

    private Fixture build() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new RealServiceNowGateway(builder, PROPS, "work_notes"), server);
    }

    private void expectSysIdLookup(MockRestServiceServer server) {
        // requestTo(containsString(...)) rather than an exact queryParam() match: the
        // "number=INC0012345" value gets percent-encoded inside sysparm_query, and the
        // encoding is an implementation detail, not part of what this test verifies.
        server.expect(requestTo(containsString("/api/now/table/incident?")))
                .andExpect(requestTo(not(containsString("sys_journal_field"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"result\":[{\"sys_id\":\"abc123\"}]}", MediaType.APPLICATION_JSON));
    }

    @Test
    void postsWhenNoIdenticalNoteExists() {
        var f = build();
        expectSysIdLookup(f.server());
        f.server().expect(requestTo(containsString("/api/now/table/sys_journal_field")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/api/now/table/incident/abc123")))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("first note")))
                .andRespond(withSuccess());

        f.gateway().addWorkNote("INC0012345", "first note");

        f.server().verify();   // the PATCH expectation must actually have fired
    }

    /**
     * The bug this test exists for: a retried request (flaky proxy, manual re-trigger, a
     * poller edge case) must NOT post a second identical comment onto a real ticket.
     */
    @Test
    void skipsWhenIdenticalNoteAlreadyExists() {
        var f = build();
        expectSysIdLookup(f.server());
        f.server().expect(requestTo(containsString("/api/now/table/sys_journal_field")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"result\":[{\"value\":\"a different note\"},{\"value\":\"duplicate note\"}]}",
                        MediaType.APPLICATION_JSON));
        // Deliberately NO expectation for a PATCH request — MockRestServiceServer fails
        // the test if an unexpected request is made, so this proves no PATCH happened.

        f.gateway().addWorkNote("INC0012345", "duplicate note");

        f.server().verify();
    }

    @Test
    void anExactPrefixIsNotTreatedAsADuplicate() {
        var f = build();
        expectSysIdLookup(f.server());
        f.server().expect(requestTo(containsString("/api/now/table/sys_journal_field")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"result\":[{\"value\":\"note\"}]}", MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/api/now/table/incident/abc123")))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess());

        f.gateway().addWorkNote("INC0012345", "note (longer)");   // not an exact match

        f.server().verify();
    }
}
