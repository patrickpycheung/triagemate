package com.company.triage.agent;

import com.company.triage.gateway.*;
import com.company.triage.model.*;
import com.google.adk.tools.Annotations.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The tools the ADK agent may call (J3 gateways exposed as ADK FunctionTools, J2).
 * ADK discovers these via {@code FunctionTool.create(TriageMateTools.class, "<method>")};
 * the {@code @Schema} text is what the model sees. Bounds/allowlists (J8) are applied
 * here and re-checked in {@link BoundsCallback}.
 *
 * <p>ADK function-tool methods are static, so gateways are reached through a bridge
 * populated once at startup ({@link #wire}). JS-1b: confirm the {@code @Schema}
 * package + FunctionTool.create signature against ADK 1.7.0.
 */
public final class TriageMateTools {

    private static ServiceNowGateway serviceNow;
    private static ConfluenceGateway confluence;
    private static SumoGateway sumo;
    private static GitLabGateway gitLab;
    private static List<String> sumoAllowlist = List.of("prod/payment", "prod/order-api");

    private TriageMateTools() {}

    static void wire(ServiceNowGateway sn, ConfluenceGateway cf, SumoGateway su,
                     GitLabGateway gl, List<String> allowlist) {
        serviceNow = sn; confluence = cf; sumo = su; gitLab = gl; sumoAllowlist = allowlist;
    }

    @Schema(name = "get_incident",
            description = "Fetch a ServiceNow incident's full context by number.")
    public static IncidentContext getIncident(
            @Schema(name = "incidentNumber") String incidentNumber) {
        return serviceNow.getIncident(incidentNumber);
    }

    @Schema(name = "find_similar_incidents",
            description = "Find previously resolved incidents with similar symptoms and their resolution groups.")
    public static List<ResolvedIncident> findSimilarIncidents(
            @Schema(name = "incidentNumber") String incidentNumber) {
        return serviceNow.findSimilarIncidents(serviceNow.getIncident(incidentNumber));
    }

    @Schema(name = "find_ownership",
            description = "Look up CMDB support-group ownership for an application/service name.")
    public static Map<String, Object> findOwnership(
            @Schema(name = "applicationName") String applicationName) {
        return serviceNow.findOwnership(applicationName)
                .map(o -> Map.<String, Object>of("supportGroup", o.supportGroup(),
                        "businessService", o.businessService(), "source", o.source()))
                .orElse(Map.of("supportGroup", "unknown"));
    }

    @Schema(name = "search_confluence",
            description = "Search enterprise knowledge (runbooks, known errors) by keywords.")
    public static List<KnowledgeDoc> searchConfluence(
            @Schema(name = "query") String query) {
        return confluence.search(query);
    }

    @Schema(name = "search_logs",
            description = "Run ONE bounded Sumo Logic search. scope must be an allowlisted _sourceCategory; "
                    + "window is fixed by the caller; results are capped. Do not attempt broad queries.")
    public static List<LogEvidence> searchLogs(
            @Schema(name = "scope") String scope,
            @Schema(name = "query") String query,
            @Schema(name = "fromIso") String fromIso,
            @Schema(name = "toIso") String toIso) {
        if (!sumoAllowlist.contains(scope)) {
            throw new IllegalArgumentException("scope not allowlisted: " + scope);   // J8 guardrail
        }
        return sumo.search(new LogSearchRequest(scope, query,
                OffsetDateTime.parse(fromIso), OffsetDateTime.parse(toIso), 20));
    }

    @Schema(name = "search_code",
            description = "Targeted code search in one allowlisted GitLab project for a concrete term "
                    + "(e.g. an error token). Returns matching file:line — used to tie a log line to its source.")
    public static List<CodeSearchResult> searchCode(
            @Schema(name = "project") String project,
            @Schema(name = "searchTerm") String searchTerm) {
        return gitLab.searchCode(project, searchTerm);
    }

    @Schema(name = "find_page_contributors",
            description = "Who to talk to about a Confluence page the triage already cited: its author "
                    + "and last editor(s). Call only for pages returned by search_confluence.")
    public static List<Contact> findPageContributors(
            @Schema(name = "pageId") String pageId,
            @Schema(name = "title") String title,
            @Schema(name = "url") String url) {
        return confluence.contributors(new KnowledgeDoc(pageId, title, url, ""));
    }

    @Schema(name = "find_recent_committers",
            description = "Who to talk to about the implicated source: recent committers to one file "
                    + "since the last release. Call only for a file already tied via search_code.")
    public static List<Contact> findRecentCommitters(
            @Schema(name = "project") String project,
            @Schema(name = "filePath") String filePath) {
        return gitLab.recentCommitters(project, filePath);
    }
}
