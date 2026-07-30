package com.company.triage.orchestration;

import com.company.triage.model.DiagnosisReport;

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
 */
public record DiagnosisResult(
        DiagnosisReport report,
        List<String> trace,
        Engine engine,
        boolean writebackPosted
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
     * the orchestrator knows the engine label or the writeback outcome — both are
     * filled in with real values afterward (see {@link DiagnosisOrchestrator}). Engines
     * should never set these themselves.
     */
    public DiagnosisResult(DiagnosisReport report, List<String> trace) {
        this(report, trace, Engine.DETERMINISTIC, true);
    }

    /** Back-compat convenience: pre-FND-25 callers that only cared about {@code engine}. */
    public DiagnosisResult(DiagnosisReport report, List<String> trace, Engine engine) {
        this(report, trace, engine, true);
    }

    /** True when this report did NOT come from the engine that was asked for. */
    public boolean degraded() {
        return engine == Engine.DEGRADED_TO_DETERMINISTIC;
    }
}
