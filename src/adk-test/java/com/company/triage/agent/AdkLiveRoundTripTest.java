package com.company.triage.agent;

import com.company.triage.gateway.mock.*;
import com.company.triage.orchestration.DiagnosisResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JS-1b live-half proof: runs the real ADK {@link AdkDiagnosisEngine} loop against a
 * local {@link FakeOpenAiServer} (OpenAI-compatible). Proves tool-calling, tool
 * execution over the mock gateways, the bounds callback, and final J4 parsing —
 * without a real LLM. Compiles/runs only with {@code mvn -Padk test}.
 */
class AdkLiveRoundTripTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("LLM_BASE_URL");
        System.clearProperty("LLM_API_KEY");
        System.clearProperty("LLM_MODEL");
    }

    @Test
    void agentCompletesOneToolRoundTripAndReturnsAValidReport() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.start()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    List.of("prod/payment", "prod/order-api"), 20, 30, 8,
                    List.of("order-payments/payment-service"));

            DiagnosisResult result = engine.diagnose("INC0012345");

            // Final JSON parsed into the J4 contract
            assertThat(result.report().incidentNumber()).isEqualTo("INC0012345");
            assertThat(result.report().advisory()).isTrue();
            assertThat(result.report().suggestedAssignment().group()).isEqualTo("Payments Platform Support");

            // The bounds callback observed the tool call (get_incident)
            assertThat(result.trace()).anyMatch(s -> s.contains("adk tool call: get_incident"));
        }
    }

    @Test
    void boundsCallbackDeniesToolsWhenBudgetIsZero() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.start()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    List.of("prod/payment"), 20, 30, 0,   // zero budget → deny every tool
                    List.of("order-payments/payment-service"));

            DiagnosisResult result = engine.diagnose("INC0012345");

            assertThat(result.trace()).anyMatch(s -> s.contains("DENIED get_incident"));
            // Even with tools denied, the agent still returns a parseable report.
            assertThat(result.report().incidentNumber()).isEqualTo("INC0012345");
        }
    }
}
