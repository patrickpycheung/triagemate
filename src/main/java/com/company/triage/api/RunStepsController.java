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
 * <p><b>{@code since} is GLOBAL across attempts, not per-attempt.</b> {@link
 * TraceStep#seq()} restarts at 0 within each attempt's segment (its own javadoc says
 * so, per the LT1 degrade-to-fallback design) — a single scalar {@code since} compared
 * directly against {@code TraceStep.seq()} would silently swallow attempt 1's first few
 * steps after a degrade, because they'd carry {@code seq} values (0, 1, 2…) already
 * "seen" from attempt 0. Instead {@code since} indexes a step's POSITION within {@link
 * TraceCollector#steps()}'s flattened, already-ordered output (attempts ascending,
 * boundary-then-segment within each) — a position that is stable and strictly growing
 * over a run's lifetime: {@code TraceSink}'s replace-by-{@code callId} semantics (a
 * {@code before}/{@code after} pair shares one slot) never change a step's position,
 * only a newly-seen {@code callId} ever extends the list. A client tracks the highest
 * index it has received and sends it back as {@code since} on its next poll; omitting
 * {@code since} (or any value {@code < 0}) means "send everything so far".
 *
 * <p>Any staleness this simple index scheme introduces — e.g. a step whose row flips to
 * {@code ABANDONED} after a client already consumed its earlier (pre-degrade) index —
 * is bounded and self-healing: the client stops polling once {@code done} or the POST
 * resolves, and {@code DiagnosisResult.steps()} on that POST response carries the
 * final, fully-settled picture regardless of what polling saw in between. This endpoint
 * changes none of that POST contract — see {@link DiagnosisController} for the
 * byte-for-byte-unchanged guarantee.
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

        // attempt -> its NEW (index > since) steps, insertion order preserved so each
        // attempt's own steps stay ordered exactly as TraceCollector.steps() emitted them.
        Map<Integer, List<TraceStep>> newStepsByAttempt = new LinkedHashMap<>();
        for (int index = 0; index < flattened.size(); index++) {
            if (index <= since) {
                continue; // already delivered on an earlier poll
            }
            TraceStep step = flattened.get(index);
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
