package com.company.triage.agent;

import com.company.triage.config.TriageProperties;
import com.company.triage.gateway.mock.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FND-60: {@code search_logs} and {@code search_code} hard-throw on a value outside their J8
 * allowlist (FND-20/38), but those allowlisted values appeared nowhere the model could see
 * them — not in the instruction, not in any {@code @Schema} description, and there is no
 * discovery tool. The model had to guess the exact strings, and the incident's own fields
 * don't contain them: the demo incident's {@code cmdb_ci} is "Order Portal" while the
 * allowlisted project is "order-payments/payment-service", underivable from one another.
 * Every guess burned a tool call from the J8 budget on a guaranteed exception.
 *
 * <p>These tests pin both halves of the fix: the instruction names the values up front, and
 * the rejection message names them too so a model that still gets it wrong can self-correct
 * within its remaining budget.
 */
class AdkAllowlistVisibilityTest {

    private static final List<String> ENVIRONMENTS = List.of("pdev", "ptest", "stest", "vtest", "prod");
    private static final List<String> PROJECTS = List.of("order-payments/payment-service");

    private static TriageProperties props() {
        return new TriageProperties(
                TriageProperties.Engine.ADK,
                new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(90000),
                new TriageProperties.Agent(10),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                new TriageProperties.ServiceNow("work_notes", java.util.Map.of()),
                com.company.triage.config.TriagePropertiesFixture.sumo(),
                new TriageProperties.GitLab(PROJECTS));
    }

    private static AdkDiagnosisEngine engine() {
        return new AdkDiagnosisEngine(new MockServiceNowGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(), props());
    }

    @Test
    void instructionNamesEveryAllowlistedEnvironmentAndProject() {
        String instruction = engine().instruction();

        // The exact strings the tools will accept — verbatim, so the model never has to guess.
        ENVIRONMENTS.forEach(env -> assertThat(instruction).contains(env));
        assertThat(instruction).contains("order-payments/payment-service");
    }

    /**
     * The allowlists must come from config, not be hardcoded a second time in the prompt —
     * otherwise enforcement and disclosure drift into "rejected for a value we never told you
     * about", which is the original bug wearing a different hat.
     */
    @Test
    void instructionReflectsConfiguredAllowlistsNotHardcodedDefaults() {
        var custom = new TriageProperties(
                TriageProperties.Engine.ADK,
                new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(90000),
                new TriageProperties.Agent(10),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                new TriageProperties.ServiceNow("work_notes", java.util.Map.of()),
                new TriageProperties.Sumo("Custom/{project}/{environment}", java.util.Map.of(),
                        "Custom_Index", List.of("sandbox"), 20, 30),
                new TriageProperties.GitLab(List.of("team/other-repo")));

        String instruction = new AdkDiagnosisEngine(new MockServiceNowGateway(),
                new MockConfluenceGateway(), new MockSumoGateway(), new MockGitLabGateway(),
                custom).instruction();

        assertThat(instruction).contains("sandbox").contains("team/other-repo");
        assertThat(instruction).doesNotContain("pdev");
    }

    /**
     * FND-66: the first real Copilot-served run's repair retry died on
     * {@code Cannot deserialize value of type double from String "HIGH"} — and that was our
     * fault, not the model's. The schema block showed {@code suggestedAssignment.confidence}
     * as {@code "LOW|MEDIUM|HIGH"} while the {@code candidateSystems[].confidence} right
     * above it had no type hint at all, so the model reasonably assumed the two identically
     * named fields held the same kind of value. One is a 0.0–1.0 double, the other an enum.
     */
    @Test
    void instructionDisambiguatesTheTwoDifferentConfidenceFields() {
        String instruction = engine().instruction();

        assertThat(instruction).contains("candidateSystems[].confidence  is a NUMBER");
        assertThat(instruction).contains("\"confidence\":<NUMBER 0.0-1.0>");
        assertThat(instruction).contains("confidenceOverall are the STRING");
    }

    /** FND-66: fencing is so deeply trained that the instruction must say so outright. */
    @Test
    void instructionForbidsMarkdownCodeFences() {
        assertThat(engine().instruction()).contains("NO markdown code fence");
    }

    @Test
    void rejectionMessagesNameTheValidValuesSoTheModelCanSelfCorrect() {
        engine();   // wires TriageMateTools with the allowlists above

        assertThatThrownBy(() -> TriageMateTools.searchLogs(
                "delivery-hazards", "not-an-environment", "q",
                "2026-07-31T00:00:00Z", "2026-07-31T00:10:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pdev")
                .hasMessageContaining("prod");

        assertThatThrownBy(() -> TriageMateTools.searchCode("someone/guessed-wrong", "TOKEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order-payments/payment-service");
    }
}
