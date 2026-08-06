package com.company.triage.orchestration;

import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.gateway.mock.MockConfluenceGateway;
import com.company.triage.gateway.mock.MockGitLabGateway;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things the report used to assert that the data did not support. Both were found by
 * running the demo against the real recorded estate and reading what came out on screen.
 *
 * <ol>
 *   <li>An IP address offered as a candidate SYSTEM, ranked above the real one. The live Sumo
 *       estate puts a host address in the logger field, so INC0010015's top suspect was
 *       "13.237.99.137" — not something a human can act on, and it displaced the system that
 *       was.</li>
 *   <li>"resolved by null: null" on every similar incident, because the Delivery Hazards
 *       estate is entirely state=New. Then, once the nulls were handled, "resolved by
 *       Application Development" on an incident that was never resolved — assignment_group is
 *       present on open tickets too.</li>
 * </ol>
 *
 * <p>Both are the FND-8 class: stating something untrue in the voice of a fact. A demo
 * audience cannot check either claim, which is exactly why it must not be made.
 */
class ReportSaysOnlyWhatTheDataSupportsTest {

    private DiagnosisReport diagnose(ServiceNowGateway snow, SumoGateway sumo) {
        return new DeterministicDiagnosisEngine(snow, new MockConfluenceGateway(), sumo,
                new MockGitLabGateway(), com.company.triage.config.TriagePropertiesFixture.deterministic())
                .diagnose("INC0010015").report();
    }

    private static IncidentContext incident() {
        return new IncidentContext("INC0010015", "Hazards not appearing", "d", "caller",
                "inquiry", "", OffsetDateTime.parse("2026-08-06T05:01:53Z"), null,
                "Service Desk", List.of(), List.of(), "Delivery Hazards", List.of());
    }

    /** A logger field holding a host address names a HOST, and a host is not a system. */
    @Test
    void anIpAddressIsNeverOfferedAsACandidateSystem() {
        SumoGateway sumo = req -> List.of(
                new LogEvidence("2026-08-06T05:23:56Z", "ERROR", "13.237.99.137",
                        "org.springframework.dao.DataIntegrityViolationException: boom"));

        var report = diagnose(new StubServiceNow(List.of()), sumo);

        assertThat(report.candidateSystems()).extracting(CandidateSystem::name)
                .noneMatch(n -> n.matches("\\d{1,3}(\\.\\d{1,3}){3}"));
        // The CMDB-derived system must survive — dropping the IP must not empty the list.
        assertThat(report.candidateSystems()).extracting(CandidateSystem::name)
                .contains("Delivery Hazards");
    }

    @Test
    void anUnresolvedPrecedentIsNotDescribedAsResolved() {
        var report = diagnose(new StubServiceNow(List.of(
                // No close code and no group: entirely open.
                new ResolvedIncident("INC0010010", "same symptom", null, null, null, 0.62),
                // Assigned, but never closed — the shape that produced "resolved by X".
                new ResolvedIncident("INC0010014", "same symptom", "Application Development",
                        null, null, 0.62),
                // Genuinely resolved: a close code exists.
                new ResolvedIncident("INC0009918", "same symptom", "Payments Platform",
                        "Solved (Permanently)", "rolled back", 0.71))),
                req -> List.of());

        var summaries = report.evidence().stream().map(Evidence::summary).toList();

        assertThat(summaries).anyMatch(s -> s.contains("INC0010010") && s.contains("still open"));
        assertThat(summaries).anyMatch(s -> s.contains("INC0010014") && s.contains("assigned to"));
        assertThat(summaries).anyMatch(s ->
                s.contains("INC0009918") && s.contains("resolved by Payments Platform"));

        // The two failure modes this replaced, in one assertion.
        assertThat(summaries).noneMatch(s -> s.contains("null"));
        assertThat(summaries).noneMatch(s ->
                s.contains("INC0010014") && s.contains("resolved by"));
    }

    /** Minimal ServiceNow: one incident, a caller-supplied precedent list, known ownership. */
    private record StubServiceNow(List<ResolvedIncident> similar) implements ServiceNowGateway {
        @Override public IncidentContext getIncident(String number) { return incident(); }
        @Override public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) { return List.of(); }
        @Override public List<ResolvedIncident> findSimilarIncidents(IncidentContext i) { return similar; }
        @Override public Optional<ServiceOwnership> findOwnership(String app) {
            return Optional.of(new ServiceOwnership("Delivery Hazards", "Application Development",
                    "Parcel Systems", "cmdb_ci_service"));
        }
        @Override public void addWorkNote(String number, String note) { }
    }
}
