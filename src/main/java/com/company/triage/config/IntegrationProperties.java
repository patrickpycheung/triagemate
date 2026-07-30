package com.company.triage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the real connectors (JS-2). Bind from
 * {@code triage.integrations.*} (env vars / application-real.yml). All optional so
 * the default mock demo never needs them.
 */
@ConfigurationProperties(prefix = "triage.integrations")
public record IntegrationProperties(
        Endpoint servicenow,
        Endpoint confluence,
        Endpoint sumo,
        Endpoint gitlab
) {
    /** Generic endpoint: baseUrl + one of (user/secret) basic auth or a bearer token. */
    public record Endpoint(String baseUrl, String user, String secret, String token) {}
}
