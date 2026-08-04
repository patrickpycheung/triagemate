package com.company.triage.orchestration.trace;

/**
 * The system a {@link TraceStep} was talking to. {@code TRIAGEMATE} is the pseudo-platform
 * for steps that are internal to this app (e.g. the writeback decision) rather than a call
 * out to one of the four real integrations.
 */
public enum Platform {
    SERVICENOW,
    CONFLUENCE,
    SUMO,
    GITLAB,
    TRIAGEMATE
}
