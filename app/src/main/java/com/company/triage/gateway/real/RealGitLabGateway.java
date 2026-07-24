package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.model.CodeSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real GitLab connector (JS-2) via the project blob-search API. {@code @ConditionalOnProperty(name = "triage.connectors.gitlab", havingValue = "real")}.
 * Auth: PRIVATE-TOKEN (project/group/service-account token). Targeted only — searches
 * one allowlisted project for a concrete term and returns matching file:line; never
 * clones. Alternatively reuse the auspost-mcp gitlab4j client.
 *
 * <p>Network note: self-managed GitLab is often intranet-only — run this app where it
 * can reach GitLab (deployment placement, not an AI problem).
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.gitlab", havingValue = "real")
public class RealGitLabGateway implements GitLabGateway {

    private final RestClient http;

    public RealGitLabGateway(IntegrationProperties props) {
        var gl = props.gitlab();
        this.http = RestClient.builder()
                .baseUrl(gl.baseUrl())
                .defaultHeader("PRIVATE-TOKEN", gl.token())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public List<CodeSearchResult> searchCode(String project, String searchTerm) {
        String projectId = URLEncoder.encode(project, StandardCharsets.UTF_8);   // group/name → group%2Fname
        JsonNode hits = http.get()
                .uri(uri -> uri.path("/api/v4/projects/{id}/search")
                        .queryParam("scope", "blobs")
                        .queryParam("search", searchTerm)
                        .build(projectId))
                .retrieve().body(JsonNode.class);
        List<CodeSearchResult> out = new ArrayList<>();
        if (hits != null) hits.forEach(h -> out.add(new CodeSearchResult(
                project,
                h.path("path").asText(),
                h.path("startline").asInt(0),
                h.path("data").asText(""))));
        return out;
    }
}
