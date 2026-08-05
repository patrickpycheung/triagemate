package com.company.triage.api;

import com.company.triage.orchestration.DiagnosisResult;
import com.company.triage.orchestration.trace.InMemoryRunTraceRegistry;
import com.company.triage.orchestration.trace.Platform;
import com.company.triage.orchestration.trace.RunNotFoundException;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceSink;
import com.company.triage.orchestration.trace.TraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-011 (J11/LT4 poll endpoint): direct unit coverage of {@link
 * RunStepsController#buildResponse}, independent of MockMvc/HTTP scaffolding — this is
 * where the interesting logic (the {@code since} filter, per-attempt grouping, and
 * attempt-state derivation) lives, so it's tested against a hand-built {@link
 * TraceCollector} rather than driving a real concurrent engine run.
 *
 * <p>Covers Requirement 3 explicitly: the segment-per-attempt/degrade case (attempt 0
 * possibly {@code ABANDONED}, attempt 1 the fallback), not just a single-attempt happy
 * path.
 */
class RunStepsControllerTest {

    private static TraceStep step(int seq, int attempt, String callId, StepState state) {
        return new TraceStep(seq, attempt, callId, Platform.SERVICENOW, "search_incidents",
                "Searching…", state == StepState.DONE ? "found 3" : null, state,
                1_000L + seq, state == StepState.DONE ? 5L : null, DiagnosisResult.Engine.ADK);
    }

    // --- single-attempt happy path --------------------------------------------------

    @Test
    void aFreshPollReturnsEverythingSoFar() {
        TraceCollector collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);
        sink.before(step(0, 0, "c1", StepState.ACTIVE));
        sink.after(step(0, 0, "c1", StepState.DONE));
        sink.before(step(1, 0, "c2", StepState.ACTIVE));

        RunStepsResponse response = RunStepsController.buildResponse(collector, -1);

