package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Real Sumo Logic connector (JS-2) via the Search Job API: create job → poll until
 * DONE GATHERING RESULTS → fetch messages. {@code @ConditionalOnProperty(name = "triage.connectors.sumo", havingValue = "real")}. Auth: Basic with
 * accessId:accessKey; the base URL must be the correct regional endpoint
 * (e.g. https://api.au.sumologic.com). Query is always bounded (scope + window + cap).
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.sumo", havingValue = "real")
public class RealSumoGateway implements SumoGateway {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RealSumoGateway.class);

    private static final int MAX_POLLS = 15;
    private final RestClient http;

    public RealSumoGateway(IntegrationProperties props) {
        var su = props.sumo();
        String basic = Base64.getEncoder()
                .encodeToString((su.user() + ":" + su.secret()).getBytes());
        this.http = RestClient.builder()
                .baseUrl(su.baseUrl())
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public List<LogEvidence> search(LogSearchRequest req) {
        log.info("[Sumo] searching scope={} for \"{}\" between {} and {}",
                req.sourceCategory(), req.query(), req.fromTime(), req.toTime());
        String query = req.toSumoQuery();
        log.debug("[Sumo] query: {}", query);
        // Sumo's Search Job API rejects OffsetDateTime.toString() outright:
        //   "The 'from' field (2026-08-03T14:16:40.644281692+10:00) contains an invalid time."
        // It wants a plain second-precision local timestamp paired with the timeZone field
        // below — no sub-second fraction, no offset suffix. Caught by
        // RealSumoGatewayLiveTest; a stubbed gateway can't see this, and every real call
        // was 400-ing before the fix.
        String body = """
            {"query":%s,"from":"%s","to":"%s","timeZone":"UTC"}
            """.formatted(json(query), sumoTime(req.fromTime()), sumoTime(req.toTime()));

        JsonNode created = http.post().uri("/api/v1/search/jobs")
                .body(body).retrieve().body(JsonNode.class);
        String jobId = created == null ? null : created.path("id").asText(null);
        if (jobId == null) return List.of();

        try {
            // Measured against the real AU instance: a 30-minute window over one project
            // completes in ~4s (4 polls). A 24-hour window was still gathering at 24s — but
            // the app never asks for one (max-window-minutes caps it at 30). If the budget
            // IS exhausted we still fetch what the job has gathered so far rather than
            // returning nothing, but say so, because partial results that look complete are
            // the kind of thing that quietly misleads a diagnosis.
            boolean complete = false;
            for (int i = 0; i < MAX_POLLS; i++) {
                JsonNode status = http.get().uri("/api/v1/search/jobs/{id}", jobId)
                        .retrieve().body(JsonNode.class);
                String state = status == null ? "" : status.path("state").asText();
                if ("DONE GATHERING RESULTS".equals(state)) { complete = true; break; }
                if ("CANCELLED".equals(state)) return List.of();
                Thread.sleep(1000);
            }
            if (!complete) {
                log.warn("[Sumo] search job {} still gathering after {}s — returning partial results",
                        jobId, MAX_POLLS);
            }
            JsonNode msgs = http.get()
                    .uri("/api/v1/search/jobs/{id}/messages?offset=0&limit={n}", jobId, req.maxResults())
                    .retrieve().body(JsonNode.class);
            List<LogEvidence> out = new ArrayList<>();
            JsonNode arr = msgs == null ? null : msgs.get("messages");
            if (arr != null) arr.forEach(m -> {
                JsonNode f = m.path("map");
                String raw = f.path("_raw").asText("");
                out.add(new LogEvidence(
                        f.path("_messagetime").asText(),
                        level(f, raw),
                        f.path("_sourcecategory").asText(""),
                        raw));
            });
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            try { http.delete().uri("/api/v1/search/jobs/{id}", jobId).retrieve().toBodilessEntity(); }
            catch (Exception ignore) { /* best-effort cleanup */ }
        }
    }

    /**
     * The log level of one row — {@code "ERROR"}, {@code "WARN"}, … or {@code ""}.
     *
     * <p><b>FND-85.</b> This read {@code map.loglevel}. The field Sumo actually returns is
     * {@code map._loglevel}, <b>with a leading underscore</b> (verified against the live AU
     * instance 2026-08-05: a real row carries {@code _loglevel = ERROR}). The misspelled key
     * silently yielded {@code ""} on every row ever returned, and nothing downstream could
     * tell that apart from "this log line genuinely has no level".
     *
     * <p>That single character disabled a whole limb of the deterministic engine.
     * {@code DeterministicDiagnosisEngine:262} selects the error line with
     * {@code "ERROR".equals(l.level())}, which could never be true, so {@code errorLine} was
     * always null → {@code errorToken} always null → the {@code if (errorToken != null)} guard
     * at {@code :298} never opened → <b>the GitLab code search never ran against real data</b>,
     * and no log↔code citation was ever produced. The trace reported this as the innocuous
     * {@code errorToken=null} while the Sumo search itself was working perfectly and returning
     * rows, which is why it read as "no errors today" rather than as a defect.
     *
     * <p>Falls back to parsing the level out of {@code _raw} when the field is absent: the
     * field comes from a Sumo field-extraction rule, so a source without that rule configured
     * would otherwise reopen exactly this hole. The raw line is Spring Boot's default layout,
     * {@code 2026-08-05 14:26:46.173 ERROR 1 --- [thread] logger : message} — the level is the
     * first standalone level word in it.
     */
    static String level(JsonNode fields, String raw) {
        String declared = fields.path("_loglevel").asText("");
        if (!declared.isBlank()) return declared.trim().toUpperCase(java.util.Locale.ROOT);
        if (raw == null || raw.isBlank()) return "";
        java.util.regex.Matcher m = RAW_LEVEL.matcher(raw);
        return m.find() ? m.group(1) : "";
    }

    /** Standalone level word, as emitted by Spring Boot / Logback default layouts. */
    private static final java.util.regex.Pattern RAW_LEVEL =
            java.util.regex.Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b");

    /**
     * The timestamp format Sumo's Search Job API accepts: UTC, second precision, no offset
     * suffix (the request body carries {@code "timeZone":"UTC"} instead). Package-private
     * so {@link RealSumoGatewayTimeFormatTest} can pin it without a network call.
     */
    static String sumoTime(java.time.OffsetDateTime t) {
        return t.atZoneSameInstant(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
