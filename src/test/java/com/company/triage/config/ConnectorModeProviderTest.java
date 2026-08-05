package com.company.triage.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-016 (J11 §LT7) — {@link ConnectorModeProvider} is the ONLY thing standing between
 * the {@code triage.connectors.*} keys the gateway {@code @ConditionalOnProperty} beans
 * key off and what {@link com.company.triage.orchestration.DiagnosisResult#connectors()}
 * actually reports. This exercises the real {@link org.springframework.core.env.Environment}
 * pipeline (via {@link MockEnvironment}, not a hand-built map), which no prior test does —
 * the existing JSON-contract regression test mocks {@code DiagnosisOrchestrator} entirely
 * and only round-trips {@link DiagnosisResult}'s hardcoded {@code DEFAULT_CONNECTORS}
 * fallback.
 */
class ConnectorModeProviderTest {

    @Test
    void noArgConstructorDefaultsEveryConnectorToMock() {
        Map<String, String> modes = new ConnectorModeProvider().modes();

        assertThat(modes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "servicenow", "mock",
                "confluence", "mock",
                "sumo", "mock",
                "gitlab", "mock"));
    }

    @Test
    void environmentBackedConstructorReadsAllFourConnectorProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("triage.connectors.servicenow", "real")
                .withProperty("triage.connectors.confluence", "real")
                .withProperty("triage.connectors.sumo", "real")
                .withProperty("triage.connectors.gitlab", "real");

        Map<String, String> modes = new ConnectorModeProvider(env).modes();

        assertThat(modes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "servicenow", "real",
                "confluence", "real",
                "sumo", "real",
                "gitlab", "real"));
    }

    @Test
    void missingIndividualPropertiesDefaultToMockEvenWhenOthersAreSet() {
        // Only servicenow is set on the Environment; the other three keys are absent
        // entirely (not just blank) — matches matchIfMissing = true on Mock*Gateway.
        MockEnvironment env = new MockEnvironment()
                .withProperty("triage.connectors.servicenow", "real");

        Map<String, String> modes = new ConnectorModeProvider(env).modes();

        assertThat(modes.get("servicenow")).isEqualTo("real");
        assertThat(modes.get("confluence")).isEqualTo("mock");
        assertThat(modes.get("sumo")).isEqualTo("mock");
        assertThat(modes.get("gitlab")).isEqualTo("mock");
    }

    /**
     * FND-74 — the reported mode must match the bean Spring actually built.
     *
     * <p>{@code @ConditionalOnProperty(havingValue = "real")} matches case-insensitively, so
     * {@code Real} builds the REAL gateway and writes to a live ticket. This provider stored
     * the raw string and the LT7 chip compares it strictly, so the UI labelled that run
     * "fixtures" while it was posting advisory comments to a customer-visible incident.
     */
    @Test
    void connectorModeIsCaseAndWhitespaceInsensitive() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("triage.connectors.servicenow", "Real")
                .withProperty("triage.connectors.confluence", "  REAL  ")
                .withProperty("triage.connectors.sumo", "Mock");

        Map<String, String> modes = new ConnectorModeProvider(env).modes();

        assertThat(modes.get("servicenow")).isEqualTo("real");
        assertThat(modes.get("confluence")).isEqualTo("real");
        assertThat(modes.get("sumo")).isEqualTo("mock");
        assertThat(modes.get("gitlab")).isEqualTo("mock");
    }

    @Test
    void mixedRealAndMockConnectorsAreReportedIndependently() {
        // FND-10: the actual scenario this feature exists for — real and mock are
        // mixable in the same run, not a single all-or-nothing flag.
        MockEnvironment env = new MockEnvironment()
                .withProperty("triage.connectors.servicenow", "real")
                .withProperty("triage.connectors.gitlab", "real");
        // confluence and sumo deliberately left unset -> should default to "mock".

        Map<String, String> modes = new ConnectorModeProvider(env).modes();

        assertThat(modes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "servicenow", "real",
                "confluence", "mock",
                "sumo", "mock",
                "gitlab", "real"));
    }
}
