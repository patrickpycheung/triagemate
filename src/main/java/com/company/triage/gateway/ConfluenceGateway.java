package com.company.triage.gateway;

import com.company.triage.model.Contact;
import com.company.triage.model.KnowledgeDoc;

import java.util.List;

/**
 * Confluence knowledge search (J6). Best-effort: on failure the run degrades
 * gracefully (evidence simply omitted). Real impl: CQL search + page REST
 * (reuse the auspost-mcp Confluence client).
 */
public interface ConfluenceGateway {
    List<KnowledgeDoc> search(String query);

    /**
     * Who to talk to about a cited page (J9): its author and last editor(s). Called
     * only for pages the triage already used as evidence — no broad people-search.
     * Best-effort: returns an empty list on any error.
     */
    default List<Contact> contributors(KnowledgeDoc doc) {
        return List.of();
    }
}
