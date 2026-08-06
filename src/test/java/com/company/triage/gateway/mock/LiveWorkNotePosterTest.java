package com.company.triage.gateway.mock;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.fixture.FixtureSession;
import com.company.triage.gateway.fixture.FixtureStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

/**
 * The one place a MOCKED run deliberately touches the real world: the two advisory comments
 * still go to the live ticket.
 *
 * <p>What must hold is less "it posts" than "it never costs anything when it can't". A demo
 * that dies because a ticket was renamed, the instance was asleep, or the laptop was off the
 * network is strictly worse than one that quietly skips a comment — the diagnosis is the
 * deliverable. So the failure paths are pinned harder than the happy one.
 */
class LiveWorkNotePosterTest {

    private static IntegrationProperties props(String baseUrl, String user, String secret) {
        return new IntegrationProperties(
                new IntegrationProperties.Endpoint(baseUrl, user, secret, null),
                null, null, null);
    }

    private record Fixture(LiveWorkNotePoster poster, MockRestServiceServer server) {}

    private Fixture configured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new LiveWorkNotePoster(builder,
                props("https://snow.example.com", "svc", "pw"),
                TriagePropertiesFixture.deterministic()), server);
    }

    @Test
    void withoutCredentialsItIsInertRatherThanBroken() {
        LiveWorkNotePoster poster = new LiveWorkNotePoster(RestClient.builder(),
                props("", "", ""), TriagePropertiesFixture.deterministic());

        assertThat(poster.isConfigured()).isFalse();
        // The offline demo on a fresh clone: no credentials, no attempt, no noise.
        assertThat(poster.tryPost("INC0010015", "note")).isFalse();
    }

    @Test
    void aConfiguredPosterWritesTheNoteToTheLiveTicket() {
        var f = configured();
        // 1. the existence check, 2. the gateway's own sys_id lookup,
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withSuccess("{\"result\":[{\"sys_id\":\"abc123\"}]}",
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withSuccess("{\"result\":[{\"sys_id\":\"abc123\"}]}",
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("sys_journal_field")))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/api/now/table/incident/abc123")))
                .andRespond(withSuccess());

        assertThat(f.poster().tryPost("INC0010015", "advisory note")).isTrue();
        f.server().verify();
    }

    /** The case the demo will actually hit: a stand-in number that exists nowhere. */
    @Test
    void anUnknownTicketIsReportedAsNotPostedRatherThanThrowing() {
        var f = configured();
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

        assertThat(f.poster().tryPost("INC0042424", "advisory note")).isFalse();
    }

    @Test
    void anInstanceFailureIsSwallowed() {
        var f = configured();
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withServerError());

        assertThatCode(() -> assertThat(f.poster().tryPost("INC0010015", "note")).isFalse())
                .doesNotThrowAnyException();
    }

    /**
     * The contract that matters at the call site: whatever the poster does, the mock gateway's
     * addWorkNote returns normally. A demo must not break over a comment.
     */
    @Test
    void theMockGatewaySurvivesAPosterThatAlwaysFails() {
        var f = configured();
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withServerError());

        var gateway = new MockServiceNowGateway(FixtureStore.none(), new FixtureSession(),
                MockLatency.none(), f.poster());

        assertThatCode(() -> gateway.addWorkNote("INC0010015", "advisory note"))
                .doesNotThrowAnyException();
    }

    /**
     * The same note text on TWO different tickets must post twice.
     *
     * <p>The diagnosis note does not contain the incident number, so under the stand-in
     * feature two tickets legitimately produce byte-identical notes. The mock's idempotency
     * list was keyed on the note alone, which made the second ticket's post a silent no-op —
     * invisible while writes only went to a log, a missing comment on a real ticket once they
     * did not. Reproduced against the live instance before this test existed.
     */
    @Test
    void anIdenticalNoteOnADifferentTicketIsStillPosted() {
        var f = configured();
        // Two tickets, same note text: four requests each (exists, sys_id, journal, patch).
        for (String sysId : new String[]{"aaa", "bbb"}) {
            f.server().expect(requestTo(containsString("/api/now/table/incident")))
                    .andRespond(withSuccess("{\"result\":[{\"sys_id\":\"" + sysId + "\"}]}",
                            MediaType.APPLICATION_JSON));
            f.server().expect(requestTo(containsString("/api/now/table/incident")))
                    .andRespond(withSuccess("{\"result\":[{\"sys_id\":\"" + sysId + "\"}]}",
                            MediaType.APPLICATION_JSON));
            f.server().expect(requestTo(containsString("sys_journal_field")))
                    .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
            f.server().expect(requestTo(containsString("/api/now/table/incident/" + sysId)))
                    .andRespond(withSuccess());
        }

        var gateway = new MockServiceNowGateway(FixtureStore.none(), new FixtureSession(),
                MockLatency.none(), f.poster());
        gateway.addWorkNote("INC0010015", "identical advisory note");
        gateway.addWorkNote("INC0010012", "identical advisory note");

        f.server().verify();   // fails if the second ticket was skipped
    }

    /** ...while a repeat on the SAME ticket is still suppressed before it reaches the wire. */
    @Test
    void aRepeatOnTheSameTicketIsStillDeduped() {
        var f = configured();
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withSuccess("{\"result\":[{\"sys_id\":\"aaa\"}]}",
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/api/now/table/incident")))
                .andRespond(withSuccess("{\"result\":[{\"sys_id\":\"aaa\"}]}",
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("sys_journal_field")))
                .andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(containsString("/api/now/table/incident/aaa")))
                .andRespond(withSuccess());

        var gateway = new MockServiceNowGateway(FixtureStore.none(), new FixtureSession(),
                MockLatency.none(), f.poster());
        gateway.addWorkNote("INC0010015", "identical advisory note");
        gateway.addWorkNote("INC0010015", "identical advisory note");

        f.server().verify();   // exactly one round trip, not two
    }
}
