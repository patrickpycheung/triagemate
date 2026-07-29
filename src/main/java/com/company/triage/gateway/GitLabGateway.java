package com.company.triage.gateway;

import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;

import java.util.List;

/**
 * GitLab — targeted, last-resort code search (J6). Only invoked once logs/docs
 * yield a concrete term. Searches one allowlisted project and returns matching
 * lines — never clones. Real impl: GitLab search/file REST (reuse the auspost-mcp
 * gitlab4j client).
 */
public interface GitLabGateway {
    List<CodeSearchResult> searchCode(String project, String searchTerm);

    /**
     * Who to talk to about the implicated source (J9): the recent committers to a
     * specific file, from git history since the last release/tag. Called only for a
     * file the triage already implicated via a log↔code citation — no repo-wide scan.
     * Best-effort: returns an empty list on any error.
     */
    default List<Contact> recentCommitters(String project, String filePath) {
        return List.of();
    }
}
