package com.company.triage.api;

import com.company.triage.orchestration.trace.RunTraceRegistry;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceStep;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TASK-011 — the poll side of the LT4 {@code runId} protocol (design doc
 * {@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §"The runId
 * protocol", steps 2-3): a client that sent {@code X-Triage-Run-Id} on {@code POST
 * /api/diagnose/{incidentNumber}} (see {@link DiagnosisController}) polls this endpoint
 * (~500 ms) to render tool-call steps as they land, and "stops on {@code done} or when
 * the POST resolves — whichever is first" (design doc).
 *
 * <p>A separate controller — not a method on {@code DiagnosisController} — because the
 * URL family and resource (a run's live buffer, not an incident diagnosis) differ; this
 * is exactly the "sibling controller" {@code DiagnosisController}'s own javadoc
 * anticipates for this later task.
 *
 * <p><b>J12 — delivery is CONVERGENT, not incremental.</b> Every response carries the
 * buffer's current contents; the client upserts each row by {@code (attempt, callId)}.
 * What the client shows after any poll therefore equals what the buffer holds, which is
 * the property the whole live-trace feature depends on.
 *
 * <p>This replaced a {@code since}-as-position scheme, and the reason is worth keeping:
 * J11 models a step as a MUTABLE row keyed by {@code callId} ({@code TraceSink}'s
 * {@code before}/{@code after} pair shares one slot), while the transport could only
 * deliver NEW positions. A row resolving {@code ACTIVE -> DONE} changes that slot's
 * contents without changing its index, so the update was skipped — and on the ADK path
 * that was not an edge case but every single resolution. The old javadoc called the
 * resulting staleness "bounded and self-healing" because the POST's settle-render
 * corrected it at the end; what that framing missed is that the correction arrives after
 * the run, so the live animation never played at all.
 *
 * <p>{@code since} is still ACCEPTED for wire compatibility and ignored. It is not an
 * error to send it: an older client that does will simply receive the full buffer and,
 * because it appends rather than upserts, render duplicates — so clients must be updated
 * together with this endpoint. The parameter is kept rather than rejected so a stale
 * bookmark or a cached page degrades to "too many rows" instead of a 400.
 *
 * <p>The POST contract is untouched — see {@link DiagnosisController} for the
 * byte-for-byte-unchanged guarantee. {@code DiagnosisResult.steps()} remains the
 * authoritative final picture.
 *
 * <p>404, never 500 or a hang, for an unknown/expired {@code runId} (TASK-010's
 * TTL/cap eviction can legitimately retire one): {@link RunTraceRegistry#lookup} throws
 * {@code RunNotFoundException}, mapped by {@code DiagnosisApiExceptionHandler} the same
 * way {@code IncidentNotFoundException} already is for the POST endpoint.
 */
@RestController
@RequestMapping("/api/runs")
public class RunStepsController {

    private final RunTraceRegistry runTraceRegistry;

    public RunStepsController(RunTraceRegistry runTraceRegistry) {
        this.runTraceRegistry = runTraceRegistry;
    }

    @GetMapping("/{runId}/steps")
    public RunStepsResponse steps(@PathVariable String runId,
                                   // J12: accepted, ignored — see the class javadoc.
                                   @RequestParam(defaultValue = "-1") long since) {
        // Throws RunNotFoundException for an unknown/evicted runId, propagating straight
        // to DiagnosisApiExceptionHandler for a clean 404 — no null-check ceremony here.
        TraceCollector collector = runTraceRegistry.lookup(runId);
        return buildResponse(collector, since);
    }

    /**
     * Package-private so it can be unit-tested directly against a hand-built {@link
     * TraceCollector} — including a simulated FND-7 degrade — without MockMvc/HTTP
     * scaffolding or a real concurrent engine run.
     */
    static RunStepsResponse buildResponse(TraceCollector collector, long since) {
        List<TraceStep> flattened = collector.steps();

        int highestAttempt = -1;
        for (TraceStep step : flattened) {
            highestAttempt = Math.max(highestAttempt, step.attempt());
        }

        // J12 — CONVERGENT DELIVERY. Every poll returns the buffer's CURRENT contents, not
        // the slice added since last time.
        //
        // The bug this replaces: `since` indexed a POSITION in the flattened list, but a row
        // is MUTABLE and keyed by callId — TraceSink's replace-by-callId semantics mean the
        // before/after pair shares one slot, so a row resolving ACTIVE -> DONE changes the
        // slot's CONTENTS without changing its INDEX. `index <= since` therefore skipped it,
        // and on the ADK path that is not an edge case: it is every single resolution. Rows
        // pulsed ACTIVE for the whole 37-77s run and only settled when the POST returned —
        // the "resolves in place" animation this whole card exists for never played live.
        //
        // Two models were in conflict (mutable rows vs append-only positions) and one had to
        // go. Full re-read wins over a change-version cursor because it is SELF-HEALING: a
        // dropped or out-of-order poll costs nothing, since each response is the complete
        // truth rather than a delta that must be applied in sequence. The cost is bandwidth,
        // and there isn't any worth counting — the buffer is bounded by J8's tool budget
        // (tens of rows), polled at 500 ms, over loopback.
        //
        // REQUIRES the client to upsert by (attempt, callId), which index.html now does. A
        // client that appended blindly would render duplicates instead of stale rows —
        // strictly worse — so these two changes are one change and must not be split.
        Map<Integer, List<TraceStep>> newStepsByAttempt = new LinkedHashMap<>();
        for (TraceStep step : flattened) {
            newStepsByAttempt.computeIfAbsent(step.attempt(), a -> new ArrayList<>()).add(step);
        }

        List<RunStepsResponse.AttemptSteps> attempts = new ArrayList<>();
        for (Map.Entry<Integer, List<TraceStep>> entry : newStepsByAttempt.entrySet()) {
            int attempt = entry.getKey();
            List<TraceStep> steps = entry.getValue();
            boolean abandoned = steps.stream().anyMatch(s -> s.state() == StepState.ABANDONED);
            RunStepsResponse.AttemptState state;
            if (abandoned) {
                state = RunStepsResponse.AttemptState.ABANDONED;
            } else if (attempt == highestAttempt) {
                state = collector.isDone() ? RunStepsResponse.AttemptState.DONE : RunStepsResponse.AttemptState.ACTIVE;
            } else {
                // A non-highest attempt with steps that were never re-tagged ABANDONED
                // shouldn't normally happen (only abandonAndStartFallback opens a new
                // attempt, and it always freezes the old one first) — treated as settled.
                state = RunStepsResponse.AttemptState.DONE;
            }
            attempts.add(new RunStepsResponse.AttemptSteps(attempt, state, steps));
        }

        return new RunStepsResponse(attempts, collector.isDone());
    }
}
