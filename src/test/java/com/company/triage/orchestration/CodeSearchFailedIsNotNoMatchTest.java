package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
import com.company.triage.orchestration.trace.StepState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J30/GEB-3 — "could not search" is not "no code matched".
 *
 * <p>Once the allowlist sweep SURVIVES a miss (J14/FRI-5), {@code 0 hit(s)} became ambiguous:
 * it reads identically for "searched and found nothing" and "never successfully searched
 * anything". Those lead a triager to opposite conclusions — the first says the code is probably
 * not the culprit, the second says nothing has been learned at all.
 *
 * <p>Third instance of one pattern, after J25/KQR-4 (failed vs empty Confluence search) and
 * J29/LLF-2 (an unreadable log level). The J30 card notes it is worth stating once as a rule if
 * a fourth appears.
 */
class CodeSearchFailedIsNotNoMatchTest {

    private DiagnosisResult diagnoseWith(GitLabGateway gitLab) {
        return new DeterministicDiagnosisEngine(new MockServiceNowGateway(),
                new MockConfluenceGateway(), new MockSumoGateway(), gitLab,
                TriagePropertiesFixture.deterministic()).diagnose("INC0010005");
    }

    private static GitLabGateway unreachable() {
        return new GitLabGateway() {
            public List<CodeSearchResult> searchCode(String project, String term) {
                throw new GatewayUnavailableException("GitLab", new IllegalStateException("403 at the perimeter"));
            }
            public List<Contact> recentCommitters(String project, String filePath) { return List.of(); }
        };
    }

    private static GitLabGateway findsNothing() {
        return new GitLabGateway() {
            public List<CodeSearchResult> searchCode(String project, String term) { return List.of(); }
            public List<Contact> recentCommitters(String project, String filePath) { return List.of(); }
        };
    }

    private static final String REAL = "enterprise/parcel-systems/applications/delivery-hazards";
    private static final String PHANTOM = "order-payments/payment-service";

    /** A two-entry allowlist — the shape real config has had since J30/GEB-1. */
    private DiagnosisResult diagnoseSweep(GitLabGateway gitLab) {
        return new DeterministicDiagnosisEngine(new MockServiceNowGateway(),
                new MockConfluenceGateway(), new MockSumoGateway(), gitLab,
                TriagePropertiesFixture.deterministicWithProjects(PHANTOM, REAL)).diagnose("INC0010005");
    }

    private static String gitLabSummaryLine(DiagnosisResult r) {
        return r.trace().stream().filter(l -> l.startsWith("gitlab.searchCode(term=")).findFirst().orElseThrow();
    }

    // ---- J31/ASO-1 — a failed project is skipped, not the end of the sweep ----------------

    /**
     * The defect ASO-1 fixes: the sweep used to {@code break} on the first failure, so an
     * allowlist entry that cannot resolve stopped every later candidate from being searched.
     * J30/GEB-2 already required the opposite ("a project that cannot resolve is a SKIPPED
     * project"), and its own verification row spells out "the sweep continues to the next".
     */
    @Test
    void aFailedProjectDoesNotStopTheSweep() {
        var gitLab = SweepingGitLabGateway.where()
                .unreachable(PHANTOM)
                .hit(REAL, "app/reconcile.py", 142)
                .build();

        var result = diagnoseSweep(gitLab);

        assertThat(gitLab.projectsAsked())
                .as("the sweep must reach the second candidate after the first fails")
                .contains(REAL);
        assertThat(result.report().evidence())
                .anyMatch(e -> "gitlab".equals(e.source()) && e.summary().contains("reconcile.py"));
    }

    // ---- J31/ASO-2 — five outcomes, each distinct --------------------------------------

    /**
     * The headline defect. A real project searched successfully and empty, then a phantom
     * entry's 404, used to report "COULD NOT SEARCH" — a degradation — for a search that ran
     * and truthfully found nothing. Opposite conclusions for a triager.
     */
    @Test
    void aSuccessfulEmptySearchIsNotOverwrittenByALaterFailure() {
        var gitLab = SweepingGitLabGateway.where().empty(REAL).unreachable(PHANTOM).build();

        var line = gitLabSummaryLine(diagnoseSweep(gitLab));

        assertThat(line)
                .as("something WAS searched, so this is not a blanket failure")
                .doesNotContain("COULD NOT SEARCH")
                .contains("PARTIAL")
                .contains(PHANTOM);
    }

    @Test
    void hitsAlongsideAFailureSayBoth() {
        var gitLab = SweepingGitLabGateway.where()
                .unreachable(PHANTOM).hit(REAL, "app/reconcile.py", 142).build();

        var line = gitLabSummaryLine(diagnoseSweep(gitLab));

        assertThat(line)
                .as("the hits are real, and they may not be all of them")
                .contains("hit(s)")
                .contains("could not be searched");
    }

