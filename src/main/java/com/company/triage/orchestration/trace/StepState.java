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
    DENIED,
    /**
     * TASK-003 (J11 LT1 Invariant 1): the engine attempt this row belongs to was frozen
     * mid-run by an FND-7 degrade — the row is kept (never deleted, per the honesty
     * contract) but re-tagged {@code ABANDONED} regardless of whatever state it last had
     * ({@code ACTIVE}/{@code DONE}/{@code FAILED}), so a renderer can show it struck
     * through without losing the row. See {@code TraceCollector#abandonAndStartFallback}.
     */
    ABANDONED
}
