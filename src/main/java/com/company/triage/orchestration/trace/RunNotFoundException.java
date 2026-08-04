package com.company.triage.orchestration.trace;

/**
 * TASK-011 (J11/LT4 poll endpoint): thrown by {@link RunTraceRegistry#lookup} when
 * {@code runId} is unknown — either it was never registered (no client ever supplied
 * {@code X-Triage-Run-Id} for it), or it WAS registered but has since aged out of
 * {@link InMemoryRunTraceRegistry} under TASK-010's TTL/cap eviction. Both cases look
 * identical to a poller and both need the same clean signal: "stop polling, this run
 * is gone" — a 404, never a 500 or a hang (design doc §"The runId protocol", binding
 * correction). Mapped to {@code HttpStatus.NOT_FOUND} by
 * {@code DiagnosisApiExceptionHandler}, the same way {@code IncidentNotFoundException}
 * already is.
 */
public class RunNotFoundException extends RuntimeException {
    public RunNotFoundException(String runId) {
        super("run not found: " + runId);
    }
}
