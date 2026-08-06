package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.DiagnosisReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J14/FRI-5 and J25/KQR-4 — the safety net degrades <b>per call</b>, and says which call.
 *
 * <p>Two guarantees, and the second is the one that actually bit. The first is that an
 * unreachable connector costs its own evidence rather than the run: this engine is the FND-7
 * fallback, so throwing here means the app has no working path at all.
 *
 * <p>The second is that "could not search" is never reported in the words used for "searched
 * and found nothing". A Confluence base-URL 404 read as a clean no-match for a full day —
 * the report said no runbook matched, which is exactly what it says when no runbook exists,
 * and nobody debugs a connector the app is reporting as healthy.
 */
class PerCallDegradationTest {

    private DeterministicDiagnosisEngine engine(ConfluenceGateway confluence, SumoGateway sumo) {
        return new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), confluence, sumo, new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());
    }

    private static ConfluenceGateway deadConfluence() {
        return q -> { throw new GatewayUnavailableException("Confluence",
                new IllegalStateException("404 Not Found")); };
    }

    private static SumoGateway deadSumo() {
        return r -> { throw new GatewayUnavailableException("Sumo Logic",
                new IllegalStateException("401 Unauthorized")); };
    }

    @Test
    void anUnreachableConnectorCostsItsOwnEvidenceNotTheWholeRun() {
        DiagnosisReport report =
                engine(deadConfluence(), new MockSumoGateway()).diagnose("INC0010005").report();

        assertThat(report.candidateSystems())
                .as("this engine IS the fallback — if it throws, nothing catches it")
                .isNotEmpty();
        assertThat(report.suggestedAssignment()).isNotNull();
        assertThat(report.evidence()).isNotEmpty();
    }

    @Test
    void theReportSaysTheConnectorFailedRatherThanThatNothingMatched() {
        DiagnosisReport report =
                engine(deadConfluence(), new MockSumoGateway()).diagnose("INC0010005").report();

        assertThat(report.missingInformation())
                .as("the distinction the whole rule exists for")
                .anyMatch(m -> m.contains("Confluence is unreachable"));
        assertThat(report.missingInformation())
                .as("and it must NOT also claim the search happened and matched nothing")
                .noneMatch(m -> m.contains("none matched the symptom terms"));
    }

    @Test
    void anUnreachableLogSearchWithholdsTheNoErrorsConclusion() {
        DiagnosisReport report =
                engine(new MockConfluenceGateway(), deadSumo()).diagnose("INC0010005").report();

        assertThat(report.missingInformation())
                .anyMatch(m -> m.contains("Sumo Logic is unreachable"));
        assertThat(report.candidateSystems()).isNotEmpty();
    }

    @Test
    void twoDeadConnectorsStillProduceAReport() {
        DiagnosisReport report =
                engine(deadConfluence(), deadSumo()).diagnose("INC0010005").report();

        assertThat(report.candidateSystems()).isNotEmpty();
        assertThat(report.missingInformation())
                .anyMatch(m -> m.contains("Confluence is unreachable"))
                .anyMatch(m -> m.contains("Sumo Logic is unreachable"));
        assertThat(report.advisory()).isTrue();
    }
}
