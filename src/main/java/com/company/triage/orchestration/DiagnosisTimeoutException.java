package com.company.triage.orchestration;

/**
 * FND-15: an engine (deterministic or ADK) did not return within
 * {@code triage.orchestrator.timeout-ms}. Thrown by {@link DiagnosisOrchestrator} — never
 * by an engine itself, which has no notion of the orchestrator's wall-clock budget.
 *
 * <p>When the primary engine is ADK, this is caught by the existing FND-7 fallback and
 * degrades to the deterministic engine like any other primary-engine failure. When the
 * primary engine already IS the deterministic one (a hung real gateway with no LLM
 * involved), it propagates — there is nothing left to fall back to.
 */
public class DiagnosisTimeoutException extends RuntimeException {
    public DiagnosisTimeoutException(String incidentNumber, long timeoutMs) {
        super("diagnosis for " + incidentNumber + " did not complete within " + timeoutMs + " ms");
    }
}
