package com.company.triage.agent;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.mock.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J19/ICF-1, ICF-2, ICF-3, ICF-5 — every bound the prompt states derives from the enforced value.
 *
 * <p>The failure this prevents is quiet and expensive: the app hard-throws on a value outside
 * an allowlist, and each such throw burns one call from a budget the model cannot see. A prompt
 * that names a DIFFERENT value than the one enforced does not produce a wrong answer — it
 * produces a guaranteed exception the model then has to recover from, with less budget.
 *
 * <p>ICF-5 is why these assert the INVARIANT rather than one sample: a test pinned to
 * "prod" would pass on the demo config and still miss the deployment where prod is not an
 * allowed environment, which is the only case where the bug exists.
 */
class InstructionConfigFidelityTest {

    private static AdkDiagnosisEngine engineWith(List<String> environments, List<String> projects) {
        var base = TriagePropertiesFixture.adk();
        var sumo = new TriageProperties.Sumo(
                TriagePropertiesFixture.sumo().sourceCategoryPattern(), java.util.Map.of(),
                TriagePropertiesFixture.sumo().index(), environments, 20, 30);
        var props = new TriageProperties(base.engine(), base.writeback(), base.orchestrator(),
                new TriageProperties.Agent(7), base.trigger(), base.servicenow(), sumo,
                new TriageProperties.GitLab(projects));
        return new AdkDiagnosisEngine(new MockServiceNowGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(), props);
    }

    @Test
    void theDefaultEnvironmentInThePromptIsTheOneTheAppWouldActuallyUse() {
        // The case the old literal got wrong: an estate with no "prod" at all.
        var engine = engineWith(List.of("staging", "uat"), List.of("a/b"));
        var props = new TriageProperties.Sumo("p", java.util.Map.of(), "i",
                List.of("staging", "uat"), 20, 30);

        assertThat(engine.instruction())
                .as("the prompt must name the fallback the app would REALLY use — telling the "
                        + "model 'prod' here sends it to a value the app then rejects, spending "
                        + "a tool call on a guaranteed exception")
                .contains("use " + props.defaultEnvironment())
                .doesNotContain("use prod.");
    }

    @Test
    void prodIsStillTheDefaultWhenItIsAllowed() {
        assertThat(engineWith(List.of("prod", "uat"), List.of("a/b")).instruction())
                .contains("use prod");
    }

    @Test
    void theToolCallBudgetIsDisclosedAsANumber() {
        assertThat(engineWith(List.of("prod"), List.of("a/b")).instruction())
                .as("'limited' is not something a model can budget against, and a denial it "
                        + "could have avoided costs exactly as much as one it could not")
                .contains("You have 7 tool calls");
    }

    @Test
    void theAllowlistsInThePromptAreTheEnforcedOnes() {
        String instruction = engineWith(List.of("staging"), List.of("team/repo-one", "team/repo-two"))
                .instruction();
        assertThat(instruction).contains("staging");
        assertThat(instruction).contains("team/repo-one", "team/repo-two");
    }

    /** ICF-5: no operator-configurable value survives in the prompt as a literal. */
    @Test
    void noConfiguredValueAppearsAsALiteralForAnEstateThatUsesNoneOfTheDefaults() {
        String instruction = engineWith(List.of("staging", "uat"), List.of("team/repo")).instruction();

        assertThat(instruction)
                .as("nothing from the demo config may leak into a deployment that shares none of it")
                .doesNotContain("order-payments/payment-service");
    }
}
