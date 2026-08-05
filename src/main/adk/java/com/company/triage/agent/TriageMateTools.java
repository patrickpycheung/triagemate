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
    private static com.company.triage.config.TriageProperties.Sumo sumoProps;
    // FND-20: these two were previously not real bounds — max-results was a hardcoded
    // 20 (triage.sumo.max-results was declared in application.yml and never read), and
    // the time window was taken verbatim from the model with no span check at all. A
    // bound the model can set is not a bound.
    private static int sumoMaxResults = 20;
    private static int sumoMaxWindowMinutes = 30;
    // J8 claimed "allowlisted GitLab projects" while search_code accepted any model-
    // supplied project string unchecked — same class of gap as FND-20, fixed the same
    // way: enforced here, not just documented.
    private static List<String> gitLabAllowlist = List.of("order-payments/payment-service");

    /**
     * The incident this run is diagnosing (FND-33). Bound once per run by
     * {@link AdkDiagnosisEngine#diagnose} before the agent starts, not supplied by the
     * model: {@code get_incident}/{@code find_similar_incidents} previously took a
     * free-form {@code incidentNumber} argument like any other tool param, so nothing
     * stopped the model from fetching or (via the report it produces) effectively
     * diagnosing a DIFFERENT incident than the one it was actually asked about — an
     * identity-binding gap, not a data-access one (the allowlists above bound WHICH
     * systems/scopes are reachable; nothing bound WHICH incident). A {@code ThreadLocal}
     * is safe here for the same reason the static gateway fields above are: ADK tool
     * methods are static, and one diagnosis run owns the thread it executes on.
     */
    private static final ThreadLocal<String> CURRENT_INCIDENT = new ThreadLocal<>();

    private TriageMateTools() {}

    static void wire(ServiceNowGateway sn, ConfluenceGateway cf, SumoGateway su,
                     GitLabGateway gl, com.company.triage.config.TriageProperties.Sumo sumoConfig,
                     List<String> gitLabProjectAllowlist) {
        serviceNow = sn; confluence = cf; sumo = su; gitLab = gl; sumoProps = sumoConfig;
        sumoMaxResults = sumoConfig.maxResults(); sumoMaxWindowMinutes = sumoConfig.maxWindowMinutes();
        gitLabAllowlist = gitLabProjectAllowlist;
    }

    /** Pins the incident for this run (FND-33). Call before the agent starts. */
    static void bindIncident(String incidentNumber) {
        CURRENT_INCIDENT.set(incidentNumber);
    }

    /** Releases the binding at the end of a run so a thread-pool reuse can't leak it. */
    static void clearIncident() {
        CURRENT_INCIDENT.remove();
    }

    @Schema(name = "get_incident",
            description = "Fetch the full context of the incident under investigation. Takes no arguments "
                    + "— it always returns the one incident this run is diagnosing.")
    public static IncidentContext getIncident() {
        return serviceNow.getIncident(CURRENT_INCIDENT.get());
    }

    @Schema(name = "find_similar_incidents",
            description = "Find previously resolved incidents similar to the incident under investigation, "
                    + "and their resolution groups. Takes no arguments. Each result carries a "
                    + "'similarity' score from 0 to 1: results are already ranked and filtered, so "
                    + "prefer the highest-scoring ones and treat a low score as weak evidence. An "
                    + "empty list means no resolved incident resembled this one — say so; do not "
                    + "infer a routing target from an empty result.")
    public static List<ResolvedIncident> findSimilarIncidents() {
        return serviceNow.findSimilarIncidents(serviceNow.getIncident(CURRENT_INCIDENT.get()));
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
            description = "Run ONE bounded Sumo Logic search. Supply the project slug (the "
                    + "affected application, lowercase and hyphenated, e.g. 'delivery-hazards') "
                    + "and the environment; the app builds the _sourceCategory from them. The "
                    + "time window and result count are capped by the app regardless of what is "
                    + "asked for. Do not attempt broad queries.")
    public static List<LogEvidence> searchLogs(
            @Schema(name = "projectSlug") String projectSlug,
            @Schema(name = "environment") String environment,
            @Schema(name = "query") String query,
            @Schema(name = "fromIso") String fromIso,
            @Schema(name = "toIso") String toIso) {
        // The model no longer supplies a _sourceCategory at all — it supplies two narrow
        // fields and the app composes the category from the configured pattern. That makes
        // an off-convention or wildcard scope unrepresentable rather than merely rejected,
        // which is a stronger bound than the literal allowlist this replaced.
        String slug = projectSlug == null ? "" : projectSlug.trim().toLowerCase(java.util.Locale.ROOT);
        if (!slug.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("projectSlug must be a lowercase hyphenated "   // J8 guardrail
                    + "slug (e.g. 'delivery-hazards'), got: " + projectSlug);
        }
        List<String> allowedEnvs = sumoProps.allowedEnvironments();
        String env = environment == null ? "" : environment.trim().toLowerCase(java.util.Locale.ROOT);
        if (!allowedEnvs.contains(env)) {
            // FND-60: name the valid values in the error too. The instruction already lists
            // them, but if the model still gets it wrong this lets it self-correct within its
            // remaining budget instead of re-guessing blind.
            throw new IllegalArgumentException("environment not allowed: " + environment   // J8 guardrail
                    + " — must be exactly one of: " + String.join(", ", allowedEnvs));
        }
        String scope = sumoProps.sourceCategoryFor(slug, env);
        OffsetDateTime from = OffsetDateTime.parse(fromIso);
        OffsetDateTime to = OffsetDateTime.parse(toIso);
        if (from.isAfter(to)) {
            OffsetDateTime tmp = from; from = to; to = tmp;   // defensive; a model-supplied pair could be reversed
        }
        // FND-20: bound the window server-side. The model may ask for anything; the app
        // clamps to at most sumoMaxWindowMinutes, anchored on the requested END so a
        // too-wide request still searches the most recent relevant slice rather than
        // silently returning nothing.
        OffsetDateTime earliestAllowed = to.minusMinutes(sumoMaxWindowMinutes);
        if (from.isBefore(earliestAllowed)) {
            from = earliestAllowed;
        }
        return sumo.search(new LogSearchRequest(scope, sumoProps.index(), query, from, to, sumoMaxResults));
    }

    @Schema(name = "search_code",
            description = "Targeted code search in one allowlisted GitLab project for a concrete term "
                    + "(e.g. an error token). Returns matching file:line — used to tie a log line to its source.")
    public static List<CodeSearchResult> searchCode(
            @Schema(name = "project") String project,
            @Schema(name = "searchTerm") String searchTerm) {
        if (!gitLabAllowlist.contains(project)) {
            throw new IllegalArgumentException("project not allowlisted: " + project   // J8 guardrail
                    + " — must be exactly one of: " + String.join(", ", gitLabAllowlist));   // FND-60
        }
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
