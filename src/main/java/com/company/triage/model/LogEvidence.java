package com.company.triage.model;

/** One log message returned by a bounded Sumo search (J6). */
public record LogEvidence(
        String time,
        String level,
        String logger,
        String message
) {}
