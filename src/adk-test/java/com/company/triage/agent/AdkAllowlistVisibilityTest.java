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

    private static final List<String> SCOPES = List.of("prod/payment", "prod/order-api");
    private static final List<String> PROJECTS = List.of("order-payments/payment-service");

    private static TriageProperties props() {
        return new TriageProperties(
                TriageProperties.Engine.ADK,
                new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(90000),
                new TriageProperties.Agent(10),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                new TriageProperties.ServiceNow("work_notes"),
                new TriageProperties.Sumo(SCOPES, 20, 30),
                new TriageProperties.GitLab(PROJECTS));
    }

    private static AdkDiagnosisEngine engine() {
        return new AdkDiagnosisEngine(new MockServiceNowGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(), props());
    }

    @Test
    void instructionNamesEveryAllowlistedScopeAndProject() {
        String instruction = engine().instruction();

        // The exact strings the tools will accept — verbatim, so the model never has to guess.
        assertThat(instruction).contains("prod/payment");
        assertThat(instruction).contains("prod/order-api");
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
                new TriageProperties.ServiceNow("work_notes"),
                new TriageProperties.Sumo(List.of("prod/some-other-scope"), 20, 30),
                new TriageProperties.GitLab(List.of("team/other-repo")));

        String instruction = new AdkDiagnosisEngine(new MockServiceNowGateway(),
                new MockConfluenceGateway(), new MockSumoGateway(), new MockGitLabGateway(),
                custom).instruction();

        assertThat(instruction).contains("prod/some-other-scope").contains("team/other-repo");
        assertThat(instruction).doesNotContain("prod/order-api");
    }

    @Test
    void rejectionMessagesNameTheValidValuesSoTheModelCanSelfCorrect() {
        engine();   // wires TriageMateTools with the allowlists above

        assertThatThrownBy(() -> TriageMateTools.searchLogs(
                "prod/not-a-real-scope", "q", "2026-07-31T00:00:00Z", "2026-07-31T00:10:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prod/payment")
                .hasMessageContaining("prod/order-api");

        assertThatThrownBy(() -> TriageMateTools.searchCode("someone/guessed-wrong", "TOKEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order-payments/payment-service");
    }
}
