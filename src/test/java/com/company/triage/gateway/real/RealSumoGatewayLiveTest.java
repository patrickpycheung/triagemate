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
 * <p>This exists because every other Sumo test is a stub, and the failures it guards are
 * ones only the real API can show: a perfectly well-formed query that returns <b>zero rows</b>
 * reads as "no logs for this incident", not as a bug — exactly the kind of wrong that survives
 * a green test suite.
 *
 * <p><b>Correction (2026-08-05).</b> This javadoc previously asserted that the zero-row cause
 * was a missing {@code _index} clause. That is not true on this estate: measured against the
 * AU instance, the scoped query returns 105 ERROR rows over 24h with <b>no {@code _index}
 * clause at all</b>, and the clause has since been disabled in {@code application.yml} by
 * operator instruction. The real discriminator is the {@code _sourceCategory} — an
 * environment segment naming an environment that exists but is quiet ({@code ptest} carries
 * traffic yet no ERROR lines) returns zero just as convincingly.
 *
 * <p>Run explicitly: {@code mvn test -Dtest=RealSumoGatewayLiveTest}. The project and
 * environment it probes are injectable — see the probe keys below.
 */
class RealSumoGatewayLiveTest {

    /**
     * Which project/environment to probe. Injected, not hardcoded: the target is only a
     * means to reach the API, so baking one in makes the test fail for a reason that has
     * nothing to do with this code the day that project stops logging. Resolution order —
     * system property, then {@code secrets.properties} (same file as the credentials, so
     * the whole live config lives in one gitignored place), then a default.
     *
     * <pre>
     *   mvn test -Dtest=RealSumoGatewayLiveTest \
     *       -Dsumo.probe.project=my-app -Dsumo.probe.environment=prod
     * </pre>
     * or in {@code secrets.properties}:
     * <pre>
     *   sumo.probe.project=my-app
     *   sumo.probe.environment=prod
     * </pre>
     */
    private static final String PROBE_PROJECT_KEY = "sumo.probe.project";
    private static final String PROBE_ENVIRONMENT_KEY = "sumo.probe.environment";
    private static final String DEFAULT_PROBE_PROJECT = "delivery-hazards";
    // prod, not ptest: measured 2026-08-05, ptest carries traffic but no ERROR lines while
    // prod carries 105 in 24h. The demo searches for errors, so prod is the environment that
    // actually exercises the path. Still injectable — see the probe keys above.
    private static final String DEFAULT_PROBE_ENVIRONMENT = "prod";

    private static Properties secrets;

    /** System property → secrets.properties → default. */
    private static String probe(String key, String fallback) {
        String fromSysProp = System.getProperty(key);
        if (fromSysProp != null && !fromSysProp.isBlank()) return fromSysProp.trim();
        return secrets.getProperty(key, fallback).trim();
    }

    private static String probeProject() {
        return probe(PROBE_PROJECT_KEY, DEFAULT_PROBE_PROJECT);
    }

    private static String probeEnvironment() {
        return probe(PROBE_ENVIRONMENT_KEY, DEFAULT_PROBE_ENVIRONMENT);
    }

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
                sumo.sourceCategoryFor(probeProject(), probeEnvironment()),
                sumo.index(),
                "",                       // no term — "everything in this scope+window"
                to.minusMinutes(30), to,  // the same 30-minute bound the app enforces
                sumo.maxResults());

        List<LogEvidence> logs = gateway.search(req);

        // The point of the test: a correctly-formed scope+index query returns rows. If the
        // _index clause were dropped this comes back empty, which is the silent failure.
        assertThat(logs)
                .as("live Sumo returned no rows for %s — either the _index clause regressed, "
                        + "or probe project '%s'/'%s' has stopped logging (point the test at a "
                        + "live one with -D%s / -D%s)",
                        req.toSumoQuery(), probeProject(), probeEnvironment(),
                        PROBE_PROJECT_KEY, PROBE_ENVIRONMENT_KEY)
                .isNotEmpty();

        // Never more than the configured cap.
        assertThat(logs).hasSizeLessThanOrEqualTo(sumo.maxResults());

        // J14/FRI-3 (2026-08-06): this used to assert logger == req.sourceCategory(), i.e. it
        // pinned the defect. _sourceCategory is pinned to ONE composed value for the whole
        // search, so asserting every row carries it proved only that we were copying the
        // query onto the rows — a property of the QUERY presented as a property of the LINE.
        //
        // The logger must now name the EMITTER, and must not be the scope. This live run is
        // what corrected the implementation too: the first cut preferred `_sourcehost`, which
        // on the real instance is `54.66.161.136` — a machine, not a component — while the
        // line itself carries `ap.http.rest.controller`.
        assertThat(logs).allSatisfy(l ->
                assertThat(l.logger())
                        .as("the emitter, never the query's scope")
                        .isNotEqualTo(req.sourceCategory()));
        assertThat(logs)
                .as("at least one row should name a real emitting component — if every row is "
                        + "blank the raw layout has changed and FRI-3's parse needs revisiting")
                .anySatisfy(l -> assertThat(l.logger()).isNotBlank());
    }

    /**
     * FND-85, against the real instance: an ERROR-scoped search must come back with rows whose
     * {@code level()} is actually {@code "ERROR"}.
     *
     * <p>{@link RealSumoGatewayLevelTest} pins the parse against a captured row; this pins the
     * thing that row was captured FROM, because the bug was a field-name mismatch with the
     * live API and only a live call can catch that drifting again. Measured 2026-08-05 on
     * {@code delivery-hazards/prod}: 14 of 20 rows at level ERROR, engine deriving
     * {@code errorToken=GNAF_FRONTAGE}.
     *
     * <p>Asserts "at least one", not a count: how many errors an application logs in 24h is
     * not this test's business, and pinning it would make a real service's quiet day look like
     * a regression.
     */
    @Test
    void errorRowsComeBackWithTheirLevelParsed() {
        RealSumoGateway gateway = liveGateway();

        var sumo = TriagePropertiesFixture.sumo();
        OffsetDateTime to = OffsetDateTime.now();
        List<LogEvidence> logs = gateway.search(new LogSearchRequest(
                sumo.sourceCategoryFor(probeProject(), probeEnvironment()),
                sumo.index(), "ERROR", to.minusHours(24), to, sumo.maxResults()));

        Assumptions.assumeFalse(logs.isEmpty(),
                "no rows in the last 24h for " + probeProject() + "/" + probeEnvironment()
                        + " — nothing to assert a level on");

        assertThat(logs)
                .as("every row came back with an empty level ⇒ the `_loglevel` field name "
                        + "regressed; the engine's errorToken would be permanently null and the "
                        + "GitLab code search would never run (FND-85). Levels seen: %s",
                        logs.stream().map(LogEvidence::level).distinct().toList())
                .anySatisfy(l -> assertThat(l.level()).isEqualTo("ERROR"));
    }

    @Test
    void aNonsenseScopeReturnsNothingRatherThanFailing() {
        RealSumoGateway gateway = liveGateway();

        var sumo = TriagePropertiesFixture.sumo();
        OffsetDateTime to = OffsetDateTime.now();
        List<LogEvidence> logs = gateway.search(new LogSearchRequest(
                sumo.sourceCategoryFor("no-such-project-triagemate-probe", probeEnvironment()),
                sumo.index(), "", to.minusMinutes(10), to, 5));

        // An unknown category is a legitimate empty result, not an error — the engine
        // relies on this to carry on and report "nothing corroborated it".
        assertThat(logs).isEmpty();
    }
}
