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
