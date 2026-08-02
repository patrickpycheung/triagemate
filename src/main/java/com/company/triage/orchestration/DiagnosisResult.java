package com.company.triage.orchestration;

import com.company.triage.model.DiagnosisReport;
import com.company.triage.orchestration.trace.TraceStep;

import java.util.List;

/**
 * What the API returns: the structured report (J4) plus the per-run tool-call trace
 * (J8) that lets the UI show "it really consulted the sources" (J7).
 *
 * <p>{@code engine} answers "what actually produced this?" in a machine-readable way
 * (FND-8). The trace already discloses a degradation in prose, but string-matching a
 * trace line is not a contract — and an unattended K1 poll run has no UI to show a
 * banner in, so the work note and any programmatic caller need a real field. Note this
 * lives on the RESULT wrapper, deliberately NOT on {@link DiagnosisReport}: the J4
 * report contract stays untouched.
 *
 * <p>{@code writebackPosted} is the same fix applied to a second question (FND-25):
 * "were the two comments actually posted to ServiceNow?" The UI previously rendered a
 * "Posted to ServiceNow — automatically" card unconditionally, reconstructed client-side
 * from the report — so with {@code triage.writeback.enabled=false} it told the audience
 * comments were posted when none were. Set by {@link DiagnosisOrchestrator#run} AFTER
 * the writeback decision is made, which is the only place that actually knows.
 *
 * <p>{@code steps} (TASK-003, J11/LT1) is the structured live-thinking-trace counterpart
 * to {@code trace} — an ADDITION alongside it, never a replacement or a derivation of
 * it. {@code trace} stays byte-identical to what it always was: 11 existing trace-line
 * formats carry argument detail and roughly 10 test assertions match their prefixes, so
 * deriving one from the other (or changing either's shape) is exactly the FND-16 failure
 * class this card's own javadoc warns about above. {@code steps} is populated by {@link
 * DiagnosisOrchestrator} from its per-run {@code TraceCollector}, segmented by engine
 * attempt so an FND-7 degrade never silently discards the primary attempt's rows.
 */
public record DiagnosisResult(
        DiagnosisReport report,
        List<String> trace,
        Engine engine,
        boolean writebackPosted,
        List<TraceStep> steps
) {
    /** Which engine produced the report. */
    public enum Engine {
        /** The offline deterministic engine ran as the configured engine. */
        DETERMINISTIC,
        /** The live ADK agent ran successfully. */
        ADK,
        /** The ADK agent failed and the deterministic engine produced this instead (FND-7). */
        DEGRADED_TO_DETERMINISTIC
    }

    /**
     * Back-compat convenience for engine implementations, which return a result BEFORE
     * the orchestrator knows the engine label, the writeback outcome, or the collected
     * {@code steps} — all three are filled in with real values afterward (see {@link
     * DiagnosisOrchestrator}). Engines should never set these themselves.
     */
    public DiagnosisResult(DiagnosisReport report, List<String> trace) {
        this(report, trace, Engine.DETERMINISTIC, true, List.of());
    }

    /** Back-compat convenience: pre-FND-25 callers that only cared about {@code engine}. */
    public DiagnosisResult(DiagnosisReport report, List<String> trace, Engine engine) {
        this(report, trace, engine, true, List.of());
    }

    /** Back-compat convenience: pre-TASK-003 callers that don't carry {@code steps}. */
    public DiagnosisResult(DiagnosisReport report, List<String> trace, Engine engine, boolean writebackPosted) {
        this(report, trace, engine, writebackPosted, List.of());
    }

    /** True when this report did NOT come from the engine that was asked for. */
    public boolean degraded() {
        return engine == Engine.DEGRADED_TO_DETERMINISTIC;
    }
}
