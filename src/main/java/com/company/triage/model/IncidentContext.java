package com.company.triage.model;

import java.time.OffsetDateTime;
import java.util.List;

/** Everything the ServiceNow gateway retrieves about the current incident (J5, Step 1). */
public record IncidentContext(
        String number,
        String shortDescription,
        String description,
        String caller,
        String category,
        String subcategory,
        OffsetDateTime openedAt,
        String environment,
        String currentAssignment,
        List<String> comments,
        List<String> workNotes,
        String configurationItem,
        List<String> reassignmentHistory
) {
    /**
     * A copy with this app's own advisory notes removed from both journals (J27).
     *
     * <p>FND-67 — the app reading its own output back as human ticket conversation — was
     * closed on the deterministic path by filtering inside {@code IncidentSignals} and
     * {@code MentionedPeople}. The ADK path was not covered: {@code TriageMateTools} handed
     * this record to the model whole, while the agent instruction explicitly tells it to read
     * the comments and work notes. A second diagnosis of the same incident therefore consumed
     * the first one's notes as ordinary conversation (FND-86).
     *
     * <p>The filter belongs at the tool boundary rather than in the prompt, so it holds
     * regardless of what the model decides to do — the same reasoning as J8's capability
     * bound. The gateway is deliberately left alone: callers that legitimately need the raw
     * journal (rendering, auditing) still get it.
     */
    public IncidentContext withoutAiAuthoredNotes() {
        return new IncidentContext(
                number, shortDescription, description, caller, category, subcategory,
                openedAt, environment, currentAssignment,
                withoutAiNotes(comments), withoutAiNotes(workNotes),
                configurationItem, reassignmentHistory);
    }

    /** Null-safe: a null journal stays null rather than becoming an empty list. */
    private static List<String> withoutAiNotes(List<String> journal) {
        return journal == null ? null
                : journal.stream().filter(e -> !DiagnosisReport.isAiAuthoredNote(e)).toList();
    }
}
