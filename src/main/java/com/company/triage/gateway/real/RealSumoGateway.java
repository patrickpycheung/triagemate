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
                        // J29/LLF-1: this estate never sets loglevel — fall back to _raw.
                        parseLevel(f.path("loglevel").asText(""), f.path("_raw").asText("")),
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
     * Spring Boot's default console layout, up to the level. {@code %5p} RIGHT-ALIGNS the level
     * to five characters, so four-character levels (WARN, INFO) carry TWO spaces before them —
     * hence {@code \s+}, not the single literal space the field report proposed, which hits
     * ERROR and silently misses everything shorter (J29, correction to the field report).
     */
    private static final java.util.regex.Pattern RAW_LEVEL = java.util.regex.Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\b");

    /**
     * J29/LLF-1: resolve a log level, preferring the structured Sumo field and falling back to
     * scanning {@code _raw}.
     *
     * <p>Verified live 2026-08-05: this estate's responses carry no {@code loglevel} key at all,
     * so every row used to arrive at {@code ""}, the engine's {@code "ERROR".equals(level)} filter
     * matched nothing, and both GitLab steps skipped on real data.
     *
     * <p>The structured field keeps priority: an estate that DOES configure a field-extraction
     * rule is better served by its own parsed value than by our regex, and must not be regressed
     * to a guess. {@code ""} means "unreadable" (LLF-2) — never null, never an assumed severity.
     *
     * <p>Package-private static so {@link RealSumoGatewayLevelParseTest} can pin it against
     * captured real rows with no HTTP — the response-side contract test J22 never built.
     */
    static String parseLevel(String structured, String raw) {
        if (structured != null && !structured.isBlank()) return structured;
        if (raw == null) return "";
        java.util.regex.Matcher m = RAW_LEVEL.matcher(raw);
        return m.find() ? m.group(1) : "";
    }

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
