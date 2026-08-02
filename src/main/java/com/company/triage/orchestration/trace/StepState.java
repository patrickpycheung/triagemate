package com.company.triage.orchestration.trace;

/**
 * Lifecycle of a single {@link TraceStep} row. Named {@code StepState}, not {@code State}
 * (collision risk with other framework/JDK types already imported across this codebase —
 * kept this way since the LT1 spike).
 */
public enum StepState {
    /** Row created but not yet started (reserved for future ordering/queueing use). */
    PENDING,
    /** Emitted by {@code TraceSink.before} — the call is in flight. */
    ACTIVE,
    /** Emitted by {@code TraceSink.after} on success — the call completed. */
    DONE,
    /** Emitted by {@code TraceSink.onError} — the call threw. */
    FAILED,
    /** The call was not attempted because a guardrail denied it. */
    DENIED
}
