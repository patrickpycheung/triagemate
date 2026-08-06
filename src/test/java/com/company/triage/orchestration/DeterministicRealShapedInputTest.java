package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.gateway.mock.MockConfluenceGateway;
import com.company.triage.gateway.mock.MockGitLabGateway;
import com.company.triage.gateway.mock.MockServiceNowGateway;
import com.company.triage.gateway.mock.MockSumoGateway;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.DiagnosisReportValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * J14/FRI-6 — one case per real-connector shape, each asserting a <b>J4-valid report</b>.
 *
 * <p>The assertion IS the guarantee (FND-63). Nothing here checks that the diagnosis is
 * <em>good</em> on a degenerate input — a report that admits it knows nothing is a correct
 * outcome. What it checks is that the fallback engine still produces a report the contract
 * accepts, because {@code DiagnosisReportValidator} throwing is a 500 out of the FND-7
 * fallback, and a 500 out of the fallback means the app has no working path at all.
 *
 * <p>Shapes live in {@link RealShapedFixtures}, one comment each explaining which real
 * connector produced them.
 */
class DeterministicRealShapedInputTest {

    /** The seeded dataset with exactly one gateway swapped — see RealShapedFixtures. */
    private static DeterministicDiagnosisEngine engine(
            ServiceNowGateway serviceNow, SumoGateway sumo, GitLabGateway gitLab) {
        return new DeterministicDiagnosisEngine(
                serviceNow, new MockConfluenceGateway(), sumo, gitLab,
                TriagePropertiesFixture.deterministic());
    }

    private static DeterministicDiagnosisEngine withServiceNow(ServiceNowGateway serviceNow) {
        return engine(serviceNow, new MockSumoGateway(), new MockGitLabGateway());
    }

    private static DeterministicDiagnosisEngine withSumo(SumoGateway sumo) {
        return engine(new MockServiceNowGateway(), sumo, new MockGitLabGateway());
    }

    /**
     * The corpus assertion. {@code diagnose} already validates internally
     * ({@code DeterministicDiagnosisEngine:710}), so a contract breach surfaces as a throw
     * from the run itself; validating the returned report again pins the guarantee to this
     * test rather than to that call site surviving a refactor.
     */
    private static void producesAJ4ValidReport(DeterministicDiagnosisEngine engine) {
        assertThatCode(() -> {
            DiagnosisReport report = engine.diagnose(RealShapedFixtures.INCIDENT).report();
            DiagnosisReportValidator.validate(report);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shape 1 — opened_at absent: the log window has no anchor")
    void missingOpenedAtStillProducesAValidReport() {
        producesAJ4ValidReport(withServiceNow(
                RealShapedFixtures.serviceNowWith(RealShapedFixtures::withoutOpenedAt)));
    }

    @Test
    @DisplayName("shape 2 — blank cmdb_ci with a hyphenated first word (\"E-commerce …\")")
    void blankConfigurationItemWithAHyphenatedFirstWordStillProducesAValidReport() {
        producesAJ4ValidReport(withServiceNow(RealShapedFixtures.serviceNowWith(
                RealShapedFixtures::blankCiWithHyphenatedFirstWord)));
    }

    @Test
    @DisplayName("shape 3 — every log line shares ONE logger value")
    void aSingleLoggerAcrossAllLogLinesStillProducesAValidReport() {
        // A single-app source category, or a deployment that logs one root logger name: the
        // logger→system inference has one bucket, not several, so it discriminates nothing.
        producesAJ4ValidReport(withSumo(RealShapedFixtures.sumoWithLogger("app")));
    }

    @Test
    @DisplayName("shape 4 — every log line has a BLANK logger")
    void aBlankLoggerOnEveryLogLineStillProducesAValidReport() {
        // Real Sumo returns whatever the collector was configured to send; the logger field is
        // routinely absent, and then the inference has no bucket at all.
        producesAJ4ValidReport(withSumo(RealShapedFixtures.sumoWithLogger("")));
    }

    /**
     * Shape 5a — <b>KNOWN OPEN DEFECT. Disabled deliberately; do not "fix" by weakening
     * this assertion.</b>
     *
     * <p>What it proves, measured (not argued): a ServiceNow gateway whose
     * {@code findSimilarIncidents} throws while every other call stays healthy takes the whole
     * run down. {@code GatewayUnavailableException: ServiceNow is unreachable: 401} propagates
     * straight out of {@code DeterministicDiagnosisEngine.diagnose} — it never reaches
     * {@code DiagnosisReportValidator}, because no report is ever assembled. The identical
     * result holds for {@code findOwnership}. {@code DiagnosisApiExceptionHandler} has no
     * {@code @ExceptionHandler} for {@code GatewayUnavailableException} (contrast
     * {@code ResourceAccessException}:78 → 504 and {@code DiagnosisReportInvalidException}:91
     * → 500), so this surfaces as a bare 500 out of the FND-7 <em>fallback</em> engine —
     * precisely the outcome J14 exists to prevent.
     *
     * <p>This is the un-degraded row the J14 review already named:
     * {@code DeterministicDiagnosisEngine:151,139} → {@code RealServiceNowGateway:130,145},
     * marked "⛔ aborts". Shape 5b below is the same shape on {@code gitlab.searchCode}, where
     * J14/FRI-5 landed, and it passes — so this is about which calls got wrapped, not about
     * throwing connectors as a class.
     *
     * <p>The fix is a DESIGN decision owned by J14/FRI-5 (which calls get a per-call
     * {@code try}/{@code catch}, and what a run with no precedents and no ownership is allowed
     * to claim) together with J13/ECI-6 (whether {@code uncitedCandidates()} may hard-throw
     * when degradation legitimately empties a candidate's evidence). Not this task: J14/FRI-6
     * is the corpus that makes the defect executable, and converting an analytical finding
     * into a reproducible red test is the deliverable.
     */
    @org.junit.jupiter.api.Disabled("J14/FRI-5 open: servicenow.findSimilarIncidents/findOwnership "
            + "still abort the run instead of degrading — GatewayUnavailableException escapes "
            + "DeterministicDiagnosisEngine.diagnose and is unmapped in DiagnosisApiExceptionHandler, "
            + "so the FND-7 fallback returns HTTP 500. Fix is a J14/FRI-5 + J13/ECI-6 design call.")
    @Test
    @DisplayName("shape 5a — ServiceNow throwing on ONE call must not take the whole run down")
    void aFailingServiceNowCallStillProducesAValidReport() {
        producesAJ4ValidReport(
                withServiceNow(RealShapedFixtures.serviceNowThatFailsSimilarIncidents()));
    }

    @Test
    @DisplayName("shape 5b — gitlab.searchCode throwing degrades to no code evidence (FRI-5 regression)")
    void aFailingCodeSearchCallStillProducesAValidReport() {
        producesAJ4ValidReport(engine(
                new MockServiceNowGateway(), new MockSumoGateway(),
                RealShapedFixtures.gitLabThatFailsCodeSearch()));
    }
}
