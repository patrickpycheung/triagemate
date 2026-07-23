package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.model.KnowledgeDoc;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Real Confluence connector (JS-2) via CQL search + page REST. {@code @Profile("real")}.
 * Auth: Basic with email + API token (Cloud). Best-effort: on any error the caller
 * degrades (evidence omitted). Alternatively reuse the auspost-mcp Confluence client.
 */
@Component
@Profile("real")
public class RealConfluenceGateway implements ConfluenceGateway {

    private final RestClient http;

    public RealConfluenceGateway(IntegrationProperties props) {
        var cf = props.confluence();
        String basic = Base64.getEncoder()
                .encodeToString((cf.user() + ":" + cf.secret()).getBytes());
        this.http = RestClient.builder()
                .baseUrl(cf.baseUrl())
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public List<KnowledgeDoc> search(String query) {
        try {
            String cql = "text ~ \"" + query.replace("\"", " ") + "\"";
            JsonNode resp = http.get()
                    .uri(uri -> uri.path("/wiki/rest/api/content/search")
                            .queryParam("cql", cql)
                            .queryParam("limit", 5)
                            .queryParam("expand", "body.view,space").build())
                    .retrieve().body(JsonNode.class);
            List<KnowledgeDoc> out = new ArrayList<>();
            JsonNode results = resp == null ? null : resp.get("results");
            if (results != null) results.forEach(r -> out.add(new KnowledgeDoc(
                    r.path("id").asText(),
                    r.path("title").asText(),
                    r.path("_links").path("webui").asText(),
                    strip(r.path("body").path("view").path("value").asText()))));
            return out;
        } catch (Exception e) {
            return List.of();   // best-effort
        }
    }

    private static String strip(String html) {
        String t = html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }
}
