package com.company.triage.agent;

import com.company.triage.gateway.*;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FND-20: {@code search_logs} previously hardcoded {@code maxResults=20} (the
 * {@code triage.sumo.max-results} config key was declared and never read) and took the
 * time window verbatim from the model with no span check — "the app supplies a fixed
 * time window" was false. Both are now enforced server-side in {@code TriageMateTools},
 * proven here against a fake gateway that records the exact {@link LogSearchRequest} it
 * actually received.
 */
class TriageMateToolsSearchLogsTest {

    static class RecordingSumo implements SumoGateway {
        LogSearchRequest lastRequest;
        public List<LogEvidence> search(LogSearchRequest request) {
            lastRequest = request;
            return List.of();
        }
    }

    private RecordingSumo wireWith(int maxResults, int maxWindowMinutes) {
        RecordingSumo sumo = new RecordingSumo();
        TriageMateTools.wire(new NoopServiceNow(), new NoopConfluence(), sumo, new NoopGitLab(),
                new com.company.triage.config.TriageProperties.Sumo(
                        "IDT/ITServices/Tomcat/{project}/{environment}/AppEvt_{project}",
                        java.util.Map.of(), "Global_Standard_Infrequent",
                        List.of("pdev", "ptest", "stest", "vtest", "prod"),
                        maxResults, maxWindowMinutes),
                List.of("order-payments/payment-service"));
        return sumo;
    }

    @Test
    void windowWiderThanTheMaxIsClampedAnchoredOnTheRequestedEnd() {
        RecordingSumo sumo = wireWith(20, 30);   // max 30 minutes
        OffsetDateTime to = OffsetDateTime.parse("2026-07-30T12:00:00Z");
        OffsetDateTime from = to.minusHours(6);   // model asked for a 6-hour window

        TriageMateTools.searchLogs("payment-service", "prod", "ORD-4031", from.toString(), to.toString());

        assertThat(sumo.lastRequest.toTime()).isEqualTo(to);              // end untouched
        assertThat(sumo.lastRequest.fromTime()).isEqualTo(to.minusMinutes(30));  // clamped, not the requested 6h
    }

    @Test
    void windowWithinTheMaxIsPassedThroughUnchanged() {
        RecordingSumo sumo = wireWith(20, 30);
        OffsetDateTime to = OffsetDateTime.parse("2026-07-30T12:00:00Z");
        OffsetDateTime from = to.minusMinutes(10);   // well within the 30-minute cap

        TriageMateTools.searchLogs("payment-service", "prod", "ORD-4031", from.toString(), to.toString());

        assertThat(sumo.lastRequest.fromTime()).isEqualTo(from);
        assertThat(sumo.lastRequest.toTime()).isEqualTo(to);
    }

    @Test
    void reversedFromAndToAreSwappedNotRejected() {
        RecordingSumo sumo = wireWith(20, 30);
        OffsetDateTime earlier = OffsetDateTime.parse("2026-07-30T11:50:00Z");
        OffsetDateTime later = OffsetDateTime.parse("2026-07-30T12:00:00Z");

        // fromIso/toIso reversed — a model-supplied pair could be malformed this way.
        TriageMateTools.searchLogs("payment-service", "prod", "ORD-4031", later.toString(), earlier.toString());

        assertThat(sumo.lastRequest.fromTime()).isEqualTo(earlier);
        assertThat(sumo.lastRequest.toTime()).isEqualTo(later);
    }

    @Test
    void configuredMaxResultsIsPassedThroughNotHardcodedTwenty() {
        RecordingSumo sumo = wireWith(3, 30);   // deliberately NOT the old hardcoded 20
        OffsetDateTime to = OffsetDateTime.now();

        TriageMateTools.searchLogs("payment-service", "prod", "q", to.minusMinutes(5).toString(), to.toString());

        assertThat(sumo.lastRequest.maxResults()).isEqualTo(3);
    }

