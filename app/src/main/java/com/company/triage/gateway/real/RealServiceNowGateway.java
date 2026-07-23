package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Real ServiceNow connector (JS-2) via the REST Table API. {@code @Profile("real")}
 * so it replaces {@code MockServiceNowGateway} only when the app runs with the
 * {@code real} profile and {@code triage.integrations.servicenow.*} is set.
 *
 * <p>Auth: Basic (least-privilege service account). This is a working skeleton —
 * JS-2 tasks are marked TODO (fuller field mapping, similar-incident query tuning).
 */
@Component
@Profile("real")
public class RealServiceNowGateway implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(RealServiceNowGateway.class);
    private final RestClient http;

    public RealServiceNowGateway(IntegrationProperties props) {
        var sn = props.servicenow();
        String basic = Base64.getEncoder()
                .encodeToString((sn.user() + ":" + sn.secret()).getBytes());
        this.http = RestClient.builder()
                .baseUrl(sn.baseUrl())
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public IncidentContext getIncident(String number) {
        JsonNode row = firstRow("/api/now/table/incident",
                "number=" + number, "sys_id,number,short_description,description,"
                        + "category,subcategory,opened_at,cmdb_ci,assignment_group,comments_and_work_notes");
        if (row == null) throw new IllegalStateException("incident not found: " + number);
        return new IncidentContext(
                text(row, "number"),
                text(row, "short_description"),
                text(row, "description"),
                text(row, "caller_id"),
                text(row, "category"),
                text(row, "subcategory"),
                parseTime(text(row, "opened_at")),
                text(row, "u_environment"),
                text(row, "assignment_group"),
                List.of(),                 // TODO JS-2: split comments_and_work_notes journal
                List.of(),
                text(row, "cmdb_ci"),
                List.of());                // TODO JS-2: reassignment history from sys_journal_field
    }

    @Override
    public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
        // Naive keyword match on short_description of resolved incidents. TODO JS-2:
        // use text search / similarity; scope by CI or business service.
        String kw = firstKeyword(incident.shortDescription());
        JsonNode body = rows("/api/now/table/incident",
                "stateIN6,7^short_descriptionLIKE" + kw,
                "number,short_description,assignment_group,close_code,close_notes");
        List<ResolvedIncident> out = new ArrayList<>();
        if (body != null) body.forEach(r -> out.add(new ResolvedIncident(
                text(r, "number"), text(r, "short_description"),
                text(r, "assignment_group"), text(r, "close_code"),
                text(r, "close_notes"), 0.5)));
        return out;
    }

    @Override
    public Optional<ServiceOwnership> findOwnership(String applicationName) {
        JsonNode row = firstRow("/api/now/table/cmdb_ci_service",
                "nameLIKE" + applicationName, "name,support_group,business_criticality");
        if (row == null) return Optional.empty();
        return Optional.of(new ServiceOwnership(text(row, "name"),
                text(row, "support_group"), text(row, "business_criticality"), "cmdb_ci_service"));
    }

    @Override
    public void addWorkNote(String number, String workNote) {
        JsonNode row = firstRow("/api/now/table/incident", "number=" + number, "sys_id");
        if (row == null) { log.warn("cannot post work note; incident {} not found", number); return; }
        String sysId = text(row, "sys_id");
        http.patch()
                .uri("/api/now/table/incident/{sysId}", sysId)
                .header("Content-Type", "application/json")
                .body("{\"work_notes\":" + jsonString(workNote) + "}")
                .retrieve().toBodilessEntity();
        log.info("posted advisory work note to {}", number);
    }

    // --- helpers ----------------------------------------------------------------
    private JsonNode rows(String path, String query, String fields) {
        JsonNode resp = http.get()
                .uri(uri -> uri.path(path)
                        .queryParam("sysparm_query", query)
                        .queryParam("sysparm_fields", fields)
                        .queryParam("sysparm_limit", 10).build())
                .retrieve().body(JsonNode.class);
        return resp == null ? null : resp.get("result");
    }

    private JsonNode firstRow(String path, String query, String fields) {
        JsonNode result = rows(path, query, fields);
        return (result != null && result.isArray() && !result.isEmpty()) ? result.get(0) : null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstKeyword(String s) {
        return s == null || s.isBlank() ? "error" : s.split("\\s+")[0];
    }

    private static OffsetDateTime parseTime(String s) {
        try { return s == null ? null : OffsetDateTime.parse(s.replace(' ', 'T') + "+00:00"); }
        catch (Exception e) { return null; }
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
