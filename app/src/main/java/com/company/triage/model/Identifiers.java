package com.company.triage.model;

/** Structured identifiers extracted from the incident (J4). Any field may be null. */
public record Identifiers(
        String correlationId,
        String errorCode,
        String orderId
) {}
