package com.company.triage.model;

/** A Confluence page / knowledge article hit (J6). */
public record KnowledgeDoc(
        String id,
        String title,
        String url,
        String snippet
) {}
