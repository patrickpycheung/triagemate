package com.company.triage.orchestration;

import com.company.triage.orchestration.trace.TraceSink;

/**
 * The pluggable "brain" (J2). Two implementations:
 *  - {@code DeterministicDiagnosisEngine} — offline, scripted phase flow (default).
 *  - {@code AdkDiagnosisEngine} — live ADK agent (src/main/adk, profile {@code adk}).
 * Both consume the same gateways (J3) and produce the same {@link DiagnosisResult}.
 *
 * <p>{@link #diagnose(String, TraceSink)} is the implemented method — engines emit their
 * live thinking trace (J11/LT1) through {@code sink} as they make tool calls. {@link
 * #diagnose(String)} is a convenience default for callers not tracking steps, passing
 * {@link TraceSink#NOOP}.
 */
public interface DiagnosisEngine {

    default DiagnosisResult diagnose(String incidentNumber) {
        return diagnose(incidentNumber, TraceSink.NOOP);
    }

    DiagnosisResult diagnose(String incidentNumber, TraceSink sink);
}
