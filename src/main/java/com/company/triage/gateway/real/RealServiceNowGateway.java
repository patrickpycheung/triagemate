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
import java.util.Set;

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
    private final TriageProperties properties;   // operator-pinned similar incidents

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
        this.properties = props;
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

    /** Fields every similar-incident lookup reads, pinned or searched. */
    private static final String SIMILAR_FIELDS =
            "number,short_description,assignment_group,close_code,close_notes,state";

    /**
     * Similar incidents for the SAME application, ranked by description overlap.
     *
     * <p>Replaces a keyword search that could not work by construction. It took
     * {@code short_description.split(" ")[0]} — the FIRST WORD — so the demo's "Delivery
     * Hazards" incident searched for {@code "Delivery"} and its twin, whose subject starts
     * "Hazards being recorded…", searched for {@code "Hazards"}. Splitting an application
     * name in half means neither query describes the thing being searched for. Verified
     * against the live instance: both returned 0 rows.
     *
     * <p>Scoping is now by {@code cmdb_ci} — "similar incident" means "same application,
     * similar symptoms", and the CI is what states the application. That also drops the
     * {@code stateIN6,7} (resolved/closed) filter: it returned 0 here because none of the
     * instance's Delivery Hazards tickets are closed, and a young estate ALWAYS looks like
     * that. Resolved incidents still win — {@link #rank} sorts them first, because a ticket
     * with a close code is the one that tells the triager what to do.
     */
    @Override
    public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
        List<ResolvedIncident> pinned = pinnedSimilar(incident.number());

        String application = incident.configurationItem();
        if (application == null || application.isBlank()) {
            // No CI to scope by. Nothing reliable to search on — a first-word search is what
            // this method is being fixed to stop doing — so answer with pins alone.
            log.info("[ServiceNow] {} has no CI; similar-incident search needs one, returning {} pin(s)",
                    incident.number(), pinned.size());
            return pinned;
        }

        JsonNode body = rows("/api/now/table/incident",
                "cmdb_ci.name=" + application + "^number!=" + incident.number()
                        + "^ORDERBYDESCsys_created_on",
                SIMILAR_FIELDS);

        List<ResolvedIncident> found = new ArrayList<>();
        Set<String> already = new java.util.HashSet<>();
        pinned.forEach(pin -> already.add(pin.number()));
        if (body != null) body.forEach(r -> {
            String number = text(r, "number");
            if (number == null || number.isBlank() || !already.add(number)) return;  // pins win
            found.add(toResolvedIncident(r, similarity(incident.shortDescription(), text(r, "short_description"))));
        });

        found.sort(RANK);
        // Pins first, then search hits best-first.
        List<ResolvedIncident> out = new ArrayList<>(pinned);
        out.addAll(found);
        return out;
    }

    /**
     * Operator-pinned duplicates, fetched by number so they carry real subjects and close
     * notes rather than a bare id. A pin that no longer exists is dropped with a warning —
     * a stale config entry must not fabricate an incident that the triager cannot open.
     */
    private List<ResolvedIncident> pinnedSimilar(String number) {
        List<String> pins = properties.servicenow().pinsFor(number);
        List<ResolvedIncident> out = new ArrayList<>();
        for (String pin : pins) {
            JsonNode row = firstRow("/api/now/table/incident", "number=" + pin, SIMILAR_FIELDS);
            if (row == null) {
                log.warn("[ServiceNow] pinned similar incident {} for {} does not exist — skipping",
                        pin, number);
                continue;
            }
            // 1.0: a human asserted this pairing. Nothing the search finds outranks that.
            out.add(toResolvedIncident(row, 1.0));
        }
        return out;
    }

    private ResolvedIncident toResolvedIncident(JsonNode r, double similarity) {
        return new ResolvedIncident(text(r, "number"), text(r, "short_description"),
                text(r, "assignment_group"), text(r, "close_code"), text(r, "close_notes"),
                similarity);
    }

    /** Resolved-first (a close code is actionable), then by descending similarity. */
    private static final java.util.Comparator<ResolvedIncident> RANK =
            java.util.Comparator
                    .comparing((ResolvedIncident r) ->
                            r.resolutionCode() != null && !r.resolutionCode().isBlank() ? 0 : 1)
                    .thenComparing(java.util.Comparator.comparingDouble(ResolvedIncident::similarity).reversed());

    /**
     * Jaccard overlap of the two subjects' significant words, 0..1.
     *
     * <p>Deliberately dumb and local: it only ever re-orders incidents already scoped to the
     * same application, so it cannot invent a match across applications the way a fuzzy text
     * search can. The demo's twin subjects share "hazards/recorded/handheld/appearing" and
     * score far above the unrelated "all hazards are no longer present" cluster.
     */
    static double similarity(String a, String b) {
        Set<String> left = significantWords(a);
        Set<String> right = significantWords(b);
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        Set<String> shared = new java.util.HashSet<>(left);
        shared.retainAll(right);
        Set<String> union = new java.util.HashSet<>(left);
        union.addAll(right);
        return (double) shared.size() / union.size();
    }

    /** Lowercased words of 3+ chars, minus filler that carries no signal about the fault. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "are", "not", "for", "with", "from", "see", "all", "any", "was", "has",
            "attached", "details", "please", "this", "that", "have", "been", "into", "when");

    private static Set<String> significantWords(String s) {
        if (s == null || s.isBlank()) return Set.of();
        Set<String> out = new java.util.HashSet<>();
        for (String w : s.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    @Override
    public Optional<ServiceOwnership> findOwnership(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) return Optional.empty();
        // Query the BASE cmdb_ci table, not cmdb_ci_service. ServiceNow table inheritance
        // means cmdb_ci returns every CI class; cmdb_ci_service returns only service-class
        // CIs. Applications are NOT service-class — the demo's "Delivery Hazards" is a
        // cmdb_ci_web_application — so the old query missed every application CI and this
        // method could only ever answer for CIs it was never asked about. Verified live:
        // cmdb_ci_service?nameLIKEDelivery Hazards -> 0 rows; cmdb_ci -> the CI.
        JsonNode row = firstRow("/api/now/table/cmdb_ci",
                "nameLIKE" + applicationName, "name,support_group,business_criticality");
        if (row == null) return Optional.empty();

        // A CI with no support_group answers nothing useful. Returning a ServiceOwnership
        // with a blank group is worse than empty: callers (and the ADK agent, which is told
        // this is "the strongest routing signal") read a present record as "ownership found"
        // and would route on a blank. Absent is honest; blank is a false positive.
        String supportGroup = text(row, "support_group");
        if (supportGroup == null || supportGroup.isBlank()) return Optional.empty();

        return Optional.of(new ServiceOwnership(text(row, "name"),
                supportGroup, text(row, "business_criticality"), "cmdb_ci"));
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

    /**
     * One field's value as text — unwrapping a ServiceNow <b>reference field</b> when it
     * arrives as an object rather than a scalar.
     *
     * <p>J24/SFF-1 (field-reported by sajids4, {@code docs/Siyad_Findings.md} §2, live
     * instance 2026-08-05): reference fields — {@code cmdb_ci}, {@code caller_id},
     * {@code assignment_group} — come back as
     * <code>{"display_value": "Delivery Hazards", "link": "…/api/now/table/cmdb_ci/…"}</code>.
     * This method used to be a bare {@code v.asText()}, and {@code asText()} on an
     * {@code ObjectNode} returns the <b>empty string</b> — so the single most load-bearing
     * field in the app, the CI naming the affected system, was blank on every real incident.
     * The live log read {@code getIncident(INC0010010) → CI=, env=null} while the raw row
     * plainly carried {@code "Delivery Hazards"}.
     *
     * <p>Everything downstream then derived the affected app from the ticket's subject line
     * instead ({@code IncidentSignals.java:97}), which is how a Sumo scope came to be built
     * from the sentence fragment {@code hazards-being-recorded-on} and matched nothing.
     *
     * <p><b>This also corrects FND-67's premise.</b> FND-67 recorded "the first real
     * ServiceNow ticket had cmdb_ci = "" (empty, not null)" and hardened the deterministic
     * engine against an empty-named candidate. The observation was real; the diagnosis was
     * not — the CMDB was never empty, this parse dropped the value. FND-67's blank-check is
     * still worth keeping (a CI genuinely can be unset), but it was never the whole story.
     *
     * <p>Returns {@code null} — never {@code ""} — for a missing/null field or an object
     * without a {@code display_value}, so "the ticket does not say" stays distinguishable
     * from "the ticket says something we failed to read". Fixing this at the parse rather
     * than by adding {@code sysparm_exclude_reference_link=true} to each query is deliberate:
     * the boundary owns the rule, so a future caller cannot silently re-open it.
     */
    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isObject()) {
            JsonNode display = v.get("display_value");
            if (display == null || display.isNull()) return null;
            String s = display.asText();
            return s == null || s.isBlank() ? null : s;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }


    /**
     * J14: {@code opened_at} anchors the deterministic engine's ±10m Sumo window, so a null
     * here silently removes the log-search step (and, before J14's guard, threw an NPE
     * inside the fallback engine).
     *
     * <p>The wire format is not one thing. Reads use {@code sysparm_display_value=true},
     * which renders datetimes in the <b>requesting user's</b> display format — the live
     * instance returned the raw-looking {@code 2026-08-02 21:37:16}
     * ({@code docs/Siyad_Findings.md} §2), but a service account with a UK/AU locale profile
     * returns {@code 02/08/2026 21:37:16} instead, which the original single-format parse
     * dropped to null without a word. Try the shapes ServiceNow actually emits, and log at
     * WARN when none match so an unparseable date is visible rather than merely absent.
     */
    private static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        String raw = s.trim();
        // ISO-8601 with an explicit offset, if the instance is configured that way.
        try { return OffsetDateTime.parse(raw); } catch (Exception ignored) { /* try next */ }
        for (java.time.format.DateTimeFormatter fmt : DATETIME_FORMATS) {
            try {
                return java.time.LocalDateTime.parse(raw, fmt).atOffset(java.time.ZoneOffset.UTC);
            } catch (Exception ignored) { /* try next */ }
        }
        log.warn("could not parse ServiceNow datetime '{}' — the log-search window will be skipped", raw);
        return null;
    }

    private static final List<java.time.format.DateTimeFormatter> DATETIME_FORMATS = List.of(
            SNOW_DATETIME,                                                        // 2026-08-02 21:37:16
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),  // AU/UK profile
            java.time.format.DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss"),  // US profile
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

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
