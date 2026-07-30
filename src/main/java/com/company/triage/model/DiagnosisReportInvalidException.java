package com.company.triage.model;

import java.util.List;

/**
 * A model returned schema-shaped J4 JSON that violates a semantic rule the schema
 * itself can't express (FND-17) — e.g. an empty candidate list or an
 * {@code evidenceRefs} entry with no matching {@code Evidence}.
 */
public class DiagnosisReportInvalidException extends RuntimeException {
    public DiagnosisReportInvalidException(String incidentNumber, List<String> problems) {
        super("report for " + incidentNumber + " failed J4 validation: " + String.join("; ", problems));
    }
}
