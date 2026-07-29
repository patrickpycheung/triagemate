package com.company.triage.orchestration;

/**
 * The pluggable "brain" (J2). Two implementations:
 *  - {@code DeterministicDiagnosisEngine} — offline, scripted phase flow (default).
 *  - {@code AdkDiagnosisEngine} — live ADK agent (src/main/adk, profile {@code adk}).
 * Both consume the same gateways (J3) and produce the same {@link DiagnosisResult}.
 */
public interface DiagnosisEngine {
    DiagnosisResult diagnose(String incidentNumber);
}
