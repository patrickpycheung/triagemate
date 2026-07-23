package com.company.triage.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The strict JSON diagnosis contract (J4) — the spine of the whole app. The
 * ServiceNow work note (J5) and the demo UI (J7) are both renderings of this object.
 *
 * <p>Rules: candidates and assignment are ranked shortlists (never one forced
 * answer); every conclusion ties to {@code evidence} via evidenceRefs; conflicting
 * evidence is surfaced, not hidden; {@code advisory} is always true this phase.
 */
public record DiagnosisReport(
        String incidentNumber,
        OffsetDateTime generatedAt,
        String reportedSymptom,
        String affectedFunction,
        String environment,
        Identifiers identifiers,
        List<CandidateSystem> candidateSystems,
        SuggestedAssignment suggestedAssignment,
        List<Evidence> evidence,
        List<String> contradictingEvidence,
        List<String> missingInformation,
        String recommendedNextAction,
        Confidence confidenceOverall,
        boolean advisory
) {
    /** Renders the report as a ServiceNow-ready advisory work note (J5). */
    public String toWorkNote() {
        StringBuilder b = new StringBuilder();
        b.append("[AI-assisted initial diagnosis — advisory only]\n\n");
        b.append(reportedSymptom).append("\n\n");
        b.append("Likely involved systems: ");
        b.append(candidateSystems.stream()
                .map(c -> "%s (%.0f%%)".formatted(c.name(), c.confidence() * 100))
                .reduce((a, c) -> a + ", " + c).orElse("—")).append("\n");
        if (suggestedAssignment != null) {
            b.append("Suggested assignment group: ").append(suggestedAssignment.group())
             .append(" — ").append(suggestedAssignment.confidence().name().toLowerCase())
             .append(" confidence\n");
        }
        b.append("Evidence: ");
        b.append(evidence.stream().map(Evidence::summary)
                .reduce((a, c) -> a + "; " + c).orElse("—")).append("\n");
        if (recommendedNextAction != null) {
            b.append("Recommended next action: ").append(recommendedNextAction).append("\n");
        }
        if (missingInformation != null && !missingInformation.isEmpty()) {
            b.append("Missing information: ").append(String.join(", ", missingInformation)).append("\n");
        }
        b.append("\nNo ticket reassignment has been performed.");
        return b.toString();
    }
}
