package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.model.Contact;
import com.company.triage.model.KnowledgeDoc;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Real Confluence connector (JS-2) via CQL search + page REST. {@code @ConditionalOnProperty(name = "triage.connectors.confluence", havingValue = "real")}.
 * Auth: Basic with email + API token (Cloud). Best-effort: on any error the caller
 * degrades (evidence omitted). Alternatively reuse the auspost-mcp Confluence client.
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.confluence", havingValue = "real")
public class RealConfluenceGateway implements ConfluenceGateway {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RealConfluenceGateway.class);

    private final RestClient http;

    public RealConfluenceGateway(IntegrationProperties props) {
        var cf = props.confluence();
        String basic = Base64.getEncoder()
                .encodeToString((cf.user() + ":" + cf.secret()).getBytes());
        this.http = RestClient.builder()
                .baseUrl(siteRoot(cf.baseUrl()))
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * FND-83: the request paths below supply the {@code /wiki} context path themselves, so
     * the configured base URL must be the bare site root. The value everyone will actually
     * paste — the one in the browser address bar, and the one Atlassian's own docs show — is
     * {@code https://<site>.atlassian.net/wiki}, which produced
     * {@code /wiki/wiki/rest/api/content/search} and a 404.
     *
     * <p>Field-reported by sajids4 ({@code docs/Siyad_Findings.md} §1, live instance
     * 2026-08-04) after a debugging session, and the failure is maximally unhelpful: the 404
     * body is a full Confluence "Page Not Found" HTML page, so the log shows a wall of markup
     * rather than "your base URL is wrong". Both spellings must work, because both are what
     * people will configure.
     */
    static String siteRoot(String baseUrl) {
        if (baseUrl == null) return null;
        String s = baseUrl.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.regionMatches(true, s.length() - 5, "/wiki", 0, 5)) {
            s = s.substring(0, s.length() - 5);
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    @Override
    public List<KnowledgeDoc> search(String query) {
        log.info("[Confluence] searching pages for: {}", query);
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

    /**
     * Page author + last editor (J9), via the content REST with history/version
     * expanded. Best-effort: any error yields an empty list so the run degrades.
     */
    @Override
    public List<Contact> contributors(KnowledgeDoc doc) {
        log.info("[Confluence] looking up contributors for page: {}",
                doc == null ? "(none)" : doc.title());
        if (doc == null || doc.id() == null || doc.id().isBlank()) {
            return List.of();
        }
        try {
            JsonNode page = http.get()
                    .uri(uri -> uri.path("/wiki/rest/api/content/{id}")
                            .queryParam("expand", "history,version,history.lastUpdated")
                            .build(doc.id()))
                    .retrieve().body(JsonNode.class);
            List<Contact> out = new ArrayList<>();
            if (page == null) return out;

            JsonNode version = page.path("version");
            String lastEditor = version.path("by").path("displayName").asText("");
            if (!lastEditor.isBlank()) {
                out.add(new Contact(lastEditor,
                        version.path("by").path("email").asText(""),
                        "confluence", "last edited this page", doc.url(),
                        "last edited " + version.path("when").asText("")));
            }
            JsonNode createdBy = page.path("history").path("createdBy");
            String author = createdBy.path("displayName").asText("");
            if (!author.isBlank() && !author.equals(lastEditor)) {
                out.add(new Contact(author,
                        createdBy.path("email").asText(""),
                        "confluence", "original author of this page", doc.url(),
                        "created " + page.path("history").path("createdDate").asText("")));
            }
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
