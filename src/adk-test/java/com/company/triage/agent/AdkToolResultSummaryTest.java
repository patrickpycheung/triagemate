package com.company.triage.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace row's result text is read by a human watching a demo, not by a developer
 * reading a debug log.
 *
 * <p>This used to be {@code String.valueOf(result)}, which dumped ADK's raw wrapper map
 * onto the screen — an empty search rendered as the literal {@code {result=[]}}. These
 * tests pin the two properties that matter: the wrapper never leaks, and an empty result
 * reads as a plain-English "nothing found" rather than empty-collection syntax.
 */
class AdkToolResultSummaryTest {

    @Test
    void emptyResultReadsAsPlainEnglishNotEmptyCollectionSyntax() {
        // The exact shape that produced "{result=[]}" on screen.
        String summary = AdkDiagnosisEngine.summariseToolResult(
                "search_logs", Map.of("result", List.of()));

        assertThat(summary).isEqualTo("nothing found");
        assertThat(summary).doesNotContain("[", "]", "{", "}", "result=");
    }

    @Test
    void populatedResultIsCountedAndNamedForTheToolThatReturnedIt() {
        assertThat(AdkDiagnosisEngine.summariseToolResult(
                "search_logs", Map.of("result", List.of("a", "b", "c", "d"))))
                .isEqualTo("4 log lines");

        // Singular is not "1 log lines".
        assertThat(AdkDiagnosisEngine.summariseToolResult(
                "search_code", Map.of("result", List.of("hit"))))
                .isEqualTo("1 code match");

        assertThat(AdkDiagnosisEngine.summariseToolResult(
                "find_recent_committers", Map.of("result", List.of("x", "y"))))
                .isEqualTo("2 names");
    }

    @Test
    void ownershipLookupNamesTheGroupOrSaysThereIsNone() {
        assertThat(AdkDiagnosisEngine.summariseToolResult(
                "find_ownership", Map.of("result", Map.of("supportGroup", "Payments Platform Support"))))
                .isEqualTo("owner: Payments Platform Support");

        // find_ownership's own "no answer" sentinel must not surface as the word "unknown"
        // dressed up as a real owner.
        assertThat(AdkDiagnosisEngine.summariseToolResult(
                "find_ownership", Map.of("result", Map.of("supportGroup", "unknown"))))
                .isEqualTo("no owner recorded");
    }

    @Test
    void unwrappedAndNullResultsDegradeHonestlyRatherThanThrowing() {
        // A future ADK version that stops wrapping in {result=...} must keep working.
        assertThat(AdkDiagnosisEngine.summariseToolResult("search_confluence", List.of("p1", "p2")))
                .isEqualTo("2 pages");

        assertThat(AdkDiagnosisEngine.summariseToolResult("get_incident", null))
                .isEqualTo("no result");

        // An unrecognised shape is described, never guessed at.
        assertThat(AdkDiagnosisEngine.summariseToolResult("get_incident", Map.of("result", "INC0010005")))
                .isEqualTo("found 1 incident");
    }
}
