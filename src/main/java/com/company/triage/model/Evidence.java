package com.company.triage.model;

/**
 * One piece of cited evidence (J4). Every conclusion in the report references
 * evidence by {@code id}. {@code source} is the system it came from
 * (e.g. servicenow-incident, servicenow-cmdb, confluence, sumo, gitlab).
 */
public record Evidence(
        String id,
        String source,
        String summary,
        String link
) {}
