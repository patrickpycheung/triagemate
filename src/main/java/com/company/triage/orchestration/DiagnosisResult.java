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
 */
public record DiagnosisResult(
        DiagnosisReport report,
        List<String> trace,
        Engine engine
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

    /** Back-compat convenience: assumes the configured engine ran normally. */
    public DiagnosisResult(DiagnosisReport report, List<String> trace) {
        this(report, trace, Engine.DETERMINISTIC);
    }

    /** True when this report did NOT come from the engine that was asked for. */
    public boolean degraded() {
        return engine == Engine.DEGRADED_TO_DETERMINISTIC;
    }
}
