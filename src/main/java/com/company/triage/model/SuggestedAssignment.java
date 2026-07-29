package com.company.triage.model;

import java.util.List;

/** The suggested owning team — advisory, evidence-backed, never auto-applied (J4/J5). */
public record SuggestedAssignment(
        String group,
        Confidence confidence,
        List<String> evidenceRefs
) {}
