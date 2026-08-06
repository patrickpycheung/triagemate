package com.company.triage.gateway.fixture;

import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.mock.MockConfluenceGateway;
import com.company.triage.gateway.mock.MockGitLabGateway;
import com.company.triage.gateway.mock.MockServiceNowGateway;
import com.company.triage.model.IncidentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariant that makes recorded fixtures trustworthy: once an incident has ANY
 * recording, no gateway may answer it out of the hand-written J7 demo dataset.
 *
 * <p>Without this, a partial capture is worse than no capture. The real INC0010015 capture
 * got ServiceNow, Confluence and Sumo but not GitLab (its endpoint 403s off the corp
 * network) — and the first replay of that bundle answered a Delivery Hazards ticket with
 * {@code payment_service.py:44}, an invented repo from an unrelated demo story, presented
 * as a code citation. Plausible, specific, and completely false.
 */
class RecordedIncidentNeverFallsBackToDemoDataTest {

    /**
     * Deliberately NOT the real INC0010015. FixtureStore falls back from the on-disk
     * directory to the CLASSPATH, and the committed INC0010015 bundle is on the classpath
     * during tests — so using that number here would silently read the real recording
     * instead of this test's @TempDir one, and the "connector not captured" case could
     * never be constructed. A number nothing has recorded keeps the temp bundle authoritative.
     */
    private static final String RECORDED = "INC9999901";
    private static final String LEGACY = "INC0010005";

    /** A bundle with ServiceNow captured and GitLab/Confluence deliberately missing. */
    private FixtureStore partiallyCaptured(Path dir) throws Exception {
        Path inc = dir.resolve(RECORDED);
        Files.createDirectories(inc);
        Files.writeString(inc.resolve("servicenow.json"), """
                {"getIncident|%s": {
                   "number": "%s",
                   "shortDescription": "Hazards recorded on handheld are not appearing",
                   "description": "d", "caller": "c", "category": "inquiry", "subcategory": "",
                   "openedAt": "2026-08-06T05:01:53Z", "environment": null,
                   "currentAssignment": "Application Development",
                   "comments": [], "workNotes": [],
                   "configurationItem": "Delivery Hazards", "reassignmentHistory": []
                 }}
                """.formatted(RECORDED.toLowerCase(), RECORDED));
        return new FixtureStore(dir.toString());
    }

    @Test
    void servesTheRecordedIncidentRatherThanTheSeededOne(@TempDir Path dir) throws Exception {
        FixtureStore store = partiallyCaptured(dir);
        FixtureSession session = new FixtureSession();

        IncidentContext inc = new MockServiceNowGateway(store, session, com.company.triage.gateway.mock.MockLatency.none(), null).getIncident(RECORDED);

        assertThat(inc.number()).isEqualTo(RECORDED);
        assertThat(inc.configurationItem()).isEqualTo("Delivery Hazards");
        assertThat(inc.description()).doesNotContain("INC-ORD-4471");
    }

    @Test
    void anUncapturedConnectorReportsUnavailableRatherThanNoResults(@TempDir Path dir)
            throws Exception {
        FixtureStore store = partiallyCaptured(dir);
        FixtureSession session = new FixtureSession();
        new MockServiceNowGateway(store, session, com.company.triage.gateway.mock.MockLatency.none(), null).getIncident(RECORDED);   // latches the incident

        // NOT an empty list: "I could not search" and "I searched and found nothing" are
        // different claims, and only one of them is true here.
        assertThatThrownBy(() -> new MockGitLabGateway(store, session, com.company.triage.gateway.mock.MockLatency.none())
                .searchCode("enterprise/parcel-systems/applications/delivery-hazards",
                        "DataIntegrityViolationException"))
                .isInstanceOf(GatewayUnavailableException.class)
                .hasMessageContaining("GitLab");

        assertThatThrownBy(() -> new MockConfluenceGateway(store, session, com.company.triage.gateway.mock.MockLatency.none())
                .search("Delivery Hazards"))
                .isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void theLegacyDemoIncidentStillWorksWithNoFixturesOnDisk(@TempDir Path dir) {
        // The offline stage walkthrough and the e2e suite both drive INC0010005, and neither
        // has a fixture bundle. An incident with no recordings keeps the J7 dataset.
        FixtureStore empty = new FixtureStore(dir.toString());
        FixtureSession session = new FixtureSession();

        IncidentContext inc = new MockServiceNowGateway(empty, session, com.company.triage.gateway.mock.MockLatency.none(), null).getIncident(LEGACY);

        assertThat(inc.configurationItem()).isEqualTo("Order Portal");
        assertThat(new MockConfluenceGateway(empty, session, com.company.triage.gateway.mock.MockLatency.none()).search("payment reconcile"))
                .hasSize(1);
    }
}
