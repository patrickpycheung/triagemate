package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Real ServiceNow connector via the REST Table API. Active when
 * {@code triage.connectors.servicenow=real} (others can stay {@code mock}), with
 * {@code triage.integrations.servicenow.*} set. This is the piece that makes the demo
 * write its two advisory comments onto a <b>real</b> dev-instance ticket.
 *
 * <p>Auth: Basic (least-privilege service account with read + write on incident).
 * Reads use {@code sysparm_display_value=true} so reference fields (assignment group,
 * CI, caller) come back as readable names. The write appends to the incident's
 * {@code work_notes} journal by default — switch to {@code comments} (customer-facing)
 * with {@code triage.servicenow.write-field=comments}.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.servicenow", havingValue = "real")
public class RealServiceNowGateway implements ServiceNowGateway {

    private static final Logger log = LoggerFactory.getLogger(RealServiceNowGateway.class);

    private final RestClient http;
    private final String writeField;   // "work_notes" (internal) or "comments" (customer-facing)

    public RealServiceNowGateway(IntegrationProperties props,
                                 @Value("${triage.servicenow.write-field:work_notes}") String writeField) {
        var sn = props.servicenow();
        String basic = Base64.getEncoder()
                .encodeToString((sn.user() + ":" + sn.secret()).getBytes());
        this.http = RestClient.builder()
                .baseUrl(sn.baseUrl())
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json")
                .build();
        this.writeField = writeField;
    }

    @Override
    public IncidentContext getIncident(String number) {
        JsonNode row = firstRow("/api/now/table/incident",
                "number=" + number, "sys_id,number,short_description,description,caller_id,"
                        + "category,subcategory,opened_at,cmdb_ci,assignment_group");
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
                List.of(),
                List.of(),
                text(row, "cmdb_ci"),
                List.of());
    }

    @Override
    public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
        // Naive keyword match on short_description of resolved/closed incidents.
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
        if (applicationName == null || applicationName.isBlank()) return Optional.empty();
        JsonNode row = firstRow("/api/now/table/cmdb_ci_service",
                "nameLIKE" + applicationName, "name,support_group,business_criticality");
        if (row == null) return Optional.empty();
        return Optional.of(new ServiceOwnership(text(row, "name"),
                text(row, "support_group"), text(row, "business_criticality"), "cmdb_ci_service"));
    }

    /** Appends the note to the incident's work_notes (or comments) journal. Advisory only. */
    @Override
    public void addWorkNote(String number, String workNote) {
        JsonNode row = firstRow("/api/now/table/incident", "number=" + number, "sys_id");
        if (row == null) { log.warn("cannot post note; incident {} not found", number); return; }
        String sysId = text(row, "sys_id");
        http.patch()
                .uri("/api/now/table/incident/{sysId}", sysId)
                .header("Content-Type", "application/json")
                .body("{\"" + writeField + "\":" + jsonString(workNote) + "}")
                .retrieve().toBodilessEntity();
        log.info("posted advisory {} to {}", writeField, number);
    }

    // --- helpers ----------------------------------------------------------------
    private JsonNode rows(String path, String query, String fields) {
        JsonNode resp = http.get()
                .uri(uri -> uri.path(path)
                        .queryParam("sysparm_query", query)
                        .queryParam("sysparm_fields", fields)
                        .queryParam("sysparm_display_value", "true")   // readable names, not sys_ids
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
