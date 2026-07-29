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
        List<Contact> suggestedContacts,
        List<String> contradictingEvidence,
        List<String> missingInformation,
        String recommendedNextAction,
        Confidence confidenceOverall,
        boolean advisory
) {
    /**
     * Comment 1 of the automatic write-back (J5): the SOURCES the triage consulted,
     * posted first so the diagnosis that follows is auditable — every claim is one
     * click from its evidence. Each line links to the document / log / file / ticket.
     */
    public String toSourcesNote() {
        StringBuilder b = new StringBuilder();
        b.append("[AI Triage · Sources consulted]\n");
        b.append("Evidence gathered for this incident — links to the exact material used:\n\n");
        if (evidence == null || evidence.isEmpty()) {
            b.append("(no external sources were consulted)\n");
        } else {
            for (Evidence e : evidence) {
                b.append("• ").append(e.source()).append(" — ").append(e.summary());
                if (e.link() != null && !e.link().isBlank()) {
                    b.append("  [").append(e.link()).append("]");
                }
                b.append("\n");
            }
        }
        b.append("\nSee the AI Triage diagnosis comment below for the interpretation of this evidence.");
        return b.toString();
    }

    /**
     * Comment 2 of the automatic write-back (J5): the first-pass DIAGNOSIS — the
     * triage's view and suggested outcome. Advisory only; it never reassigns, closes,
     * or re-prioritises the ticket. Posted after the sources comment.
     */
    public String toDiagnosisNote() {
        StringBuilder b = new StringBuilder();
        b.append("[AI Triage · First-pass diagnosis — advisory only]\n\n");
        b.append("What appears to have happened: ").append(reportedSymptom).append("\n\n");
        b.append("Likely involved systems: ");
        b.append(candidateSystems.stream()
                .map(c -> "%s (%.0f%%)".formatted(c.name(), c.confidence() * 100))
                .reduce((a, c) -> a + ", " + c).orElse("—")).append("\n");
        if (suggestedAssignment != null) {
            b.append("Suggested assignment group: ").append(suggestedAssignment.group())
             .append(" — ").append(suggestedAssignment.confidence().name().toLowerCase())
             .append(" confidence\n");
        }
        if (recommendedNextAction != null) {
            b.append("Recommended next check: ").append(recommendedNextAction).append("\n");
        }
        if (missingInformation != null && !missingInformation.isEmpty()) {
            b.append("Still missing: ").append(String.join(", ", missingInformation)).append("\n");
        }
        b.append("\nAI-assisted and advisory. No reassignment, closure, or priority change has been made — "
                + "the assigned engineer decides. Sources are in the comment above.");
        return b.toString();
    }
}
