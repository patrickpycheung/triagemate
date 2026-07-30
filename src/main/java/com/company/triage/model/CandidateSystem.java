package com.company.triage.model;

import java.util.List;

/**
 * A candidate affected system in the ranked shortlist (J4). Never a single forced
 * answer — the report carries several, each with a 0..1 confidence and the evidence
 * that supports it.
 */
public record CandidateSystem(
        String name,
        double confidence,
        List<String> evidenceRefs
) {}
