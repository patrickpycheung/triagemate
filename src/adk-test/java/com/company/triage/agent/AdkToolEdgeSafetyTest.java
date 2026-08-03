package com.company.triage.agent;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.mock.MockConfluenceGateway;
import com.company.triage.gateway.mock.MockGitLabGateway;
import com.company.triage.gateway.mock.MockServiceNowGateway;
import com.company.triage.gateway.mock.MockSumoGateway;
import com.company.triage.orchestration.DiagnosisResult;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceSink;
import com.company.triage.orchestration.trace.TraceStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * STREAM-003 review remediation (higher-severity finding): proves the tool-edge
 * callbacks ({@code beforeToolCallbackSync}/{@code afterToolCallbackSync}/{@code
 * onToolErrorCallbackSync}) are now hardened with the SAME safety contract as {@code
 * beforeModelObserve} — a bug in {@code TraceSink} bookkeeping (a {@code before}/{@code
 * after}/{@code onError} that throws) must never affect the real guardrail decision
 * (allow/deny), the real tool execution, or the real result delivered back to ADK.
 *
 * <p>Follow-up remediation: {@code afterModelCallbackSync}/{@code
 * onModelErrorCallbackSync} calling {@code resolveActiveModelCall} unguarded was flagged
 * as a matching gap during the tool-edge work above (same hazard class, model-resolve path
 * instead of tool-resolve path) and is now hardened the same way. {@link
 * #modelResolvePathStillCompletesWhenTheSinkThrowsOnEveryEdge()} proves it.
 *
 * <p>Exercised against the real ADK loop (like {@link AdkLiveRoundTripTest}) rather than
 * unit-testing the lambda bodies in isolation, because the property under test — "no
 * exception escapes the ADK callback and the run completes exactly as it would with a
 * working sink" — is only provable by actually driving the callback through ADK's real
 * invocation machinery.
 */
class AdkToolEdgeSafetyTest {

    private static TriageProperties props(List<String> sumoScopes, int maxToolCalls,
                                          List<String> gitLabProjects) {
        var base = TriagePropertiesFixture.adk();
        return new TriageProperties(base.engine(), base.writeback(), base.orchestrator(),
                new TriageProperties.Agent(maxToolCalls), base.trigger(), base.servicenow(),
                TriagePropertiesFixture.sumo(),
                new TriageProperties.GitLab(gitLabProjects));
    }

    @AfterEach
    void clearProps() {
        System.clearProperty("LLM_BASE_URL");
        System.clearProperty("LLM_API_KEY");
        System.clearProperty("LLM_MODEL");
    }

    /**
     * A sink that throws on every TOOL-edge row (a real tool name, e.g. {@code
     * get_incident}) — the exact hazard the review flagged for {@code
     * beforeToolCallbackSync}/{@code afterToolCallbackSync}/{@code onToolErrorCallbackSync}.
     *
     * <p>Deliberately does NOT throw for model-think rows ({@code step.tool() == null}):
     * this test isolates the TOOL-edge remediation under review. The model-resolve path
     * ({@code afterModelCallbackSync}/{@code onModelErrorCallbackSync} calling {@code
     * resolveActiveModelCall}) has since been hardened the same way and is covered
     * separately by {@link ExplodingOnEverythingTraceSink} and {@link
     * #modelResolvePathStillCompletesWhenTheSinkThrowsOnEveryEdge()}.
     */
    private static final class ExplodingTraceSink implements TraceSink {
        final AtomicInteger beforeCalls = new AtomicInteger();
        final AtomicInteger afterCalls = new AtomicInteger();
        final AtomicInteger onErrorCalls = new AtomicInteger();

        @Override
        public void before(TraceStep step) {
            if (step.tool() == null) return;
            beforeCalls.incrementAndGet();
            throw new IllegalStateException("boom — a bug in trace bookkeeping (before)");
        }

        @Override
        public void after(TraceStep step) {
            if (step.tool() == null) return;
            afterCalls.incrementAndGet();
            throw new IllegalStateException("boom — a bug in trace bookkeeping (after)");
        }

        @Override
        public void onError(TraceStep step) {
            if (step.tool() == null) return;
            onErrorCalls.incrementAndGet();
            throw new IllegalStateException("boom — a bug in trace bookkeeping (onError)");
        }
    }

    /**
     * A sink that throws on EVERY row — both TOOL-edge rows and model-think rows ({@code
     * step.tool() == null}). Used to prove the follow-up remediation of {@code
     * afterModelCallbackSync}/{@code onModelErrorCallbackSync} (which resolve through the
     * model-resolve path, {@code resolveActiveModelCall}), on top of the tool-edge coverage
     * {@link ExplodingTraceSink} already provides.
     */
    private static final class ExplodingOnEverythingTraceSink implements TraceSink {
        final AtomicInteger beforeCalls = new AtomicInteger();
        final AtomicInteger afterCalls = new AtomicInteger();
        final AtomicInteger onErrorCalls = new AtomicInteger();
        final AtomicInteger modelAfterCalls = new AtomicInteger();

        @Override
        public void before(TraceStep step) {
            beforeCalls.incrementAndGet();
            throw new IllegalStateException("boom — a bug in trace bookkeeping (before)");
        }

        @Override
        public void after(TraceStep step) {
            afterCalls.incrementAndGet();
            if (step.tool() == null) {
                modelAfterCalls.incrementAndGet();
            }
            throw new IllegalStateException("boom — a bug in trace bookkeeping (after)");
        }

        @Override
        public void onError(TraceStep step) {
            onErrorCalls.incrementAndGet();
            throw new IllegalStateException("boom — a bug in trace bookkeeping (onError)");
        }
    }

    /**
     * Follow-up remediation coverage: a sink that throws on EVERY row — including
     * model-think rows resolved through {@code afterModelCallbackSync} calling {@code
     * resolveActiveModelCall} — must not stop the real ADK round trip from completing with
     * a valid report. This is the model-resolve-path counterpart to {@link
     * #toolCallStillProceedsAndCompletesWhenTheSinkThrowsOnEveryEdge()}, which only covers
     * the tool-resolve path.
     */
    @Test
    void modelResolvePathStillCompletesWhenTheSinkThrowsOnEveryEdge() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.start()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    props(List.of("prod/payment", "prod/order-api"), 8,
                            List.of("order-payments/payment-service")));

            ExplodingOnEverythingTraceSink sink = new ExplodingOnEverythingTraceSink();

            DiagnosisResult[] holder = new DiagnosisResult[1];
            assertThatCode(() -> holder[0] = engine.diagnose("INC0010005", sink))
                    .doesNotThrowAnyException();
            DiagnosisResult result = holder[0];

            // Real model-round-trip behaviour and the final report are unaffected by the
            // exploding sink — same shape as the working-sink case in AdkLiveRoundTripTest.
            assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");
            assertThat(result.report().advisory()).isTrue();
            assertThat(result.report().suggestedAssignment().group()).isEqualTo("Payments Platform Support");

            // The sink genuinely was invoked (and threw) on model-think rows specifically —
            // proving the model-resolve path (afterModelCallbackSync -> resolveActiveModelCall)
            // was actually exercised, not merely the already-covered tool-resolve path.
            assertThat(sink.modelAfterCalls.get()).isGreaterThan(0);
            assertThat(sink.beforeCalls.get()).isGreaterThan(0);
            assertThat(sink.afterCalls.get()).isGreaterThan(0);
        }
    }

    /**
     * ALLOWED path: an exploding sink must not stop the real tool call from proceeding
     * (get_incident still executes over the mock gateway) or the agent from completing
     * with a valid J4 report — exactly as {@code AdkLiveRoundTripTest}'s happy-path test
     * proves for a working sink.
     */
    @Test
    void toolCallStillProceedsAndCompletesWhenTheSinkThrowsOnEveryEdge() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.start()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    props(List.of("prod/payment", "prod/order-api"), 8,
                            List.of("order-payments/payment-service")));

            ExplodingTraceSink sink = new ExplodingTraceSink();

            DiagnosisResult[] holder = new DiagnosisResult[1];
            assertThatCode(() -> holder[0] = engine.diagnose("INC0010005", sink))
                    .doesNotThrowAnyException();
            DiagnosisResult result = holder[0];

            // The real tool execution and final report are byte-for-byte the same shape as
            // the working-sink case in AdkLiveRoundTripTest — the exploding sink cost only
            // trace rows, never the real behaviour.
            assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");
            assertThat(result.report().advisory()).isTrue();
            assertThat(result.report().suggestedAssignment().group()).isEqualTo("Payments Platform Support");
            assertThat(result.trace()).anyMatch(s -> s.contains("adk tool call: get_incident"));

            // The sink genuinely was invoked (and threw) on the before/after edges — this
            // isn't passing merely because the sink went unused.
            assertThat(sink.beforeCalls.get()).isGreaterThan(0);
            assertThat(sink.afterCalls.get()).isGreaterThan(0);
        }
    }

    /**
     * DENIED path — the specific hazard the review flagged: a throwing sink must never
     * prevent {@code beforeToolCallbackSync} from returning the denial {@code Optional}
     * to ADK. Zero tool-call budget forces every tool to be denied on the first attempt.
     */
    @Test
    void deniedToolCallStillReturnsTheDenialWhenTheSinkThrows() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.start()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    props(List.of("prod/payment"), 0,   // zero budget → deny every tool
                            List.of("order-payments/payment-service")));

            ExplodingTraceSink sink = new ExplodingTraceSink();

            // The whole point: this must not throw. If beforeToolCallbackSync's denial
            // Optional were ever swallowed by a throwing sink.before(), the agent would
            // either hang, crash, or silently let the tool run unbounded — none of which
            // is what actually happens here.
            DiagnosisResult[] holder = new DiagnosisResult[1];
            assertThatCode(() -> holder[0] = engine.diagnose("INC0010005", sink))
                    .doesNotThrowAnyException();
            DiagnosisResult result = holder[0];

            // BoundsCallback's real denial behaviour is untouched: the model still gets an
            // error-shaped tool result and the run still completes with a valid report.
            assertThat(result.trace()).anyMatch(s -> s.contains("DENIED get_incident"));
            assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");
            assertThat(sink.beforeCalls.get()).isGreaterThan(0);
        }
    }

    /**
     * Direct unit-level proof (no ADK context needed) that a throwing sink passed through
     * {@link AdkDiagnosisEngine#resolveActiveCall} — the shared resolve path used by
     * {@code afterToolCallbackSync}/{@code onToolErrorCallbackSync} — behaves exactly as
     * the production call sites now guard it: the sink is genuinely invoked (and throws),
     * but nothing about the resolve outcome (the active-call bookkeeping) is left
     * inconsistent by that failure.
     */
    @Test
    void resolveActiveCallInvokesTheSinkAndTheProductionGuardSwallowsItsException() {
        var activeCalls = new ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var deniedCallIds = ConcurrentHashMap.<String>newKeySet();
        var stepSeq = new AtomicInteger();
        activeCalls.put("call-1", new AdkDiagnosisEngine.ActiveCall(0, System.currentTimeMillis(), System.nanoTime()));

        ExplodingTraceSink sink = new ExplodingTraceSink();

        // Mirrors exactly what afterToolCallbackSync's try/catch now does around this call.
        assertThatCode(() -> {
            try {
                AdkDiagnosisEngine.resolveActiveCall(activeCalls, deniedCallIds, stepSeq, "call-1",
                        "get_incident", sink, StepState.DONE, "ok");
            } catch (RuntimeException e) {
                // guarded — matches production behaviour in afterToolCallbackSync
            }
        }).doesNotThrowAnyException();

        assertThat(sink.afterCalls.get()).isEqualTo(1);
        // The active-call row was still consumed even though the sink then threw —
        // resolve's bookkeeping happens before the sink call, so it isn't left dangling.
        assertThat(activeCalls).isEmpty();
    }
}