    @Test
    void everyProjectSearchedAndEmptyIsACleanNegative() {
        var gitLab = SweepingGitLabGateway.where().empty(REAL).empty(PHANTOM).build();

        var line = gitLabSummaryLine(diagnoseSweep(gitLab));

        assertThat(line)
                .contains("found nothing")
                .doesNotContain("COULD NOT SEARCH")
                .doesNotContain("PARTIAL");
    }

    @Test
    void everyProjectFailingIsTheOnlyCouldNotSearch() {
        var gitLab = SweepingGitLabGateway.where()
                .unreachable(REAL).unreachable(PHANTOM).build();

        var line = gitLabSummaryLine(diagnoseSweep(gitLab));

        assertThat(line).contains("COULD NOT SEARCH");
    }

    /** A partial answer that cannot say WHICH project it missed is honest but useless. */
    @Test
    void aFailureNamesItsProject() {
        var gitLab = SweepingGitLabGateway.where().empty(REAL).unreachable(PHANTOM).build();

        var result = diagnoseSweep(gitLab);

        assertThat(result.report().missingInformation())
                .as("GatewayUnavailableException carries only the system, so the engine must add the project")
                .anyMatch(m -> m.contains(PHANTOM));
    }

    // ---- J31/ASO-4 + FND-89 — the trace state matches what happened ----------------------

    /**
     * FND-89: {@code emitStep} hardcoded {@code DONE}, so no row on this engine could ever
     * resolve {@code FAILED} — {@code StepState.FAILED} appeared only in the ADK engine. The
     * GitLab row said "COULD NOT SEARCH" in its text while its state claimed success, which is
     * exactly what J14/FRI-5 forbids.
     */
    @Test
    void aFailedAttemptResolvesFailedNotDone() {
        var sink = new RecordingTraceSink();
        var gitLab = SweepingGitLabGateway.where().empty(REAL).unreachable(PHANTOM).build();

        new DeterministicDiagnosisEngine(new MockServiceNowGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), gitLab,
                TriagePropertiesFixture.deterministicWithProjects(PHANTOM, REAL))
                .diagnose("INC0010005", sink);

        assertThat(sink.terminalStates("gitlab.searchCode"))
                .as("one row per attempt, and the unreachable one must not claim DONE")
                .contains(StepState.FAILED)
                .contains(StepState.DONE);
    }

    /**
     * The COULD NOT SEARCH line must carry the real term and projects, not the format string.
     *
     * <p>Found during J31's CDS round. {@code "…%s…" + "…".formatted(a, b)} applies
     * {@code formatted} to the SECOND literal only — Java binds the method call tighter than
     * the concatenation — so the placeholders in the first literal were never substituted and
     * the trace read {@code term='%s', projects=%s} verbatim on every degraded run.
     *
     * <p>The sibling tests could not catch it: asserting {@code contains("COULD NOT SEARCH")}
     * passes just as happily on an unformatted string. A test that checks a line is PRESENT
     * says nothing about whether the line is READABLE.
     */
    @Test
    void theCouldNotSearchLineIsActuallyFormatted() {
        var trace = diagnoseWith(unreachable()).trace();

        var line = trace.stream().filter(l -> l.contains("COULD NOT SEARCH")).findFirst().orElseThrow();
        assertThat(line)
                .as("the format placeholders must be substituted, not printed")
                .doesNotContain("%s")
                .contains("term='");
    }

    @Test
    void anUnreachableGitLabSaysSoRatherThanReportingZeroHits() {
        var trace = diagnoseWith(unreachable()).trace();

        assertThat(trace).anyMatch(l -> l.contains("COULD NOT SEARCH"));
        assertThat(trace)
                .as("the ambiguous form must not also appear — one run, one claim")
                .noneMatch(l -> l.contains("hit(s) (log↔code citation)"));
    }

    @Test
    void aGenuinelyEmptySearchStillReportsZeroHits() {
        var trace = diagnoseWith(findsNothing()).trace();

        assertThat(trace)
                .as("searched-and-found-nothing is a real finding and keeps its own wording")
                .anyMatch(l -> l.contains("hit(s)"));
        assertThat(trace).noneMatch(l -> l.contains("COULD NOT SEARCH"));
    }

    @Test
    void theRunSurvivesEitherWay() {
        assertThat(diagnoseWith(unreachable()).report().candidateSystems()).isNotEmpty();
        assertThat(diagnoseWith(findsNothing()).report().candidateSystems()).isNotEmpty();
    }
}
