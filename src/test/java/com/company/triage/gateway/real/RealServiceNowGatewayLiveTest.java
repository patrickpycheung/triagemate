package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.model.IncidentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.company.triage.model.ResolvedIncident;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE test against the real ServiceNow dev instance.
 *
 * <p><b>Read-only by construction.</b> Every method here fetches; none calls
 * {@code addWorkNote}. That is deliberate and should stay that way: a write test against a
 * shared instance posts advisory comments onto a real ticket every time CI runs, and the
 * idempotency guard (FND-14) makes the SECOND run silently pass while the first already
 * dirtied the ticket. The write path is covered offline by
 * {@code RealServiceNowGatewayTest} against a mock server, which can assert the PATCH body
 * without touching anything.
 *
 * <p>Skips cleanly without credentials — see {@code RealConfluenceGatewayLiveTest} for why
 * skipping beats failing here.
 */
class RealServiceNowGatewayLiveTest {

    /** The incident from the field report (docs/Siyad_Findings.md). */
    private static final String PROBE_INCIDENT = "INC0010010";

    private static Properties secrets() {
        Properties p = new Properties();
        Path file = Path.of("secrets.properties");
        if (Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (Exception ignored) {
                // treated as "no credentials"
            }
        }
        return p;
    }

    static boolean credentialsPresent() {
        Properties p = secrets();
        return notBlank(p.getProperty("triage.integrations.servicenow.base-url"))
                && notBlank(p.getProperty("triage.integrations.servicenow.user"))
                && notBlank(p.getProperty("triage.integrations.servicenow.secret"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private RealServiceNowGateway gateway() {
        Properties p = secrets();
        var endpoint = new IntegrationProperties.Endpoint(
                p.getProperty("triage.integrations.servicenow.base-url"),
                p.getProperty("triage.integrations.servicenow.user"),
                p.getProperty("triage.integrations.servicenow.secret"), null);
        return new RealServiceNowGateway(RestClient.builder(),
                new IntegrationProperties(endpoint, null, null, null),
                TriagePropertiesFixture.deterministic());
    }

    /** Same gateway, with operator-pinned similar incidents bound. */
    private RealServiceNowGateway gatewayWithPins(java.util.Map<String, java.util.List<String>> pins) {
        Properties p = secrets();
        var endpoint = new IntegrationProperties.Endpoint(
                p.getProperty("triage.integrations.servicenow.base-url"),
                p.getProperty("triage.integrations.servicenow.user"),
                p.getProperty("triage.integrations.servicenow.secret"), null);
        var base = TriagePropertiesFixture.deterministic();
        var props = new com.company.triage.config.TriageProperties(
                base.engine(), base.writeback(), base.orchestrator(), base.agent(), base.trigger(),
                new com.company.triage.config.TriageProperties.ServiceNow("work_notes", "1,6,7", 0.25, 5, pins),
                base.sumo(), base.gitlab());
        return new RealServiceNowGateway(RestClient.builder(),
                new IntegrationProperties(endpoint, null, null, null), props);
    }

    /**
     * J24/SFF-1 proved against the instance that produced the bug.
     *
     * <p>{@code cmdb_ci} really does come back as {@code {"display_value": …, "link": …}} —
     * confirmed live — and {@code JsonNode.asText()} on that object returns the empty string.
     * That is what made the affected system blank on every real incident and sent the Sumo
     * scope after a fragment of the subject line. The offline test pins the parse against a
     * captured copy of this row; this one proves the captured copy is still what the instance
     * actually sends.
     */
    @Test
    @EnabledIf("credentialsPresent")
    void referenceFieldsFromTheRealInstanceUnwrapToTheirDisplayValue() {
        IncidentContext inc = gateway().getIncident(PROBE_INCIDENT);

        assertThat(inc.number()).isEqualTo(PROBE_INCIDENT);
        assertThat(inc.configurationItem())
                .as("the CMDB names the affected system; a blank here is the J24 regression")
                .isEqualTo("Delivery Hazards");
        assertThat(inc.caller())
                .as("caller_id is a reference field too — same unwrapping rule")
                .isNotBlank();
        assertThat(inc.shortDescription()).containsIgnoringCase("hazards");
    }

    /**
     * J14/FRI-1: {@code opened_at} must parse. A null here silently removes the log-search
     * window, and before J14 it threw an NPE inside the fallback engine.
     */
    @Test
    @EnabledIf("credentialsPresent")
    void openedAtParsesFromTheRealInstancesDateFormat() {
        assertThat(gateway().getIncident(PROBE_INCIDENT).openedAt())
                .as("unparseable opened_at degrades the whole log search silently")
                .isNotNull();
    }

    /** FND-53/FND-54: an unknown number is a clean typed not-found, never a fabricated context. */
    @Test
    @EnabledIf("credentialsPresent")
    void anUnknownIncidentNumberIsACleanNotFound() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> gateway().getIncident("INC0009999999"))
                .isInstanceOf(com.company.triage.gateway.IncidentNotFoundException.class);
    }

    /**
     * The demo incident must find its real twins and rank them above the unrelated cluster.
     *
     * <p>The instance holds two fault clusters under the Delivery Hazards CI: tickets sharing
     * the probe's subject ("Hazards being recorded on handheld are not appearing…") and an
     * unrelated set ("All hazards are no longer present"). The old first-word search returned
     * 0 rows for every one of them.
     *
     * <p>Asserts the PROPERTY (every perfect-score twin outranks every weaker hit), not a
     * specific incident number. An earlier draft asserted {@code INC0010012} exactly and
     * broke within the hour when a teammate added {@code INC0010013} with the same subject —
     * it tied at 1.0 and took first place on the recency tiebreak. The code was right and the
     * assertion was wrong: this is a shared instance that people add demo tickets to, so
     * pinning a number here bakes in a failure with a fuse on it.
     */
    @Test
    @EnabledIf("credentialsPresent")
    void similarIncidentsRanksGenuineDuplicatesAboveTheUnrelatedSameApplicationCluster() {
        var gateway = gateway();
        var probe = gateway.getIncident(PROBE_INCIDENT);
        var similar = gateway.findSimilarIncidents(probe);

        assertThat(similar).as("same-CI search must return the sibling tickets").isNotEmpty();
        assertThat(similar).extracting(ResolvedIncident::number)
                .as("an incident is never its own duplicate").doesNotContain(PROBE_INCIDENT);

        assertThat(similar.get(0).shortDescription())
                .as("the top hit must be a genuine duplicate — same subject — not merely "
                        + "another ticket against the same application")
                .isEqualToIgnoringWhitespace(probe.shortDescription());

        // The ordering contract, expressed without depending on SimilarIncidentRanker's
        // exact weights: nothing may outscore the top hit, and the unrelated cluster must
        // score strictly lower than the twin. Asserting an absolute value (1.0) would couple
        // this live test to the weighting constants, which are the ranker's own unit-test job.
        assertThat(similar).extracting(ResolvedIncident::similarity)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(similar)
                .filteredOn(r -> r.shortDescription() != null
                        && r.shortDescription().toLowerCase(java.util.Locale.ROOT)
                                .contains("no longer present"))
                .allSatisfy(unrelated -> assertThat(unrelated.similarity())
                        .as("a different fault against the same CI must rank below a true twin")
                        .isLessThan(similar.get(0).similarity()));
    }

    /** A pinned duplicate outranks every search hit and carries its real subject. */
    @Test
    @EnabledIf("credentialsPresent")
    void operatorPinnedIncidentIsReturnedFirstWithItsRealSubject() {
        var gateway = gatewayWithPins(java.util.Map.of(PROBE_INCIDENT, java.util.List.of("INC0010005")));
        var similar = gateway.findSimilarIncidents(gateway.getIncident(PROBE_INCIDENT));

        assertThat(similar.get(0).number()).isEqualTo("INC0010005");
        assertThat(similar.get(0).similarity()).isEqualTo(1.0);
        assertThat(similar.get(0).shortDescription())
                .as("a pin must be fetched, not fabricated from the configured id")
                .containsIgnoringCase("hazards");
        assertThat(similar).extracting(ResolvedIncident::number)
                .as("the pin must not also appear as a search hit").containsOnlyOnce("INC0010005");
    }

    /** A stale pin must be dropped, not surfaced as a ticket nobody can open. */
    @Test
    @EnabledIf("credentialsPresent")
    void aPinnedIncidentThatDoesNotExistIsSkipped() {
        var gateway = gatewayWithPins(java.util.Map.of(PROBE_INCIDENT, java.util.List.of("INC9999999")));
        var similar = gateway.findSimilarIncidents(gateway.getIncident(PROBE_INCIDENT));

        assertThat(similar).extracting(ResolvedIncident::number).doesNotContain("INC9999999");
    }

    /**
     * Ownership must resolve for the demo CI — the gap that made this call dead weight.
     *
     * <p>Two independent defects, both proven live. (1) The query ran against
     * {@code cmdb_ci_service}, but "Delivery Hazards" is a {@code cmdb_ci_web_application};
     * ServiceNow table inheritance means only the BASE {@code cmdb_ci} table returns every
     * CI class, so the old query returned 0 rows for every application CI. (2) The CI's
     * {@code support_group} was empty, so even the corrected query answered with a blank
     * group — which {@code findOwnership} now reports as absent rather than as a false
     * "ownership found".
     *
     * <p>This asserts the SUPPORT GROUP, not merely that a record came back: a CI with no
     * group is the exact state that made the tool useless, and an existence-only assertion
     * would have passed throughout.
     */
    @Test
    @EnabledIf("credentialsPresent")
    void ownershipResolvesToASupportGroupForTheDemoApplication() {
        var ownership = gateway().findOwnership("Delivery Hazards");

        assertThat(ownership)
                .as("the CI exists in cmdb_ci; an empty Optional means the lookup regressed "
                        + "to a service-only table or the CI lost its support_group")
                .isPresent();
        assertThat(ownership.get().supportGroup())
                .as("a blank group is the failure this test exists to catch")
                .isNotBlank();
        assertThat(ownership.get().application()).isEqualTo("Delivery Hazards");
    }

    /** An application nobody owns must answer absent, never a record with a blank group. */
    @Test
    @EnabledIf("credentialsPresent")
    void anUnknownApplicationYieldsNoOwnership() {
        assertThat(gateway().findOwnership("No Such Application XYZZY")).isEmpty();
    }

    /** The K1 poller's feed must answer against the real table without error. */
    @Test
    @EnabledIf("credentialsPresent")
    void thePollerFeedAnswersWithoutError() {
        var found = gateway().findIncidentsCreatedSince(
                java.time.OffsetDateTime.now().minusDays(30), 5);

        assertThat(found).as("a well-formed query must not throw").isNotNull();
        assertThat(found.size()).isLessThanOrEqualTo(5);
        assertThat(found).allSatisfy(n -> {
            assertThat(n.number()).startsWith("INC");
            assertThat(n.createdAt()).as("an undateable row would corrupt the cursor").isNotNull();
        });
    }
}
