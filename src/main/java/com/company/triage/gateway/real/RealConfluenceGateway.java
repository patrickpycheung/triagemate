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

    /**
     * J22: takes the injected {@link RestClient.Builder} so a test can bind a
     * {@code MockRestServiceServer} to it. Without that seam this gateway had <b>zero</b>
     * tests, which is how FND-83's base-URL 404 reached a live run.
     */
    public RealConfluenceGateway(RestClient.Builder builder, IntegrationProperties props) {
        var cf = props.confluence();
        String basic = Base64.getEncoder()
                .encodeToString((cf.user() + ":" + cf.secret()).getBytes());
        this.http = builder
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
            // J25/KQR-1 — `siteSearch`, NOT `text`. This is the whole fix for Siyad's §4,
            // and it is an OPERATOR problem, not a keyword problem.
            //
            // Measured against the real AusPost instance on 2026-08-05, incident INC0010010,
            // scoring the top 8 titles for Delivery Hazards relevance:
            //
            //   text ~ "<the app's own query>"         8 results, 0 relevant
            //   text ~ "Delivery Hazards"              8 results, 0 relevant
            //   text ~ "Delivery Hazards" type=page    8 results, 0 relevant
            //   siteSearch ~ "<the SAME app query>"    8 results, 8 relevant
            //   siteSearch ~ "Delivery Hazards"        8 results, 8 relevant
            //   siteSearch ~ "<the subject line>"      8 results, 8 relevant
            //
            // Note row 4: the identical noisy query that returned nothing useful under `text`
            // returns entirely relevant results under `siteSearch`. The field report's
            // conclusion that the query was "not returning relevant results" was right; the
            // natural inference — that the words were wrong — was not. `text ~` is a raw
            // content match with no relevance ranking, while `siteSearch ~` is the operator
            // backing Confluence's own UI search. That is precisely why the reporter could
            // paste the same words into the UI and get useful pages while the app got a
            // Teradata data-model appendix.
            //
            // `type = page` (KQR-3) drops attachments and database objects: three of the five
            // results in the field report were attachments whose "snippet" is a filename.
            String cql = "siteSearch ~ \"" + query.replace("\"", " ") + "\" AND type = page";
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
