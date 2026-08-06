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
