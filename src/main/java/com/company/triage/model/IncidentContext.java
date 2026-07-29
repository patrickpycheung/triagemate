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
) {}
