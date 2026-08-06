package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
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
