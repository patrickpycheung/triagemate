package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Recent committers to one file since the last release/tag (J9). Two calls:
     * newest tag → its commit date, then commits filtered to that path since that
     * date; committers are de-duplicated by email, keeping their most recent commit.
     * Best-effort: any error yields an empty list so the run degrades.
     */
    @Override
    public List<Contact> recentCommitters(String project, String filePath) {
        String projectId = URLEncoder.encode(project, StandardCharsets.UTF_8);
        try {
            // 1. newest tag → its committed date (the "last release" boundary)
            JsonNode tags = http.get()
                    .uri(uri -> uri.path("/api/v4/projects/{id}/repository/tags")
                            .queryParam("per_page", 1).build(projectId))
                    .retrieve().body(JsonNode.class);
            String tagName = tags != null && tags.size() > 0 ? tags.get(0).path("name").asText("") : "";
            String since = tags != null && tags.size() > 0
                    ? tags.get(0).path("commit").path("committed_date").asText("") : "";

            // 2. commits touching this path since that date (fall back to unfiltered)
            JsonNode commits = http.get()
                    .uri(uri -> {
                        var b = uri.path("/api/v4/projects/{id}/repository/commits")
                                .queryParam("path", filePath)
                                .queryParam("per_page", 20);
                        if (!since.isBlank()) b.queryParam("since", since);
                        return b.build(projectId);
                    })
                    .retrieve().body(JsonNode.class);

            // De-dup by email, first seen = most recent (commits come newest-first).
            Map<String, Contact> byEmail = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            if (commits != null) {
                for (JsonNode c : commits) {
                    String email = c.path("author_email").asText("");
                    String name = c.path("author_name").asText(email);
                    String key = email.isBlank() ? name : email;
                    counts.merge(key, 1, Integer::sum);
                    byEmail.putIfAbsent(key, new Contact(name, email, "gitlab",
                            "recently committed to " + filePath,
                            "%s/%s".formatted(project, filePath),
                            "last commit " + c.path("committed_date").asText("")));
                }
            }
            List<Contact> out = new ArrayList<>();
            for (var e : byEmail.entrySet()) {
                Contact base = e.getValue();
                int n = counts.getOrDefault(e.getKey(), 1);
                String rel = tagName.isBlank() ? "" : " since " + tagName;
                out.add(new Contact(base.name(), base.handle(), base.source(), base.reason(),
                        base.link(), "%d commit%s%s — %s".formatted(
                                n, n == 1 ? "" : "s", rel, base.signal())));
            }
            return out;
        } catch (Exception e) {
            return List.of();   // best-effort
        }
    }
}
