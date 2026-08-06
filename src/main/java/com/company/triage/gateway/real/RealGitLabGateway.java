package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(RealGitLabGateway.class);

    private final RestClient http;

    /**
     * J22: takes the injected {@link RestClient.Builder} (Spring Boot autoconfigures a fresh
     * prototype per injection point) rather than calling {@code RestClient.builder()} directly,
     * so a test can bind a {@code MockRestServiceServer} to it. Without that seam this gateway
     * had <b>zero</b> tests — which is how the double-encoded project id below survived.
     */
    public RealGitLabGateway(RestClient.Builder builder, IntegrationProperties props,
                             com.company.triage.config.TriageProperties triageProps) {
        var gl = props.gitlab();
        this.http = builder
                .baseUrl(gl.baseUrl())
                .defaultHeader("PRIVATE-TOKEN", gl.token())
                .defaultHeader("Accept", "application/json")
                .build();
        // J18/GEC-2: the allowlist lives HERE, at the boundary it protects, not at each
        // caller. It used to be checked in TriageMateTools.searchCode only — so
        // find_recent_committers, which takes a project id from the model just the same, went
        // straight through unchecked. A bound enforced per-caller is a bound that holds only
        // for the callers someone remembered.
        this.allowedProjects = triageProps.gitlab().allowedProjects();
    }

    private final List<String> allowedProjects;

    /**
     * J18/GEC-2 + GEC-4 — every method that takes a project id passes through here, so a new
     * caller (or a new gateway method) cannot silently skip the bound: it has to hold a project
     * id, and holding one means coming through this check.
     */
    private void requireAllowlisted(String project) {
        if (allowedProjects == null || !allowedProjects.contains(project)) {
            throw new IllegalArgumentException("project not allowlisted: " + project
                    + " (triage.gitlab.allowed-projects)");
        }
    }

    @Override
    public List<CodeSearchResult> searchCode(String project, String searchTerm) {
        requireAllowlisted(project);
        log.info("[GitLab] searching {} for code matching \"{}\"", project, searchTerm);
        // J22: pass the RAW project id as a URI VARIABLE and let the UriBuilder encode it
        // exactly once. This used to pre-encode with URLEncoder (group/name -> group%2Fname)
        // and THEN hand the result to build(), whose TEMPLATE_AND_VALUES encoding escaped the
        // percent again -> group%252Fname. GitLab resolves that to a project that does not
        // exist, so EVERY real-mode code search 404'd. Nothing caught it because this class
        // had no tests at all.
        //
        // The rule this establishes, and the reason it is a rule rather than a fix: caller-
        // derived text is ALWAYS a URI variable, NEVER spliced into the template and never
        // pre-encoded. One encoder, one pass, at the boundary that owns the URI.
        try {
            JsonNode hits = http.get()
                    .uri(uri -> uri.path("/api/v4/projects/{id}/search")
                            .queryParam("scope", "blobs")
                            .queryParam("search", searchTerm)
                            .build(project))
                    .retrieve().body(JsonNode.class);
            List<CodeSearchResult> out = new ArrayList<>();
            if (hits != null) hits.forEach(h -> out.add(new CodeSearchResult(
                    project,
                    h.path("path").asText(),
                    h.path("startline").asInt(0),
                    h.path("data").asText(""))));
            return out;
        } catch (Exception e) {
            throw new GatewayUnavailableException("GitLab", e);
        }
    }

    /**
     * Recent committers to one file since the last release/tag (J9). Two calls:
     * newest tag → its commit date, then commits filtered to that path since that
     * date; committers are de-duplicated by email, keeping their most recent commit.
     * Best-effort: any error yields an empty list so the run degrades.
     */
    @Override
    public List<Contact> recentCommitters(String project, String filePath) {
        requireAllowlisted(project);   // J18/GEC-2: the bypass this card exists to close
        log.info("[GitLab] looking up recent committers for {}:{}", project, filePath);
        try {
            // 1. newest tag → its committed date (the "last release" boundary)
            JsonNode tags = http.get()
                    .uri(uri -> uri.path("/api/v4/projects/{id}/repository/tags")
                            .queryParam("per_page", 1).build(project))
                    .retrieve().body(JsonNode.class);
            String tagName = tags != null && !tags.isEmpty() ? tags.get(0).path("name").asText("") : "";
            String since = tags != null && !tags.isEmpty()
                    ? tags.get(0).path("commit").path("committed_date").asText("") : "";

            // 2. commits touching this path since that date (fall back to unfiltered)
            JsonNode commits = http.get()
                    .uri(uri -> {
                        var b = uri.path("/api/v4/projects/{id}/repository/commits")
                                .queryParam("path", filePath)
                                .queryParam("per_page", 20);
                        if (!since.isBlank()) b.queryParam("since", since);
                        return b.build(project);
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
            // J25/KQR-4 + J14/FRI-5: a connector that could not answer must SAY so. Returning
            // an empty list here made a base-URL 404 indistinguishable from a clean no-match,
            // and the report narrated the search as having happened.
            throw new GatewayUnavailableException("GitLab", e);
        }
    }
}
