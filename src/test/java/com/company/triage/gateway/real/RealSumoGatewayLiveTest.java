package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the REAL Sumo Logic API. Opt-in: skipped unless {@code secrets.properties} at the
 * repo root carries live {@code triage.integrations.sumo.*} values, so a normal
 * {@code mvn test} on a machine with no credentials stays green and offline.
 *
 * <p>This exists because every other Sumo test is a stub, and the failure this guards is
 * one only the real API can show: a query missing the {@code _index} clause is perfectly
 * well-formed and returns <b>zero rows</b> against the corporate instance. That reads as
 * "no logs for this incident", not as a bug — exactly the kind of wrong that survives a
 * green test suite. Verified 2026-08-03 against the AU instance.
 *
 * <p>Run explicitly: {@code mvn test -Dtest=RealSumoGatewayLiveTest}
 */
class RealSumoGatewayLiveTest {

    /** A project known to log into the estate, used only as a probe target. */
    private static final String PROBE_PROJECT = "delivery-hazards";
    private static final String PROBE_ENVIRONMENT = "ptest";

    private static Properties secrets;

    @BeforeAll
    static void loadSecrets() {
        secrets = new Properties();
        Path p = Path.of("secrets.properties");
        if (Files.exists(p)) {
            try (var in = Files.newInputStream(p)) {
                secrets.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static RealSumoGateway liveGateway() {
        String base = secrets.getProperty("triage.integrations.sumo.base-url", "");
        String user = secrets.getProperty("triage.integrations.sumo.user", "");
        String secret = secrets.getProperty("triage.integrations.sumo.secret", "");
        Assumptions.assumeTrue(!base.isBlank() && !user.isBlank() && !secret.isBlank(),
                "no live Sumo credentials in secrets.properties — skipping live API test");

        var blank = new IntegrationProperties.Endpoint("", "", "", "");
        return new RealSumoGateway(new IntegrationProperties(
                blank, blank,
                new IntegrationProperties.Endpoint(base, user, secret, ""),
                blank));
    }

    @Test
    void theRealApiAnswersAScopedIndexedSearch() {
        RealSumoGateway gateway = liveGateway();

        var sumo = TriagePropertiesFixture.sumo();
        OffsetDateTime to = OffsetDateTime.now();
        LogSearchRequest req = new LogSearchRequest(
                sumo.sourceCategoryFor(PROBE_PROJECT, PROBE_ENVIRONMENT),
                sumo.index(),
                "",                       // no term — "everything in this scope+window"
                to.minusMinutes(30), to,  // the same 30-minute bound the app enforces
                sumo.maxResults());

        List<LogEvidence> logs = gateway.search(req);

        // The point of the test: a correctly-formed scope+index query returns rows. If the
        // _index clause were dropped this comes back empty, which is the silent failure.
        assertThat(logs)
                .as("live Sumo returned no rows for %s — check the _index clause and that "
                        + "the probe project is still logging", req.toSumoQuery())
                .isNotEmpty();

        // Never more than the configured cap, and every row is from the scope we asked for.
        assertThat(logs).hasSizeLessThanOrEqualTo(sumo.maxResults());
        assertThat(logs).allSatisfy(l ->
                assertThat(l.logger()).isEqualTo(req.sourceCategory()));
    }

    @Test
    void aNonsenseScopeReturnsNothingRatherThanFailing() {
        RealSumoGateway gateway = liveGateway();

        var sumo = TriagePropertiesFixture.sumo();
        OffsetDateTime to = OffsetDateTime.now();
        List<LogEvidence> logs = gateway.search(new LogSearchRequest(
                sumo.sourceCategoryFor("no-such-project-triagemate-probe", "ptest"),
                sumo.index(), "", to.minusMinutes(10), to, 5));

        // An unknown category is a legitimate empty result, not an error — the engine
        // relies on this to carry on and report "nothing corroborated it".
        assertThat(logs).isEmpty();
    }
}
