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
        String body = """
            {"query":%s,"from":"%s","to":"%s","timeZone":"UTC"}
            """.formatted(json(query), req.fromTime(), req.toTime());

        JsonNode created = http.post().uri("/api/v1/search/jobs")
                .body(body).retrieve().body(JsonNode.class);
        String jobId = created == null ? null : created.path("id").asText(null);
        if (jobId == null) return List.of();

        try {
            for (int i = 0; i < MAX_POLLS; i++) {
                JsonNode status = http.get().uri("/api/v1/search/jobs/{id}", jobId)
                        .retrieve().body(JsonNode.class);
                String state = status == null ? "" : status.path("state").asText();
                if ("DONE GATHERING RESULTS".equals(state)) break;
                if ("CANCELLED".equals(state)) return List.of();
                Thread.sleep(1000);
            }
            JsonNode msgs = http.get()
                    .uri("/api/v1/search/jobs/{id}/messages?offset=0&limit={n}", jobId, req.maxResults())
                    .retrieve().body(JsonNode.class);
            List<LogEvidence> out = new ArrayList<>();
            JsonNode arr = msgs == null ? null : msgs.get("messages");
            if (arr != null) arr.forEach(m -> {
                JsonNode f = m.path("map");
                out.add(new LogEvidence(
                        f.path("_messagetime").asText(),
                        f.path("loglevel").asText(""),
                        f.path("_sourcecategory").asText(""),
                        f.path("_raw").asText("")));
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

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
