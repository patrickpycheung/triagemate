package com.company.triage.api;

import com.company.triage.orchestration.trace.TraceStep;

import java.util.List;

/**
 * TASK-011: the {@code GET /api/runs/{runId}/steps} poll response (design doc
 * {@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §"The runId
 * protocol", step 2 — {@code {attempts: [{attempt, state, steps: [...]}], done: bool}}).
 *
 * <p>{@code attempts} is an ARRAY, not a single segment, because of the segment-per-
 * attempt degrade model (TASK-003/TASK-010): a degraded run produces two — attempt 0
 * (the primary, possibly {@link AttemptState#ABANDONED}) and attempt 1 (the FND-7
 * fallback). {@code done} mirrors {@code TraceCollector#isDone()} and is designed to
 * agree with the POST's eventual 200 (design doc, binding requirement) — see {@link
 * RunStepsController} for exactly how/when it's set.
 */
public record RunStepsResponse(List<AttemptSteps> attempts, boolean done) {

    /** One engine-call segment's worth of NEW (see {@link RunStepsController}'s {@code
     *  since} javadoc) steps, plus that segment's own lifecycle state. */
    public record AttemptSteps(int attempt, AttemptState state, List<TraceStep> steps) {
    }

    /**
     * Attempt-level lifecycle — deliberately a SEPARATE vocabulary from {@code
     * StepState}, which is per-step, not per-attempt.
     */
    public enum AttemptState {
        /** This is the run's current (highest) attempt and the run is not yet done. */
        ACTIVE,
        /** Either this attempt completed normally, or the run itself is now done. */
        DONE,
        /** This attempt's segment was frozen by an FND-7 degrade — see {@code
         *  TraceCollector#abandonAndStartFallback}. Its rows are kept (never deleted)
         *  but re-tagged {@code StepState.ABANDONED}. */
        ABANDONED
    }
}
