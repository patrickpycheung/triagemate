package com.company.triage.orchestration;

import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceSink;
import com.company.triage.orchestration.trace.TraceStep;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link TraceSink} that keeps every row, so a test can assert on the trace's <b>state</b>
 * and not merely on its text (FND-89).
 *
 * <p>That distinction is the point. The trace text and the trace state are two separate
 * claims, and FND-89 was precisely the case where they disagreed: a row reading
 * {@code COULD NOT SEARCH (GitLab unreachable)} carried {@link StepState#DONE}, because
 * {@code emitStep} hardcoded it. Every existing test asserted on {@code result.trace()},
 * which is the text list — so the state could be wrong indefinitely without a red test.
 *
 * <p>Rows are recorded exactly as emitted, including the {@code ACTIVE} row from
 * {@code before}; {@link #terminalStates} filters to the settled ones a reader would judge.
 */
final class RecordingTraceSink implements TraceSink {

    private final List<TraceStep> steps = new ArrayList<>();

    @Override public synchronized void before(TraceStep step) { steps.add(step); }
    @Override public synchronized void after(TraceStep step) { steps.add(step); }
    @Override public synchronized void onError(TraceStep step) { steps.add(step); }

    synchronized List<TraceStep> steps() { return List.copyOf(steps); }

    /** Settled states for one dotted key, in emission order — one entry per completed row. */
    synchronized List<StepState> terminalStates(String dottedKey) {
        return steps.stream()
                .filter(s -> dottedKey.equals(s.tool()))
                .map(TraceStep::state)
                .filter(s -> s != StepState.ACTIVE)
                .toList();
    }
}
