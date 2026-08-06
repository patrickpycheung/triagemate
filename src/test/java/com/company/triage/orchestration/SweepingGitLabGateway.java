package com.company.triage.orchestration;

import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * J31/ASO-3 — a {@link GitLabGateway} whose behaviour varies <b>by project</b>.
 *
 * <p>Every existing double in these tests is <b>uniform</b>: it throws for every project, or
 * returns empty for every project, ignoring the {@code project} argument entirely. That is
 * why the defects J31 describes survived J30/GEB-3's own verification — a uniform stub cannot
 * express a MIXED sweep (project A searched and empty, project B unreachable), so every
 * assertion about "the sweep" was really an assertion about one repeated call.
 *
 * <p>This is J14/FRI-6's lesson applied to the sweep: the fixture is the artefact that stops
 * finding number six, and a test double that cannot represent the failure shape is a test
 * double that guarantees the shape stays unfound.
 *
 * <p>It also <b>records the projects it was asked for, in order</b>. Asserting that a later
 * project was reached is the only direct evidence that a failed one did not end the sweep;
 * inferring it from the report conflates "continued" with "reported correctly", which are the
 * two separate things ASO-1 and ASO-2 fix.
 */
final class SweepingGitLabGateway implements GitLabGateway {

    /** What this gateway does when asked about one particular project. */
    sealed interface Outcome {
        /** The project was searched and matched. */
        record Hits(List<CodeSearchResult> results) implements Outcome {}
        /** The project was searched successfully and matched nothing — a real answer. */
        record Empty() implements Outcome {}
        /** The project could not be searched at all (404, perimeter block, timeout). */
        record Unreachable(String because) implements Outcome {}
    }

    private final Map<String, Outcome> byProject = new LinkedHashMap<>();
    private final Outcome fallback;
    private final List<String> asked = new ArrayList<>();

    private SweepingGitLabGateway(Map<String, Outcome> byProject, Outcome fallback) {
        this.byProject.putAll(byProject);
        this.fallback = fallback;
    }

    /** Builder entry point: unnamed projects fall through to {@code Empty}. */
    static Builder where() { return new Builder(); }

    static final class Builder {
        private final Map<String, Outcome> map = new LinkedHashMap<>();
        private Outcome fallback = new Outcome.Empty();

        Builder project(String project, Outcome outcome) { map.put(project, outcome); return this; }
        Builder unreachable(String project) { return project(project, new Outcome.Unreachable("404 Project Not Found")); }
        Builder empty(String project) { return project(project, new Outcome.Empty()); }

        Builder hit(String project, String filePath, int line) {
            return project(project, new Outcome.Hits(
                    List.of(new CodeSearchResult(project, filePath, line, "the emitting line"))));
        }

        Builder otherwise(Outcome o) { this.fallback = o; return this; }
        SweepingGitLabGateway build() { return new SweepingGitLabGateway(map, fallback); }
    }

    @Override
    public List<CodeSearchResult> searchCode(String project, String searchTerm) {
        asked.add(project);
        Outcome outcome = byProject.getOrDefault(project, fallback);
        if (outcome instanceof Outcome.Unreachable u) {
            throw new GatewayUnavailableException("GitLab", new IllegalStateException(u.because()));
        }
        return outcome instanceof Outcome.Hits h ? h.results() : List.of();
    }

    @Override
    public List<Contact> recentCommitters(String project, String filePath) { return List.of(); }

    /** The projects this gateway was asked about, in call order. */
    List<String> projectsAsked() { return List.copyOf(asked); }
}