        assertThat(response.done()).isFalse();
        assertThat(response.attempts()).hasSize(1);
        RunStepsResponse.AttemptSteps attempt0 = response.attempts().get(0);
        assertThat(attempt0.attempt()).isZero();
        assertThat(attempt0.state()).isEqualTo(RunStepsResponse.AttemptState.ACTIVE);
        assertThat(attempt0.steps()).hasSize(2);
        assertThat(attempt0.steps().get(0).state()).isEqualTo(StepState.DONE); // c1, resolved
        assertThat(attempt0.steps().get(1).state()).isEqualTo(StepState.ACTIVE); // c2, in flight
    }

    /**
     * SUPERSEDED BY J12 — this asserted the INCREMENTAL contract ("only the new step, not
     * c1 again"). That contract was the bug: it is incompatible with rows being mutable and
     * keyed by callId, because a row that RESOLVES occupies the same position and therefore
     * looks "already delivered". Delivery is now convergent — every poll returns the whole
     * buffer and the client upserts by (attempt, callId).
     */
    @Test
    void everyPollReturnsTheWholeBufferNotJustTheNewSteps() {
        TraceCollector collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);
        sink.before(step(0, 0, "c1", StepState.ACTIVE));
        sink.after(step(0, 0, "c1", StepState.DONE));

        RunStepsResponse first = RunStepsController.buildResponse(collector, -1);
        assertThat(first.attempts().get(0).steps()).hasSize(1);

        sink.before(step(1, 0, "c2", StepState.ACTIVE));

        // A client that still sends a cursor gets the same answer — `since` is accepted and
        // ignored, so an older client degrades to duplicate rows, never to missing ones.
        RunStepsResponse second = RunStepsController.buildResponse(collector, 0);

        assertThat(second.attempts().get(0).steps())
                .as("the complete current buffer, so a dropped poll costs nothing")
                .extracting(TraceStep::callId).containsExactly("c1", "c2");
    }

    /**
     * THE regression this card exists for: a row that resolves ACTIVE -> DONE must reach a
     * later poll.
     *
     * <p>Under the old since-as-position scheme it never did. {@code TraceSink} replaces by
     * {@code callId}, so the resolution reuses the SAME slot; its index was therefore
     * {@code <= since} and it was skipped as "already delivered". On the ADK path that was
     * every single resolution, so rows pulsed ACTIVE for the whole 37-77 s run and only
     * settled when the POST returned — the "resolves in place" animation the card is named
     * for never played live.
     */
    @Test
    void aRowThatResolvesAfterAnEarlierPollIsDeliveredWithItsNewState() {
        TraceCollector collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);
        sink.before(step(0, 0, "c1", StepState.ACTIVE));

        // Poll 1: the client sees c1 in flight and (under the old scheme) advanced past it.
        RunStepsResponse first = RunStepsController.buildResponse(collector, -1);
        assertThat(first.attempts().get(0).steps().get(0).state()).isEqualTo(StepState.ACTIVE);

        // The tool comes back — same callId, same slot, new state.
        sink.after(step(0, 0, "c1", StepState.DONE));

        RunStepsResponse second = RunStepsController.buildResponse(collector, 0);

        assertThat(second.attempts()).as("the resolution must not be swallowed").isNotEmpty();
        assertThat(second.attempts().get(0).steps())
                .extracting(TraceStep::callId).contains("c1");
        assertThat(second.attempts().get(0).steps().stream()
                        .filter(st -> "c1".equals(st.callId())).findFirst().orElseThrow().state())
                .as("the client must be able to see c1 resolve, during the run")
                .isEqualTo(StepState.DONE);
    }

    /**
     * SUPERSEDED BY J12: "no NEW steps" is no longer a meaningful state, because delivery is
     * not incremental. A poll against an unchanged buffer returns that buffer unchanged, and
     * the client's upsert makes re-delivery a no-op on screen. An genuinely EMPTY buffer
     * still yields empty attempts — that case is preserved below.
     */
    @Test
    void aPollAgainstAnUnchangedBufferReturnsThatBufferAgain() {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE));

        RunStepsResponse response = RunStepsController.buildResponse(collector, 0);

        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().get(0).steps()).extracting(TraceStep::callId)
                .containsExactly("c1");
        assertThat(response.done()).isFalse();
    }

    /** An empty buffer still has nothing to report — the one genuinely empty case. */
    @Test
    void aPollAgainstAnEmptyBufferReturnsNoAttempts() {
        RunStepsResponse response = RunStepsController.buildResponse(new TraceCollector(), -1);

        assertThat(response.attempts()).isEmpty();
        assertThat(response.done()).isFalse();
    }

    @Test
    void doneReflectsCollectorMarkDoneRegardlessOfStepState() {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE)); // still "in flight"
        collector.markDone(); // but the orchestrator says the whole run has settled

        RunStepsResponse response = RunStepsController.buildResponse(collector, -1);

        assertThat(response.done()).isTrue();
        assertThat(response.attempts().get(0).state())
                .as("the highest attempt's state must resolve to DONE once the run is done")
                .isEqualTo(RunStepsResponse.AttemptState.DONE);
    }

    // --- segment-per-attempt / degrade case (Requirement 3) -------------------------

    @Test
    void aDegradedRunProducesTwoAttemptsWithAttempt0AbandonedAndAttempt1Live() {
        TraceCollector collector = new TraceCollector();
        TraceSink primary = collector.forAttempt(0);
        primary.before(step(0, 0, "c1", StepState.ACTIVE));
        primary.after(step(0, 0, "c1", StepState.DONE));

        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC,
                "IllegalStateException: LLM_API_KEY missing");

        TraceSink fallback = collector.forAttempt(1);
        fallback.before(step(0, 1, "c2", StepState.ACTIVE));

        RunStepsResponse response = RunStepsController.buildResponse(collector, -1);

        assertThat(response.done()).isFalse();
        assertThat(response.attempts()).hasSize(2);

        RunStepsResponse.AttemptSteps attempt0 = response.attempts().get(0);
        assertThat(attempt0.attempt()).isZero();
        assertThat(attempt0.state()).isEqualTo(RunStepsResponse.AttemptState.ABANDONED);
        assertThat(attempt0.steps()).hasSize(1);
        assertThat(attempt0.steps().get(0).state()).isEqualTo(StepState.ABANDONED);

        RunStepsResponse.AttemptSteps attempt1 = response.attempts().get(1);
        assertThat(attempt1.attempt()).isEqualTo(1);
        assertThat(attempt1.state()).isEqualTo(RunStepsResponse.AttemptState.ACTIVE);
        // The FALLBACK_STARTED boundary row plus the one live step.
        assertThat(attempt1.steps()).hasSize(2);
        assertThat(attempt1.steps().get(0).tool()).isEqualTo(TraceCollector.FALLBACK_STARTED_TOOL);
        assertThat(attempt1.steps().get(1).callId()).isEqualTo("c2");
    }

    @Test
    void aDegradedRunReachesDoneOnceTheFallbackFinishesAndTheOrchestratorMarksItDone() {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE));
        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "boom");
        TraceSink fallback = collector.forAttempt(1);
        fallback.before(step(0, 1, "c2", StepState.ACTIVE));
        fallback.after(step(0, 1, "c2", StepState.DONE));
        collector.markDone();

        RunStepsResponse response = RunStepsController.buildResponse(collector, -1);

        assertThat(response.done()).isTrue();
        RunStepsResponse.AttemptSteps attempt1 = response.attempts().get(1);
        assertThat(attempt1.attempt()).isEqualTo(1);
        assertThat(attempt1.state()).isEqualTo(RunStepsResponse.AttemptState.DONE);
    }

    @Test
    void pollingAgainstARealRegistryEndToEndForADegradedRun() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector collector = registry.register("run-degrade-1", "INC0010005");
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE));
        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "boom");
        collector.forAttempt(1).before(step(0, 1, "c2", StepState.ACTIVE));

        RunStepsController controller = new RunStepsController(registry);
        RunStepsResponse response = controller.steps("run-degrade-1", -1);

        assertThat(response.attempts()).hasSize(2);
        assertThat(response.attempts().get(0).state()).isEqualTo(RunStepsResponse.AttemptState.ABANDONED);
        assertThat(response.attempts().get(1).state()).isEqualTo(RunStepsResponse.AttemptState.ACTIVE);
    }

    // --- unknown/expired runId -> clean failure, not a null/hang --------------------

    @Test
    void unknownRunIdThrowsRunNotFoundException() {
        var registry = new InMemoryRunTraceRegistry();
        RunStepsController controller = new RunStepsController(registry);

        assertThatThrownBy(() -> controller.steps("no-such-run", -1))
                .isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void ambiguousBoundaryRowsGroupWithTheAttemptTheyIntroduce() {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE));
        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "boom");

        RunStepsResponse response = RunStepsController.buildResponse(collector, -1);

        // Even with zero attempt-1 tool-call steps yet, the boundary row alone must
        // surface attempt 1 in the response (it carries attempt=1, per TraceCollector).
        assertThat(response.attempts()).extracting(RunStepsResponse.AttemptSteps::attempt)
                .containsExactly(0, 1);
        assertThat(response.attempts().get(1).steps()).hasSize(1);
        assertThat(response.attempts().get(1).steps().get(0).tool())
                .isEqualTo(TraceCollector.FALLBACK_STARTED_TOOL);
    }

    @Test
    void allStepsIncludedWhenNothingIsAbandoned() {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE));

        RunStepsResponse response = RunStepsController.buildResponse(collector, -1);

        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().get(0).steps()).hasSize(1);
        assertThat(response.attempts().get(0).steps()).isEqualTo(List.of(step(0, 0, "c1", StepState.ACTIVE)));
    }
}