    @Test
    void outOfAllowlistEnvironmentIsStillRejected() {
        wireWith(20, 30);
        assertThatThrownBy(() -> TriageMateTools.searchLogs(
                "payment-service", "not-an-env", "q", "2026-07-30T11:00:00Z", "2026-07-30T12:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment not allowed");
    }

    /**
     * The model supplies a project slug + environment, never a raw _sourceCategory — the app
     * composes it. A malformed slug (a path, a wildcard, anything that isn't a plain slug)
     * must be rejected rather than interpolated into the category.
     */
    @Test
    void malformedProjectSlugIsRejectedRatherThanInterpolated() {
        wireWith(20, 30);
        for (String bad : List.of("prod/payment", "*", "Delivery Hazards", "../etc")) {
            assertThatThrownBy(() -> TriageMateTools.searchLogs(
                    bad, "prod", "q", "2026-07-30T11:00:00Z", "2026-07-30T12:00:00Z"))
                    .as("slug %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("projectSlug");
        }
    }

    /** The composed category follows the configured pattern, and carries the index clause. */
    @Test
    void sourceCategoryIsComposedFromTheConfiguredPattern() {
        RecordingSumo sumo = wireWith(20, 30);
        OffsetDateTime to = OffsetDateTime.parse("2026-07-30T12:00:00Z");

        TriageMateTools.searchLogs("delivery-hazards", "ptest", "ORD-1",
                to.minusMinutes(5).toString(), to.toString());

        assertThat(sumo.lastRequest.sourceCategory())
                .isEqualTo("IDT/ITServices/Tomcat/delivery-hazards/ptest/AppEvt_delivery-hazards");
        assertThat(sumo.lastRequest.toSumoQuery())
                .isEqualTo("_sourceCategory=IDT/ITServices/Tomcat/delivery-hazards/ptest/"
                        + "AppEvt_delivery-hazards and _index=Global_Standard_Infrequent ORD-1");
    }

    /**
     * FND-38: {@code search_code} previously accepted any model-supplied project string
     * unchecked — J8 documented a GitLab-project allowlist that did not exist in code.
     * Same fix shape as FND-20's Sumo scope bound: enforced in {@code TriageMateTools},
     * not just claimed in the docs.
     */
    @Test
    void outOfAllowlistGitLabProjectIsRejected() {
        wireWith(20, 30);
        assertThatThrownBy(() -> TriageMateTools.searchCode("some/other-project", "TOKEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void allowlistedGitLabProjectIsPassedThrough() {
        RecordingGitLab gitLab = new RecordingGitLab();
        TriageMateTools.wire(new NoopServiceNow(), new NoopConfluence(), new RecordingSumo(), gitLab,
                com.company.triage.config.TriagePropertiesFixture.sumo(),
                List.of("order-payments/payment-service"));

        TriageMateTools.searchCode("order-payments/payment-service", "TOKEN");

        assertThat(gitLab.lastProject).isEqualTo("order-payments/payment-service");
        assertThat(gitLab.lastTerm).isEqualTo("TOKEN");
    }

    static class RecordingGitLab implements GitLabGateway {
        String lastProject;
        String lastTerm;
        public List<CodeSearchResult> searchCode(String project, String term) {
            lastProject = project; lastTerm = term;
            return List.of();
        }
        public List<Contact> recentCommitters(String project, String filePath) { return List.of(); }
    }

    // --- minimal no-op stand-ins for the gateways searchLogs doesn't exercise ---
    static class NoopServiceNow implements ServiceNowGateway {
        public IncidentContext getIncident(String n) { return null; }
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) { return List.of(); }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String n, String note) {}
    }
    static class NoopConfluence implements ConfluenceGateway {
        public List<KnowledgeDoc> search(String query) { return List.of(); }
        // contributors(KnowledgeDoc) has a default no-op impl — nothing to override.
    }
    static class NoopGitLab implements GitLabGateway {
        public List<CodeSearchResult> searchCode(String project, String term) { return List.of(); }
        public List<Contact> recentCommitters(String project, String filePath) { return List.of(); }
    }
}
