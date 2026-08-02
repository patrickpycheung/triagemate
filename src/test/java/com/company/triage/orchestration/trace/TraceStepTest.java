package com.company.triage.orchestration.trace;

import com.company.triage.orchestration.DiagnosisResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-001: minimal data-carrier tests for LT1's core types. Nothing emits/consumes a
 * {@link TraceStep} yet (that lands in later STREAM-001 tasks) — this just locks down
 * construction and record equality/hashing so a later "replace the row by callId" sink
 * implementation can rely on them.
 */
class TraceStepTest {

    private static TraceStep activeStep() {
        return new TraceStep(0, 0, "det-0", Platform.SERVICENOW, "search_incidents",
                "Searching ServiceNow…", null, StepState.ACTIVE, 1_000L, null,
                DiagnosisResult.Engine.DETERMINISTIC);
    }

    @Test
    void constructsWithAllComponentsAccessible() {
        TraceStep step = activeStep();

        assertThat(step.seq()).isZero();
        assertThat(step.attempt()).isZero();
        assertThat(step.callId()).isEqualTo("det-0");
        assertThat(step.platform()).isEqualTo(Platform.SERVICENOW);
        assertThat(step.tool()).isEqualTo("search_incidents");
        assertThat(step.label()).isEqualTo("Searching ServiceNow…");
        assertThat(step.result()).isNull();
        assertThat(step.state()).isEqualTo(StepState.ACTIVE);
        assertThat(step.startedAtEpochMs()).isEqualTo(1_000L);
        assertThat(step.durationMs()).isNull();
        assertThat(step.engine()).isEqualTo(DiagnosisResult.Engine.DETERMINISTIC);
    }

    @Test
    void twoStepsWithTheSameFieldsAreEqual() {
        assertThat(activeStep()).isEqualTo(activeStep());
        assertThat(activeStep()).hasSameHashCodeAs(activeStep());
    }

    /**
     * The "replace the row" contract: an after/onError step carries the same callId as the
     * before step it replaces, but is a genuinely different record (different state/result).
     */
    @Test
    void resolvedStepSharesCallIdWithTheActiveStepItReplaces() {
        TraceStep active = activeStep();
        TraceStep resolved = new TraceStep(active.seq(), active.attempt(), active.callId(),
                active.platform(), active.tool(), active.label(), "Found 3 similar incidents",
                StepState.DONE, active.startedAtEpochMs(), 42L, active.engine());

        assertThat(resolved.callId()).isEqualTo(active.callId());
        assertThat(resolved).isNotEqualTo(active);
        assertThat(resolved.state()).isEqualTo(StepState.DONE);
        assertThat(resolved.durationMs()).isEqualTo(42L);
    }

    @Test
    void noopSinkAcceptsAllThreeCallbacksWithoutThrowing() {
        TraceStep step = activeStep();

        TraceSink.NOOP.before(step);
        TraceSink.NOOP.after(step);
        TraceSink.NOOP.onError(step);
    }
}
