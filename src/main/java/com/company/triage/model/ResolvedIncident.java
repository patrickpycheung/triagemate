package com.company.triage.model;

/**
 * A previously resolved incident with similar symptoms (J5) — often the strongest
 * routing signal. {@code similarity} is a 0..1 score from the search.
 */
public record ResolvedIncident(
        String number,
        String shortDescription,
        String resolutionGroup,
        String resolutionCode,
        String resolutionNotes,
        double similarity
) {}
