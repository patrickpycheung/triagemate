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
                List.of("prod/payment"), maxResults, maxWindowMinutes);
        return sumo;
    }

    @Test
    void windowWiderThanTheMaxIsClampedAnchoredOnTheRequestedEnd() {
        RecordingSumo sumo = wireWith(20, 30);   // max 30 minutes
        OffsetDateTime to = OffsetDateTime.parse("2026-07-30T12:00:00Z");
        OffsetDateTime from = to.minusHours(6);   // model asked for a 6-hour window

        TriageMateTools.searchLogs("prod/payment", "ORD-4031", from.toString(), to.toString());

        assertThat(sumo.lastRequest.toTime()).isEqualTo(to);              // end untouched
        assertThat(sumo.lastRequest.fromTime()).isEqualTo(to.minusMinutes(30));  // clamped, not the requested 6h
    }

    @Test
    void windowWithinTheMaxIsPassedThroughUnchanged() {
        RecordingSumo sumo = wireWith(20, 30);
        OffsetDateTime to = OffsetDateTime.parse("2026-07-30T12:00:00Z");
        OffsetDateTime from = to.minusMinutes(10);   // well within the 30-minute cap

        TriageMateTools.searchLogs("prod/payment", "ORD-4031", from.toString(), to.toString());

        assertThat(sumo.lastRequest.fromTime()).isEqualTo(from);
        assertThat(sumo.lastRequest.toTime()).isEqualTo(to);
    }

    @Test
    void reversedFromAndToAreSwappedNotRejected() {
        RecordingSumo sumo = wireWith(20, 30);
        OffsetDateTime earlier = OffsetDateTime.parse("2026-07-30T11:50:00Z");
        OffsetDateTime later = OffsetDateTime.parse("2026-07-30T12:00:00Z");

        // fromIso/toIso reversed — a model-supplied pair could be malformed this way.
        TriageMateTools.searchLogs("prod/payment", "ORD-4031", later.toString(), earlier.toString());

        assertThat(sumo.lastRequest.fromTime()).isEqualTo(earlier);
        assertThat(sumo.lastRequest.toTime()).isEqualTo(later);
    }

    @Test
    void configuredMaxResultsIsPassedThroughNotHardcodedTwenty() {
        RecordingSumo sumo = wireWith(3, 30);   // deliberately NOT the old hardcoded 20
        OffsetDateTime to = OffsetDateTime.now();

        TriageMateTools.searchLogs("prod/payment", "q", to.minusMinutes(5).toString(), to.toString());

        assertThat(sumo.lastRequest.maxResults()).isEqualTo(3);
    }

    @Test
    void outOfAllowlistScopeIsStillRejected() {
        wireWith(20, 30);
        assertThatThrownBy(() -> TriageMateTools.searchLogs(
                "prod/not-allowed", "q", "2026-07-30T11:00:00Z", "2026-07-30T12:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
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
