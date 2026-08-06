package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.model.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE test against the real GitLab instance.
 *
 * <p><b>Gated on REACHABILITY as well as credentials</b>, unlike the other live tests. GitLab
 * here is self-managed and intranet-only — J3's own note says "run this app where it can reach
 * GitLab (deployment placement, not an AI problem)". From outside the corporate network the
 * host answers an nginx <b>HTML 403</b> at the edge, before the API is ever consulted, which
 * is emphatically not the same as a bad token: a rejected token returns a JSON 401 from
 * GitLab itself. Treating the former as a test failure would train everyone to ignore a red
 * suite, so this skips instead — and says which of the two it saw.
 *
 * <p>What that means in practice: on a developer machine off the corporate network these tests
 * SKIP; on the demo laptop, or anywhere with intranet routing, they RUN and are the only proof
 * that the {@code %252F} double-encoding fix (J22) holds against a real GitLab. The offline
 * {@code RealGitLabGatewayTest} pins the request shape either way.
 */
class RealGitLabGatewayLiveTest {

    /** Probe target: an allowlisted project from application.yml. */
    private static final String PROBE_PROJECT = "order-payments/payment-service";

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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Credentials present AND the API genuinely answering.
     *
     * <p>A TCP connect is NOT sufficient, which cost a first attempt at this gate: the
     * corporate edge accepts the connection on 443 and then serves an nginx <b>HTML</b> 403,
     * so the socket opens perfectly while the API is unreachable. The gate therefore makes a
     * real, cheap API call and insists the answer be JSON — GitLab always answers its own API
     * in JSON, including for a rejected token (401), so "not JSON" means something in front
     * of GitLab answered instead of GitLab.
     *
     * <p>Deliberately does NOT require 2xx. A JSON 401/403 is GitLab itself saying the token
     * is wrong — that is a genuine credential defect and the tests SHOULD run and fail. Only
     * a non-JSON answer means "wrong network", and only that skips.
     */
    static boolean gitLabApiAnswers() {
        Properties p = secrets();
        String base = p.getProperty("triage.integrations.gitlab.base-url");
        String token = p.getProperty("triage.integrations.gitlab.token");
        if (!notBlank(base) || !notBlank(token)) {
            return false;
        }
        try {
            var url = URI.create(base.replaceAll("/+$", "") + "/api/v4/projects?per_page=1&simple=true").toURL();
            var conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("PRIVATE-TOKEN", token);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String contentType = String.valueOf(conn.getContentType());
            conn.disconnect();
            boolean json = contentType.contains("json");
            if (!json) {
                System.out.println("[RealGitLabGatewayLiveTest] skipping — " + base
                        + " answered HTTP " + code + " with content-type " + contentType
                        + " (an edge/proxy answered, not the GitLab API; this host is "
                        + "intranet-only per J3). Run these on the demo laptop.");
            }
            return json;
        } catch (Exception unreachable) {
            System.out.println("[RealGitLabGatewayLiveTest] skipping — " + base
                    + " unreachable: " + unreachable);
            return false;
        }
    }

    private RealGitLabGateway gateway() {
        Properties p = secrets();
        var endpoint = new IntegrationProperties.Endpoint(
                p.getProperty("triage.integrations.gitlab.base-url"), null, null,
                p.getProperty("triage.integrations.gitlab.token"));
        var base = com.company.triage.config.TriagePropertiesFixture.deterministic();
        return new RealGitLabGateway(RestClient.builder(),
                new IntegrationProperties(null, null, null, endpoint), base);
    }

    /**
     * J22's regression against a real GitLab: the project id must survive as {@code %2F}.
     * A double-encoded {@code %252F} resolves to a project that does not exist, so this would
     * throw a 404 rather than return an empty list.
     */
    @Test
    @EnabledIf("gitLabApiAnswers")
    void aScopedCodeSearchAnswersWithoutA404() {
        List<CodeSearchResult> hits = gateway().searchCode(PROBE_PROJECT, "PAYMENT_RECONCILE_MISMATCH");

        // Zero hits is a legitimate answer (the token may not exist in that repo); a 404 from
        // a double-encoded id is not, and would surface as an exception out of searchCode.
        assertThat(hits).as("a well-formed search must not throw").isNotNull();
        assertThat(hits).allSatisfy(h -> {
            assertThat(h.project()).isEqualTo(PROBE_PROJECT);
            assertThat(h.filePath()).isNotBlank();
        });
    }

    /** The committer lookup encodes the same way and degrades to empty rather than throwing. */
    @Test
    @EnabledIf("gitLabApiAnswers")
    void theCommitterLookupAnswersWithoutThrowing() {
        assertThat(gateway().recentCommitters(PROBE_PROJECT, "src/payment_service.py")).isNotNull();
    }
}
