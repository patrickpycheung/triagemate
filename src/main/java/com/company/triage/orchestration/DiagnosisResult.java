package com.company.triage.orchestration;

import com.company.triage.model.DiagnosisReport;

import java.util.List;

/**
 * What the API returns: the structured report (J4) plus the per-run tool-call trace
 * (J8) that lets the UI show "it really consulted the sources" (J7).
 */
public record DiagnosisResult(
        DiagnosisReport report,
        List<String> trace
) {}
