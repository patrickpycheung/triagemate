package com.company.triage.model;

/**
 * A targeted GitLab code-search hit (J6). {@code line} is where the search term was
 * found — used for the log↔code citation (RC3): matching a runtime log line to its
 * emitting source statement at file:line.
 */
public record CodeSearchResult(
        String project,
        String filePath,
        int line,
        String snippet
) {}
