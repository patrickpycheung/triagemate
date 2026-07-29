package com.company.triage.gateway;

import com.company.triage.model.CodeSearchResult;

import java.util.List;

/**
 * GitLab — targeted, last-resort code search (J6). Only invoked once logs/docs
 * yield a concrete term. Searches one allowlisted project and returns matching
 * lines — never clones. Real impl: GitLab search/file REST (reuse the auspost-mcp
 * gitlab4j client).
 */
public interface GitLabGateway {
    List<CodeSearchResult> searchCode(String project, String searchTerm);
}
