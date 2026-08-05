package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.model.KnowledgeDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE test against the real Confluence instance — the one that proves the J25 fix actually
 * retrieves relevant content, which no offline test can.
 *
 * <p><b>Why this exists.</b> {@code RealConfluenceGatewayTest} pins the request SHAPE against a
 * mock server: it can prove we send {@code siteSearch}, never that {@code siteSearch} returns
 * anything useful. Siyad's §4 was precisely a case where the shape was fine and the RESULTS
 * were worthless, so shape-only coverage would have passed while the demo failed. The gap
 * between "well-formed request" and "useful answer" is exactly where this connector's bugs
 * live — FND-83's 404 and this relevance failure both sat in it.
 *
 * <p><b>Skips cleanly without credentials.</b> Gated on {@code secrets.properties} carrying a
 * Confluence secret, so it is a no-op on any machine without one (CI, a teammate's clone) and
 * never fails the default build. Same precedent as {@code RealSumoGatewayLiveTest}, with one
 * deliberate difference: that test FAILS in a sandboxed environment (CLAUDE.md documents it as
 * a known environment failure), whereas this one skips — an environment without credentials is
 * not a regression, and a test that cries wolf gets ignored.
 *
 * <p><b>Assertions are deliberately weak on specifics.</b> Real Confluence content changes;
 * asserting exact page titles would make this a flake generator. It asserts the PROPERTY the
 * fix is about — that a Delivery Hazards incident retrieves Delivery Hazards material — which
 * is what actually regressed.
 */
class RealConfluenceGatewayLiveTest {

    /** Live incident from the field report (INC0010010). */
    private static final String SUBJECT =
            "Hazards being recorded on handheld are not appearing in Delivery Hazards application.";
    /** What the app sent before J25 — kept as the control. */
    private static final String OLD_NOISY_QUERY =
            "hazards being recorded handheld appearing delivery application attached Hazards being recorded on";

    private static Properties secrets() {
        Properties p = new Properties();
        Path file = Path.of("secrets.properties");
        if (Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (Exception ignored) {
                // treated as "no credentials" below
            }
        }
        return p;
    }

    static boolean credentialsPresent() {
        Properties p = secrets();
        return notBlank(p.getProperty("triage.integrations.confluence.base-url"))
                && notBlank(p.getProperty("triage.integrations.confluence.user"))
                && notBlank(p.getProperty("triage.integrations.confluence.secret"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private RealConfluenceGateway gateway() {
        Properties p = secrets();
        var endpoint = new IntegrationProperties.Endpoint(
                p.getProperty("triage.integrations.confluence.base-url"),
                p.getProperty("triage.integrations.confluence.user"),
                p.getProperty("triage.integrations.confluence.secret"), null);
        return new RealConfluenceGateway(RestClient.builder(),
                new IntegrationProperties(null, endpoint, null, null));
    }

    /** The fix, end to end: the incident's own words must retrieve its own application's pages. */
    @Test
    @EnabledIf("credentialsPresent")
    void aDeliveryHazardsIncidentRetrievesDeliveryHazardsPages() {
        List<KnowledgeDoc> docs = gateway().search(SUBJECT + " Delivery Hazards");

        assertThat(docs).as("a live search must return something").isNotEmpty();
        long relevant = docs.stream()
                .filter(d -> d.title() != null && d.title().toLowerCase().contains("hazard"))
                .count();
        assertThat(relevant)
                .as("most results should concern hazards — got: %s",
                        docs.stream().map(KnowledgeDoc::title).toList())
                .isGreaterThanOrEqualTo(Math.max(1, docs.size() / 2));
    }

    /**
     * The control that isolates the CAUSE. The same noisy string the app used to send now
     * returns relevant results too — because the operator changed, not the words. If this ever
     * fails while the test above passes, the fix has silently become a query-text fix and the
     * real lesson has been lost.
     */
    @Test
    @EnabledIf("credentialsPresent")
    void evenTheOldNoisyQueryIsRelevantNowThatTheOperatorIsCorrect() {
        List<KnowledgeDoc> docs = gateway().search(OLD_NOISY_QUERY);

        assertThat(docs).isNotEmpty();
        assertThat(docs.stream().anyMatch(
                d -> d.title() != null && d.title().toLowerCase().contains("hazard")))
                .as("siteSearch ranks by relevance, so even a keyword bag finds hazard pages — "
                        + "got: %s", docs.stream().map(KnowledgeDoc::title).toList())
                .isTrue();
    }

    /** KQR-3: no attachments or database objects among the results. */
    @Test
    @EnabledIf("credentialsPresent")
    void resultsArePagesNotAttachments() {
        List<KnowledgeDoc> docs = gateway().search(SUBJECT);

        assertThat(docs).isNotEmpty();
        assertThat(docs).allSatisfy(d -> assertThat(d.title())
                .as("attachments surface as filenames and were cited as evidence in the field report")
                .doesNotEndWith(".pdf").doesNotEndWith(".docx").doesNotEndWith(".xlsx"));
    }

    /** FND-83 stays fixed against the real instance: the /wiki path resolves, no 404. */
    @Test
    @EnabledIf("credentialsPresent")
    void theWikiContextPathResolvesAgainstTheRealInstance() {
        assertThat(gateway().search("Delivery Hazards"))
                .as("an empty list here means the blanket catch swallowed a 404 (see J25/KQR-4)")
                .isNotEmpty();
    }

    /**
     * J27, end to end: the query the engine now actually sends for INC0010010 is the CI name
     * alone, and that alone must retrieve real Delivery Hazards pages from the live instance.
     *
     * <p>This is the assertion the operator asked for directly — not "a well-formed request
     * went out" but "the system name on its own comes back with content". Measured
     * 2026-08-05: five pages, all Delivery Hazards reference documentation.
     */
    @Test
    @EnabledIf("credentialsPresent")
    void theSystemNameAloneRetrievesThatSystemsPages() {
        List<KnowledgeDoc> docs = gateway().search("Delivery Hazards");

        assertThat(docs).as("the name-only query must return content, not an empty list")
                .isNotEmpty();
        assertThat(docs).allSatisfy(d -> assertThat(d.title().toLowerCase())
                .as("every result should concern the named system — got: %s",
                        docs.stream().map(KnowledgeDoc::title).toList())
                .contains("hazard"));
    }
}
