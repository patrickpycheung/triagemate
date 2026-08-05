package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J29 regression, against the REAL Sumo instance: a live search must return at least one row
 * whose MAPPED level is {@code "ERROR"}.
 *
 * <p>This is the single assertion that would have caught J29 before a demo did. Every offline
 * test passed while every real row arrived at level {@code ""} — because {@code MockSumoGateway}
 * populates {@code loglevel} and this estate never does. The engine's one and only selector for
 * the line it cites is {@code "ERROR".equals(l.level())}
 * ({@code DeterministicDiagnosisEngine:286}), so a blank level means {@code errorLine == null},
 * no error token, and both GitLab steps skipping on real data.
 *
 * <p><b>Read-only by construction</b>, like every other file in this package: it issues a search
 * job and deletes it, and writes nothing to the shared instance.
 *
 * <p>The request mirrors what the engine actually sends — literal term {@code ERROR}, last 24h,
 * no {@code _index} clause — so a divergence between what is tested and what runs cannot hide
 * here. Skipped cleanly when {@code secrets.properties} carries no live credentials.
 */
class RealSumoGatewayLevelLiveTest {

    private static final String PROBE_PROJECT_KEY = "sumo.probe.project";
    private static final String PROBE_ENVIRONMENT_KEY = "sumo.probe.environment";
    private static final String DEFAULT_PROBE_PROJECT = "delivery-hazards";
    private static final String DEFAULT_PROBE_ENVIRONMENT = "prod";

    /** Exactly the term the engine sends (DeterministicDiagnosisEngine.SUMO_QUERY_TERM). */
    private static final String ENGINE_QUERY_TERM = "ERROR";

    private static Properties secrets = new Properties();

    @BeforeAll
    static void loadSecrets() {
        secrets = readSecrets();
    }

    private static Properties readSecrets() {
        Properties p = new Properties();
        Path path = Path.of("secrets.properties");
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return p;
    }

    /** Same gate as the other live gateway tests: no credentials → the test does not run. */
    static boolean credentialsPresent() {
        Properties p = readSecrets();
        return !p.getProperty("triage.integrations.sumo.base-url", "").isBlank()
                && !p.getProperty("triage.integrations.sumo.user", "").isBlank()
                && !p.getProperty("triage.integrations.sumo.secret", "").isBlank();
    }

    private static String probe(String key, String fallback) {
        String fromSysProp = System.getProperty(key);
        if (fromSysProp != null && !fromSysProp.isBlank()) return fromSysProp.trim();
        return secrets.getProperty(key, fallback).trim();
    }

    private static RealSumoGateway liveGateway() {
        var blank = new IntegrationProperties.Endpoint("", "", "", "");
        return new RealSumoGateway(new IntegrationProperties(
                blank, blank,
                new IntegrationProperties.Endpoint(
                        secrets.getProperty("triage.integrations.sumo.base-url", ""),
                        secrets.getProperty("triage.integrations.sumo.user", ""),
                        secrets.getProperty("triage.integrations.sumo.secret", ""), ""),
                blank));
    }

    @Test
    @EnabledIf("credentialsPresent")
    void aRealSearchReturnsAtLeastOneRowMappedToErrorLevel() {
        RealSumoGateway gateway = liveGateway();
        var sumo = TriagePropertiesFixture.sumo();

        OffsetDateTime to = OffsetDateTime.now();
        LogSearchRequest req = new LogSearchRequest(
                sumo.sourceCategoryFor(probe(PROBE_PROJECT_KEY, DEFAULT_PROBE_PROJECT),
                        probe(PROBE_ENVIRONMENT_KEY, DEFAULT_PROBE_ENVIRONMENT)),
                sumo.index(),
                ENGINE_QUERY_TERM,
                to.minusDays(1), to,          // the engine's window, verbatim
                sumo.maxResults());

        List<LogEvidence> logs = gateway.search(req);

        assertThat(logs)
                .as("live Sumo returned no rows at all for %s — the probe target has stopped "
                        + "logging; point the test elsewhere with -D%s / -D%s",
                        req.toSumoQuery(), PROBE_PROJECT_KEY, PROBE_ENVIRONMENT_KEY)
                .isNotEmpty();

        LogEvidence firstError = logs.stream()
                .filter(l -> "ERROR".equals(l.level())).findFirst().orElse(null);

        assertThat(firstError)
                .as("J29: no row out of %d mapped to level ERROR. Levels seen: %s. A blank level "
                        + "means the level was read from a structured field this estate does not "
                        + "populate instead of from _raw — the engine then finds no errorLine and "
                        + "both GitLab steps skip on real data.",
                        logs.size(), logs.stream().map(LogEvidence::level).distinct().toList())
                .isNotNull();

        // The line the engine would cite must actually carry its severity in the text, which is
        // where the mapping claims to have read it from.
        assertThat(firstError.message()).contains("ERROR");
    }
}
