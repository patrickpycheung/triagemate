package com.company.triage.agent;

import com.company.triage.orchestration.trace.Platform;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceSink;
import com.company.triage.orchestration.trace.TraceStep;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TASK-007 (J11 LT4, model edges): unit-level proof of the two hardest requirements in
 * the task —
 * <ol>
 *   <li>{@code beforeModelCallbackSync}'s observer returns {@code Optional.empty()}
 *       UNCONDITIONALLY, even when its own bookkeeping throws — the single most important
 *       acceptance criterion in this task (see {@link AdkDiagnosisEngine#beforeModelObserve}
 *       javadoc for why: anything else silently substitutes text the model never produced,
 *       with no {@code DENIED}-style row to reveal it).</li>
 *   <li>Retrospective labelling ({@link AdkDiagnosisEngine#describeModelOutcome}) reads the
 *       real {@link LlmResponse} rather than predicting the outcome.</li>
 * </ol>
 * Exercises {@code AdkDiagnosisEngine}'s package-private static helpers directly — the same
 * "no ADK context object needed" pattern {@code onToolErrorCallbackResolvesActiveCallToFailed}
 * already established for the tool edges, since {@code CallbackContext} construction requires
 * ADK's {@code InvocationContext} machinery this test has no need to fight.
 */
class AdkModelEdgeTest {

    // ---- Requirement 3: beforeModelCallback observer returns Optional.empty() UNCONDITIONALLY ----

    @Test
    void beforeModelObserveReturnsEmptyOnTheHappyPath() {
        var activeModelCalls = new ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var stepSeq = new AtomicInteger();
        var collector = new TraceCollector();

        Optional<LlmResponse> returned = AdkDiagnosisEngine.beforeModelObserve(
                activeModelCalls, stepSeq, collector.forAttempt(0), "event-1");

        assertThat(returned).isEmpty();
        // The row was still recorded — the safety property doesn't come at the cost of
        // the trace actually working on the happy path.
        assertThat(collector.steps()).hasSize(1);
        assertThat(activeModelCalls).containsKey("event-1");
    }

    @Test
    void beforeModelObserveReturnsEmptyEvenWhenTheSinkThrows() {
        var activeModelCalls = new ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var stepSeq = new AtomicInteger();
        TraceSink explodingSink = new TraceSink() {
            @Override public void before(TraceStep step) {
                throw new IllegalStateException("boom — a bug in trace emission");
            }
            @Override public void after(TraceStep step) { }
            @Override public void onError(TraceStep step) { }
        };

        Optional<LlmResponse> returned = AdkDiagnosisEngine.beforeModelObserve(
                activeModelCalls, stepSeq, explodingSink, "event-2");

        // The CRITICAL property: no exception escapes, and the return value is STILL
        // Optional.empty() — a throwing sink must never turn into a substituted response.
        assertThat(returned).isEmpty();
    }

    @Test
    void beforeModelObserveReturnsEmptyEvenWhenTheActiveCallsMapThrows() {
        // A Map whose put() throws — simulates "internal state" going wrong in the
        // bookkeeping this method does before it ever touches the sink.
        Map<String, AdkDiagnosisEngine.ActiveCall> explodingMap = new ConcurrentHashMap<>() {
            @Override public AdkDiagnosisEngine.ActiveCall put(String key, AdkDiagnosisEngine.ActiveCall value) {
                throw new IllegalStateException("boom — a bug in internal bookkeeping");
            }
        };
        var stepSeq = new AtomicInteger();
        var collector = new TraceCollector();

        Optional<LlmResponse> returned = AdkDiagnosisEngine.beforeModelObserve(
                explodingMap, stepSeq, collector.forAttempt(0), "event-3");

        assertThat(returned).isEmpty();
    }

    @Test
    void beforeModelObserveReturnsEmptyForANullOrBlankEventId() {
        var activeModelCalls = new ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var stepSeq = new AtomicInteger();
        var collector = new TraceCollector();

        assertThat(AdkDiagnosisEngine.beforeModelObserve(
                activeModelCalls, stepSeq, collector.forAttempt(0), null)).isEmpty();
        assertThat(AdkDiagnosisEngine.beforeModelObserve(
                activeModelCalls, stepSeq, collector.forAttempt(0), "")).isEmpty();
        assertThat(AdkDiagnosisEngine.beforeModelObserve(
                activeModelCalls, stepSeq, collector.forAttempt(0), "   ")).isEmpty();
    }

    /**
     * Sweeps every combination above and asserts the SAME thing every time — the point being
     * that "unconditionally" is not "usually": no matter which internal component fails, or
     * whether it fails at all, the observed return value never varies.
     */
    @Test
    void beforeModelObserveNeverReturnsAnythingOtherThanEmptyRegardlessOfInternalState() {
        var stepSeq = new AtomicInteger();
        TraceSink noop = TraceSink.NOOP;
        TraceSink exploding = new TraceSink() {
            @Override public void before(TraceStep step) { throw new RuntimeException("x"); }
            @Override public void after(TraceStep step) { }
            @Override public void onError(TraceStep step) { }
        };

        List<Optional<LlmResponse>> results = List.of(
                AdkDiagnosisEngine.beforeModelObserve(new ConcurrentHashMap<>(), stepSeq, noop, "a"),
                AdkDiagnosisEngine.beforeModelObserve(new ConcurrentHashMap<>(), stepSeq, exploding, "b"),
                AdkDiagnosisEngine.beforeModelObserve(new ConcurrentHashMap<>(), stepSeq, noop, null),
                AdkDiagnosisEngine.beforeModelObserve(new ConcurrentHashMap<>(), stepSeq, exploding, null)
        );

        assertThat(results).allMatch(Optional::isEmpty);
        // And, just as important, none of the above threw — the method call itself never
        // propagates an exception that could disrupt the real model call.
        assertThatCode(() -> AdkDiagnosisEngine.beforeModelObserve(
                new ConcurrentHashMap<>(), stepSeq, exploding, "c")).doesNotThrowAnyException();
    }

    // ---- Requirement 1/2: open ACTIVE / resolve DONE+FAILED, correlated on eventId() ----

    @Test
    void modelEdgesOpenActiveAndResolveDoneOnTheSameEventId() {
        var activeModelCalls = new ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var stepSeq = new AtomicInteger();
        var collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);

        AdkDiagnosisEngine.beforeModelObserve(activeModelCalls, stepSeq, sink, "evt-42");
        AdkDiagnosisEngine.resolveActiveModelCall(activeModelCalls, stepSeq, "evt-42", sink,
                StepState.DONE, "chose search_confluence");

        List<TraceStep> steps = collector.steps();
        assertThat(steps).hasSize(1); // DONE replaced ACTIVE, not appended
        TraceStep step = steps.get(0);
        assertThat(step.callId()).isEqualTo("evt-42");
        assertThat(step.platform()).isEqualTo(Platform.TRIAGEMATE);
        assertThat(step.tool()).isNull(); // model rows have no tool (StepCatalog javadoc)
        assertThat(step.label()).isEqualTo("Thinking…");
        assertThat(step.state()).isEqualTo(StepState.DONE);
        assertThat(step.result()).isEqualTo("chose search_confluence");
        assertThat(step.durationMs()).isNotNull();
        assertThat(activeModelCalls).isEmpty(); // resolved, not left dangling
    }

    @Test
    void onModelErrorResolvesActiveCallToFailedMakingAProxyFailureVisible() {
        var activeModelCalls = new ConcurrentHashMap<String, AdkDiagnosisEngine.ActiveCall>();
        var stepSeq = new AtomicInteger();
        var collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);

        AdkDiagnosisEngine.beforeModelObserve(activeModelCalls, stepSeq, sink, "evt-err");
        AdkDiagnosisEngine.resolveActiveModelCall(activeModelCalls, stepSeq, "evt-err", sink,
                StepState.FAILED, "proxy returned 503");

        List<TraceStep> steps = collector.steps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).state()).isEqualTo(StepState.FAILED);
        assertThat(steps.get(0).result()).isEqualTo("proxy returned 503");
    }

    // ---- Requirement 4: retrospective labelling from the ACTUAL LlmResponse ----

    @Test
    void describeModelOutcomeReportsTheChosenToolWhenTheResponseContainsAFunctionCall() {
        LlmResponse response = LlmResponse.builder()
                .content(Content.fromParts(Part.fromFunctionCall("search_confluence", Map.of())))
                .build();

        assertThat(AdkDiagnosisEngine.describeModelOutcome(response)).isEqualTo("chose search_confluence");
    }

    @Test
    void describeModelOutcomeReportsProducedTheReportWhenTheResponseHasNoFunctionCall() {
        LlmResponse response = LlmResponse.builder()
                .content(Content.fromParts(Part.fromText("{\"incidentNumber\":\"INC1\"}")))
                .build();

        assertThat(AdkDiagnosisEngine.describeModelOutcome(response)).isEqualTo("produced the report");
    }

    @Test
    void describeModelOutcomeHandlesAResponseWithNoContentAtAll() {
        LlmResponse response = LlmResponse.builder().build();

        assertThat(AdkDiagnosisEngine.describeModelOutcome(response)).isEqualTo("produced the report");
    }
}
