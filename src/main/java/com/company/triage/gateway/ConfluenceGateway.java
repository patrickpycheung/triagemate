package com.company.triage.gateway;

import com.company.triage.model.KnowledgeDoc;

import java.util.List;

/**
 * Confluence knowledge search (J6). Best-effort: on failure the run degrades
 * gracefully (evidence simply omitted). Real impl: CQL search + page REST
 * (reuse the auspost-mcp Confluence client).
 */
public interface ConfluenceGateway {
    List<KnowledgeDoc> search(String query);
}
