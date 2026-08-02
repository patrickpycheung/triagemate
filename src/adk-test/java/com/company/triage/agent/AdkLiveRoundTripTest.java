package com.company.triage.agent;

import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.mock.*;
import com.company.triage.orchestration.DiagnosisResult;
import com.company.triage.orchestration.trace.Platform;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceStep;
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

    private static TriageProperties props(List<String> sumoScopes, int maxResults, int maxWindowMinutes,
                                          int maxToolCalls, List<String> gitLabProjects) {
        var base = TriagePropertiesFixture.adk();
        return new TriageProperties(base.engine(), base.writeback(), base.orchestrator(),
                new TriageProperties.Agent(maxToolCalls), base.trigger(), base.servicenow(),
                new TriageProperties.Sumo(sumoScopes, maxResults, maxWindowMinutes),
                new TriageProperties.GitLab(gitLabProjects));
    }

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
                    props(List.of("prod/payment", "prod/order-api"), 20, 30, 8,
                            List.of("order-payments/payment-service")));

            TraceCollector collector = new TraceCollector();
            DiagnosisResult result = engine.diagnose("INC0010005", collector.forAttempt(0));

            // Final JSON parsed into the J4 contract
            assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");
            assertThat(result.report().advisory()).isTrue();
            assertThat(result.report().suggestedAssignment().group()).isEqualTo("Payments Platform Support");

            // The bounds callback observed the tool call (get_incident)
            assertThat(result.trace()).anyMatch(s -> s.contains("adk tool call: get_incident"));

            // FND-65 / LT4 latency spike: every tool-call and finish line carries elapsed
            // timing, so a real run against the corp-laptop Copilot proxy produces per-step
            // latency in the trace itself — no console-log correlation needed.
            var timingPattern = java.util.regex.Pattern.compile("\\[t=\\d+ms, \\+\\d+ms]");
            assertThat(result.trace())
                    .filteredOn(s -> s.startsWith("adk tool call:") || s.startsWith("adk agent finished:"))
                    .isNotEmpty()
                    .allMatch(s -> timingPattern.matcher(s).find());

            // TASK-006 (J11 LT4): the beforeToolCallbackSync/afterToolCallbackSync edges
            // resolved the get_incident row all the way to DONE, with a real (non-null)
            // duration and the real tool result carried into `result` — not a placeholder.
            List<TraceStep> steps = collector.steps();
            assertThat(steps).anySatisfy(step -> {
                assertThat(step.tool()).isEqualTo("get_incident");
                assertThat(step.platform()).isEqualTo(Platform.SERVICENOW);
                assertThat(step.state()).isEqualTo(StepState.DONE);
                assertThat(step.durationMs()).isNotNull();
                assertThat(step.result()).isNotBlank();
                assertThat(step.callId()).isNotBlank();
            });
            // Exactly one row per callId — the DONE replacement overwrote the ACTIVE row,
            // it did not append a second one (TraceSink's replace-by-callId contract).
            assertThat(steps).filteredOn(step -> "get_incident".equals(step.tool())).hasSize(1);

            // TASK-007 (J11 LT4, model edges): this fake server's script is exactly two
            // LLM turns — turn 1 chooses get_incident, turn 2 (after the tool result) is
            // the final report with no tool call — so retrospective labelling must produce
            // ONE "chose get_incident" row and ONE "produced the report" row, verified
            // against this live round trip rather than assumed from the callback ordering.
            List<TraceStep> modelRows = steps.stream()
                    .filter(step -> step.platform() == Platform.TRIAGEMATE)
                    .filter(step -> "Thinking…".equals(step.label()))
                    .toList();
            assertThat(modelRows).hasSize(2);
            assertThat(modelRows).allMatch(step -> step.tool() == null);
            assertThat(modelRows).allMatch(step -> step.state() == StepState.DONE);
            assertThat(modelRows).allMatch(step -> step.durationMs() != null);
            assertThat(modelRows).anyMatch(step -> "chose get_incident".equals(step.result()));
            assertThat(modelRows).anyMatch(step -> "produced the report".equals(step.result()));
            // Each model row's callId is its own eventId — distinct from get_incident's
            // functionCallId-based callId, and distinct from each other (no collapsing).
            assertThat(modelRows).extracting(TraceStep::callId).doesNotHaveDuplicates();
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
                    props(List.of("prod/payment"), 20, 30, 0,   // zero budget → deny every tool
                            List.of("order-payments/payment-service")));

            TraceCollector collector = new TraceCollector();
            DiagnosisResult result = engine.diagnose("INC0010005", collector.forAttempt(0));

            assertThat(result.trace()).anyMatch(s -> s.contains("DENIED get_incident"));
            // Even with tools denied, the agent still returns a parseable report — proves
            // BoundsCallback's actual denial behaviour (the model gets an error map back
            // and still completes) is completely untouched by wiring the trace observer
            // onto the same before edge.
            assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");

            // TASK-006: a denied call still gets a DENIED TraceStep, with BoundsCallback's
            // own denial reason carried into `result` — composed onto the before edge, not
            // replacing what BoundsCallback itself returned to short-circuit the call.
            List<TraceStep> steps = collector.steps();
            assertThat(steps).anySatisfy(step -> {
                assertThat(step.tool()).isEqualTo("get_incident");
                assertThat(step.platform()).isEqualTo(Platform.SERVICENOW);
                assertThat(step.state()).isEqualTo(StepState.DENIED);
                assertThat(step.result()).contains("max tool calls (0) exceeded");
            });
            // verification-adk-callbacks.md's residual uncertainty, now settled empirically:
            // whether afterToolCallback fires for a callId the before edge already denied.
            // Whatever the answer, the DENIED row must survive to the end — never silently
            // overwritten into a DONE row sharing the same callId.
            assertThat(steps)
                    .filteredOn(step -> "get_incident".equals(step.tool()))
                    .hasSize(1)
                    .allMatch(step -> step.state() == StepState.DENIED);
        }
    }

    /**
     * A real thrown exception (not a fake/timeout) from inside a live ADK tool call —
     * {@link MockServiceNowGateway#getIncident} throws {@code IncidentNotFoundException}
     * for any incident number other than its one seeded {@code INC0010005}, reached here
     * because {@code get_incident} takes no model-supplied arguments — it always reads
     * whatever incident number {@code diagnose} bound for this run (FND-33).
     *
     * <p><b>Empirical finding (this task), correcting an assumption in
     * verification-adk-callbacks.md:</b> for a tool registered via {@code FunctionTool
     * .create(...)} (every TriageMate tool), ADK 1.7.0's {@code FunctionTool.runAsync}
     * itself catches the reflective invocation's exception and resolves the call
     * SUCCESSFULLY with an error-shaped {@code {status=error, message=...}} result —
     * {@code afterToolCallbackSync} fires, {@code onToolErrorCallbackSync} does not. The
     * DDS spike's "before is not a finally hook, a thrown tool call only reaches
     * onToolErrorCallback" holds for callback SIGNATURES (confirmed by javap) but not, it
     * turns out, for FunctionTool's actual exception handling — a distinction the spike's
     * bytecode-only verification could not have surfaced. This still exercises {@code
     * afterToolCallbackSync}'s real-result path end to end: the row still resolves DONE,
     * with the tool's honest error content carried into {@code result}, not lost.
     */
    @Test
    void toolThrowIsCaughtByFunctionToolAndSurfacesAsAnHonestDoneResult() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.start()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    props(List.of("prod/payment", "prod/order-api"), 20, 30, 8,
                            List.of("order-payments/payment-service")));

            TraceCollector collector = new TraceCollector();
            // Not the mock's one seeded incident — get_incident() throws IncidentNotFoundException,
            // which FunctionTool catches (see class doc above) and turns into an error-shaped result.
            engine.diagnose("INC-DOES-NOT-EXIST", collector.forAttempt(0));

            List<TraceStep> steps = collector.steps();
            assertThat(steps).anySatisfy(step -> {
                assertThat(step.tool()).isEqualTo("get_incident");
                assertThat(step.platform()).isEqualTo(Platform.SERVICENOW);
                assertThat(step.state()).isEqualTo(StepState.DONE);
                assertThat(step.durationMs()).isNotNull();
                assertThat(step.result()).contains("error");
            });
            assertThat(steps)
                    .filteredOn(step -> "get_incident".equals(step.tool()))
                    .hasSize(1);
        }
    }

    /**
     * {@code onToolErrorCallbackSync} is required per the LT4 design note ({@code after}
     * is explicitly NOT a {@code finally} hook) even though the test above shows it is
     * unreachable for TriageMate's FunctionTool-wrapped tools on this ADK version — a
     * future ADK version, or a non-FunctionTool {@code BaseTool}, may still route a
     * failure there, and the resolve logic must be correct when it does. Exercises the
     * SAME {@code resolveActiveCall} method the real {@code onToolErrorCallbackSync}
     * lambda in {@code AdkDiagnosisEngine} delegates to, directly and without any ADK
     * context object — that method deliberately takes a plain {@code callId} (see its
     * javadoc) so this case doesn't need to fight ADK's {@code InvocationContext}
     * construction to prove FAILED resolution end to end.
     */
    @Test
    void onToolErrorCallbackResolvesActiveCallToFailed() {
        var activeCalls = new java.util.concurrent.ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var deniedCallIds = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var stepSeq = new java.util.concurrent.atomic.AtomicInteger();
        var collector = new TraceCollector();
        var sink = collector.forAttempt(0);

        // The row beforeToolCallbackSync would have recorded for this call.
        activeCalls.put("call-err-1", new AdkDiagnosisEngine.ActiveCall(0, System.currentTimeMillis(),
                System.nanoTime()));

        Exception thrown;
        try {
            new MockServiceNowGateway().getIncident("INC-DOES-NOT-EXIST");
            throw new AssertionError("expected getIncident to throw");
        } catch (Exception e) {
            thrown = e; // a genuinely thrown exception, not a fabricated string
        }

        AdkDiagnosisEngine.resolveActiveCall(activeCalls, deniedCallIds, stepSeq, "call-err-1",
                "get_incident", sink, StepState.FAILED, String.valueOf(thrown.getMessage()));

        List<TraceStep> steps = collector.steps();
        assertThat(steps).hasSize(1);
        TraceStep step = steps.get(0);
        assertThat(step.tool()).isEqualTo("get_incident");
        assertThat(step.platform()).isEqualTo(Platform.SERVICENOW);
        assertThat(step.state()).isEqualTo(StepState.FAILED);
        assertThat(step.result()).contains("INC-DOES-NOT-EXIST");
        assertThat(step.callId()).isEqualTo("call-err-1");
        // The row is gone from activeCalls — resolved, not left dangling.
        assertThat(activeCalls).isEmpty();
    }

    /**
     * FND-42: a malformed final response gets exactly one repair retry, on the SAME
     * session (the fake server's second "final" turn only returns valid JSON after
     * the tool-result turn has already happened once — a fresh session would restart
     * at the tool-call turn, not the malformed-final turn, so this also proves the
     * retry reuses context rather than re-investigating).
     *
     * <p>TASK-007 (J11 LT4, model edges): also proves the free side-effect the design note
     * calls out — a repair retry is itself a model call with no tool call, so it naturally
     * produces a SECOND {@code "Thinking…"} row through the same beforeModel/afterModel
     * mechanism, with no special-case code for FND-42 anywhere in {@code
     * AdkDiagnosisEngine}. Switched from the single-arg {@code diagnose(incidentNumber)} to
     * the {@code TraceSink}-carrying overload so this can be verified against a live round
     * trip rather than just assumed.
     */
    @Test
    void malformedFinalResponseGetsOneRepairRetryThenSucceeds() throws Exception {
        try (FakeOpenAiServer fake = FakeOpenAiServer.startWithOneMalformedFinalResponse()) {
            System.setProperty("LLM_BASE_URL", "http://127.0.0.1:" + fake.port() + "/v1");
            System.setProperty("LLM_API_KEY", "test-key");
            System.setProperty("LLM_MODEL", "fake");

            AdkDiagnosisEngine engine = new AdkDiagnosisEngine(
                    new MockServiceNowGateway(), new MockConfluenceGateway(),
                    new MockSumoGateway(), new MockGitLabGateway(),
                    props(List.of("prod/payment", "prod/order-api"), 20, 30, 8,
                            List.of("order-payments/payment-service")));

            TraceCollector collector = new TraceCollector();
            DiagnosisResult result = engine.diagnose("INC0010005", collector.forAttempt(0));

            assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");
            assertThat(result.trace()).anyMatch(s -> s.contains("one repair retry (FND-42)"));

            // TASK-007: two model-think rows for the two LLM calls after the tool call —
            // the first malformed "final" turn and the repair retry — neither of which
            // called a tool, so BOTH resolve to "produced the report", not just one.
            List<TraceStep> modelRows = collector.steps().stream()
                    .filter(step -> step.platform() == Platform.TRIAGEMATE)
                    .filter(step -> "Thinking…".equals(step.label()))
                    .toList();
            assertThat(modelRows).hasSizeGreaterThanOrEqualTo(2);
            assertThat(modelRows).allMatch(step -> step.state() == StepState.DONE);
            assertThat(modelRows).allMatch(step -> step.result() != null);
            // Every model row here resolves "produced the report": the tool-call turn's
            // model row chose get_incident (asserted separately below in the general
            // round-trip test), but BOTH of these final-turn rows — the malformed one and
            // the repair retry — had no tool call in their response, retrospectively.
            assertThat(modelRows).filteredOn(step -> "produced the report".equals(step.result()))
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }
}
