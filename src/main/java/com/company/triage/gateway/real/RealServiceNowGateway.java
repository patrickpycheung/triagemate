package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriageProperties;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.NewIncident;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public RealServiceNowGateway(RestClient.Builder builder, IntegrationProperties integrationProps,
                                 TriageProperties props) {
        var sn = integrationProps.servicenow();
        String basic = Base64.getEncoder()
                .encodeToString((sn.user() + ":" + sn.secret()).getBytes());
        // Injected RestClient.Builder (Spring Boot autoconfigures a fresh prototype per
        // injection point) rather than RestClient.builder() directly, so tests can bind a
        // MockRestServiceServer to it (see RealServiceNowGatewayTest) — FND-14 needed a
        // real regression test against actual HTTP request/response shapes, not just a
        // unit test of an extracted predicate.
        this.http = builder
                .baseUrl(sn.baseUrl())
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json")
                .build();
        // FND-51/FND-57: writeField used to be checked here with a manual constructor throw,
        // which only ran when THIS bean was constructed — i.e. never under the default mock
        // connector config. It's now a @Pattern on TriageProperties, validated unconditionally
        // at boot regardless of connector mode, so a bad value fails before serving traffic
        // rather than the first time `triage.connectors.servicenow=real` is set on stage.
        this.writeField = props.servicenow().writeField();
    }

    @Override
    public IncidentContext getIncident(String number) {
        // FND-47: u_environment was read below but never requested here — ServiceNow
        // returns only requested fields, so IncidentContext.environment was always null
        // against a real instance. Mock-only testing hid this completely.
        JsonNode row = firstRow("/api/now/table/incident",
                "number=" + number, "sys_id,number,short_description,description,caller_id,"
                        + "category,subcategory,opened_at,cmdb_ci,assignment_group,u_environment");
        // FND-53: a dedicated type, not a bare IllegalStateException — see
        // IncidentNotFoundException's javadoc for why the old mapping was unsafe.
        if (row == null) throw new com.company.triage.gateway.IncidentNotFoundException(number);
        // FND-61: comments and workNotes were hardcoded empty here while
        // MockServiceNowGateway populated them — so the demo showed the agent reasoning over
        // the caller's follow-ups ("it worked yesterday, now some checkouts error out": the
        // timing/scope detail the description omits) and a real instance silently dropped
        // exactly that signal. Same mock-only-testing blind spot as FND-47's u_environment.
        // Journal entries live in sys_journal_field, not on the incident row, so they need
        // their own query — the same table alreadyPosted() already reads for idempotency.
        String sysId = text(row, "sys_id");
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
                journal(sysId, "comments"),
                journal(sysId, "work_notes"),
                text(row, "cmdb_ci"),
                List.of());   // reassignmentHistory: needs sys_audit; not wired (see J5)
    }

    /**
     * FND-61: one incident's journal entries for a field, oldest first (reading order).
     * Best-effort — the triage is still useful without the conversation, so a journal
     * failure degrades to "no comments" rather than failing the whole diagnosis.
     */
    private List<String> journal(String sysId, String element) {
        if (sysId == null || sysId.isBlank()) return List.of();
        try {
            JsonNode entries = rows("/api/now/table/sys_journal_field",
                    "element_id=" + sysId + "^element=" + element + "^ORDERBYsys_created_on",
                    "value,sys_created_by,sys_created_on");
            if (entries == null) return List.of();
            List<String> out = new ArrayList<>();
            entries.forEach(e -> {
                String value = text(e, "value");
                if (value == null || value.isBlank()) return;
                String who = text(e, "sys_created_by");
                out.add(who == null ? value : who + ": " + value);
            });
            return out;
        } catch (Exception e) {
            log.warn("could not read {} journal for {} — continuing without it", element, sysId, e);
            return List.of();
        }
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

    /**
     * Appends the note to the incident's work_notes (or comments) journal. Advisory only.
     *
     * <p><b>Idempotent (FND-14).</b> {@code MockServiceNowGateway} always skipped an
     * identical AI note; this connector previously PATCHed unconditionally — a real
     * safety layer that J5/J10 both document and only actually existed against the mock.
     * A retried request (a flaky proxy, a manual re-trigger, K1 re-selecting an incident
     * after a cursor edge case) posted duplicate advisory comments onto a real,
     * customer-visible ticket. Checked against actual history for that field via
     * {@code sys_journal_field} — exact match only, same semantics as the mock.
     */
    @Override
    public void addWorkNote(String number, String workNote) {
        JsonNode row = firstRow("/api/now/table/incident", "number=" + number, "sys_id");
        if (row == null) { log.warn("cannot post note; incident {} not found", number); return; }
        String sysId = text(row, "sys_id");

        if (alreadyPosted(sysId, workNote)) {
            log.info("[ServiceNow] identical AI work note already present on {} — skipping", number);
            return;
        }

        http.patch()
                .uri("/api/now/table/incident/{sysId}", sysId)
                .header("Content-Type", "application/json")
                .body("{\"" + writeField + "\":" + jsonString(workNote) + "}")
                .retrieve().toBodilessEntity();
        log.info("posted advisory {} to {}", writeField, number);
    }

    /** Recent journal entries for this field, exact-string-compared against {@code workNote}. */
    private boolean alreadyPosted(String sysId, String workNote) {
        JsonNode entries = rows("/api/now/table/sys_journal_field",
                "element_id=" + sysId + "^element=" + writeField + "^ORDERBYDESCsys_created_on",
                "value");
        if (entries == null) return false;
        for (JsonNode entry : entries) {
            if (workNote.equals(text(entry, "value"))) return true;
        }
        return false;
    }

    /**
     * K1 poller feed. Queries {@code sys_created_on > since}, oldest first.
     *
     * <p>Deliberately <b>created</b>, not <b>updated</b> (FND-1): {@code addWorkNote} bumps
     * {@code sys_updated_on}, so an updated-since query would re-select every incident this
     * app comments on and re-run its diagnosis forever. {@code sys_created_on} is immutable.
     *
     * <p>ServiceNow compares dates in the <b>instance's UTC</b> representation, so the
     * cursor is converted to UTC and formatted {@code yyyy-MM-dd HH:mm:ss}.
     */
    @Override
    public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
        if (limit <= 0) return List.of();
        String utc = since.atZoneSameInstant(java.time.ZoneOffset.UTC).format(SNOW_DATETIME);
        String query = "sys_created_on>" + utc + "^ORDERBYsys_created_on";

        JsonNode resp = http.get()
                .uri(uri -> uri.path("/api/now/table/incident")
                        .queryParam("sysparm_query", query)
                        .queryParam("sysparm_fields", "number,sys_created_on")
                        // Raw values, NOT display values: sys_created_on must come back in
                        // the parseable UTC form, not the instance's user-facing date format.
                        .queryParam("sysparm_display_value", "false")
                        .queryParam("sysparm_limit", limit).build())
                .retrieve().body(JsonNode.class);
        JsonNode result = resp == null ? null : resp.get("result");
        if (result == null || !result.isArray()) return List.of();

        List<NewIncident> found = new ArrayList<>();
        result.forEach(row -> {
            String n = text(row, "number");
            if (n == null || n.isBlank()) return;
            OffsetDateTime createdAt = parseSnowDateTime(text(row, "sys_created_on"));
            // A row we can't date is worse than useless for a high-water-mark cursor:
            // including it with a guessed timestamp risks skipping real incidents.
            if (createdAt == null) {
                log.warn("poll: skipping {} — unparseable sys_created_on {}", n, text(row, "sys_created_on"));
                return;
            }
            found.add(new NewIncident(n, createdAt));
        });
        log.debug("poll: {} incident(s) created after {}", found.size(), utc);
        return found;
    }

    private static final java.time.format.DateTimeFormatter SNOW_DATETIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** ServiceNow returns raw datetimes as {@code yyyy-MM-dd HH:mm:ss} in UTC. */
    private static OffsetDateTime parseSnowDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(raw.trim(), SNOW_DATETIME).atOffset(java.time.ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
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

    private static final ObjectMapper JSON = new ObjectMapper();

    // FND-51: the hand-rolled escaping here only covered \, ", \n — a \r or tab in an
    // evidence summary (plausible: pasted log text) produced invalid JSON on a real PATCH.
    // Jackson is already a transitive dependency; use it instead of re-deriving the
    // escaping rules.
    private static String jsonString(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize work note text", e);
        }
    }
}
