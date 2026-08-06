package com.company.triage.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-87 — proves <b>Spring</b> builds {@link ConnectorModeProvider} from the constructor that
 * reads the {@link org.springframework.core.env.Environment}.
 *
 * <p><b>Why this test exists rather than another constructor test.</b>
 * {@link ConnectorModeProviderTest} calls {@code new ConnectorModeProvider()} and
 * {@link com.company.triage.orchestration.DiagnosisOrchestratorConnectorModesTest} calls
 * {@code new ConnectorModeProvider(env)} — between them every line of the class is covered, and
 * both stayed green for the entire life of the bug. Neither could see it, because the defect
 * was not in either constructor: it was in <b>which one Spring chose</b>. A component with two
 * public constructors and no {@code @Autowired} resolves to the no-arg one, so the container
 * silently built the all-mock instance while the real gateways were wired and calling live
 * systems.
 *
 * <p>This is J20/STV-5's rule — "mechanism tests, not annotation tests" — applied to
 * constructor selection: assert the container's behaviour, never our own {@code new}.
 */
class ConnectorModeProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConnectorModeProvider.class);

    @Test
    void springReadsTheConnectorModesFromTheEnvironment() {
        runner.withPropertyValues(
                        "triage.connectors.servicenow=real",
                        "triage.connectors.confluence=real",
                        "triage.connectors.sumo=mock",
                        "triage.connectors.gitlab=mock")
                .run(ctx -> assertThat(ctx.getBean(ConnectorModeProvider.class).modes())
                        .as("Spring picked the no-arg constructor, so every mode reads 'mock' "
                                + "however the connectors are actually configured (FND-87)")
                        .containsEntry("servicenow", "real")
                        .containsEntry("confluence", "real")
                        .containsEntry("sumo", "mock")
                        .containsEntry("gitlab", "mock"));
    }

    /** FND-10: real and mock are mixable, and the banner must show the mix, not a summary. */
    @Test
    void aPartialMixIsReportedPerConnector() {
        runner.withPropertyValues("triage.connectors.servicenow=real")
                .run(ctx -> assertThat(ctx.getBean(ConnectorModeProvider.class).modes())
                        .containsEntry("servicenow", "real")
                        .containsEntry("confluence", "mock"));
    }

    /** With nothing configured the default still holds — matching {@code matchIfMissing=true}. */
    @Test
    void defaultsToMockWhenNothingIsConfigured() {
        runner.run(ctx -> assertThat(ctx.getBean(ConnectorModeProvider.class).modes().values())
                .containsOnly("mock"));
    }
}
