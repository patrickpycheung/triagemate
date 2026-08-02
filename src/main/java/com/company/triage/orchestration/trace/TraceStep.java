package com.company.triage.orchestration.trace;

import com.company.triage.orchestration.DiagnosisResult;

/**
 * One row of the live thinking trace (J11/LT1): a single tool call, from the moment it
 * starts to the moment it resolves.
 *
 * <p>A step is a <b>mutable row, not an append</b>: {@code TraceSink.before} emits it in
 * {@link StepState#ACTIVE}, and {@code TraceSink.after}/{@code onError} emit a
 * <b>replacement</b> {@code TraceStep} carrying the same {@code callId} — sinks key off
 * {@code callId} and overwrite the existing row rather than appending a second one.
 *
 * @param seq                index within this engine call's segment, starting at 0. Segments
 *                            restart at 0 per {@code attempt} (see the LT1 degrade-to-fallback
 *                            design) — {@code seq} is display order within a segment, not a
 *                            global sequence.
 * @param attempt             0 for the primary engine call, 1 for an FND-7 fallback retry.
 * @param callId              correlation key. {@code ToolContext.functionCallId()} on the ADK
 *                            path; a synthetic {@code det-<seq>} on the deterministic path.
 *                            {@code before} and the matching {@code after}/{@code onError}
 *                            share the same {@code callId}.
 * @param platform            which system this call talked to.
 * @param tool                the tool/function name invoked.
 * @param label               in-progress verb shown while {@link StepState#ACTIVE} (e.g.
 *                            "Searching ServiceNow…").
 * @param result              outcome text once resolved (e.g. "Found 3 similar incidents").
 *                            {@code label} and {@code result} are separate fields so both are
 *                            available at once for the client's resolve animation.
 * @param state               current lifecycle state.
 * @param startedAtEpochMs    wall-clock time {@code before} was called.
 * @param durationMs          real elapsed time once resolved, {@code null} while
 *                            {@link StepState#ACTIVE}. Real, not synthetic — this is what
 *                            makes a paced replay honest.
 * @param engine              which engine attempt produced this step. Gives attempt identity
 *                            so a live view never shows ADK steps beside a deterministic final
 *                            report with no visible boundary (the FND-16 failure class).
 */
public record TraceStep(
        int seq,
        int attempt,
        String callId,
        Platform platform,
        String tool,
        String label,
        String result,
        StepState state,
        long startedAtEpochMs,
        Long durationMs,
        DiagnosisResult.Engine engine
) {
}
