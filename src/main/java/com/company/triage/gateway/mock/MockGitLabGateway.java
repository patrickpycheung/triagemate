package com.company.triage.gateway.mock;

import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.fixture.FixtureKeys;
import com.company.triage.gateway.fixture.FixtureSession;
import com.company.triage.gateway.fixture.FixtureStore;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock GitLab (J7 dataset) — a snapshot of the seed-repo. Returns the emitting
 * source line for a given error token so the log↔code citation (RC3) resolves to
 * payment_service.py:44 (the distinctive-token line inside reconcile()).
 */
@Component
@ConditionalOnProperty(name = "triage.connectors.gitlab", havingValue = "mock", matchIfMissing = true)
public class MockGitLabGateway implements GitLabGateway {

    private static final String G = "gitlab";
    private final FixtureStore fixtures;
    private final FixtureSession session;

    /** No fixtures — the legacy J7 dataset only. See {@link FixtureStore#none()}. */
    public MockGitLabGateway() {
        this(FixtureStore.none(), new FixtureSession());
    }

    /**
     * @Autowired is LOAD-BEARING, not decoration. With two constructors and no
     * annotation Spring picks the NO-ARG one, which wires FixtureStore.none() — every
     * fixture lookup then misses and a recorded incident comes back "incident not
     * found". Silent, and invisible to unit tests, which construct explicitly.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public MockGitLabGateway(FixtureStore fixtures, FixtureSession session) {
        this.fixtures = fixtures;
        this.session = session;
    }

    @Override
    public List<CodeSearchResult> searchCode(String project, String searchTerm) {
        fixtures.requireCapturedOrUnavailable(session.current(), G, "GitLab");
        if (fixtures.hasIncident(session.current())) {
            return fixtures.<List<CodeSearchResult>>find(session.current(), G, "searchCode",
                    FixtureKeys.of(project, searchTerm), new TypeReference<List<CodeSearchResult>>() {})
                    .orElseGet(List::of);
        }
        String t = searchTerm == null ? "" : searchTerm.toUpperCase();
        if (t.contains("PAYMENT_RECONCILE_MISMATCH") || t.contains("RECONCILE")) {
            return List.of(new CodeSearchResult(
                    "order-payments/payment-service",
                    "payment_service.py",
                    44,
                    "logger.error(  # line 43\n    \"PAYMENT_RECONCILE_MISMATCH order=%s expected=%.2f charged=%.2f\",  # line 44\n    order[\"id\"], expected, charged,\n)\nraise ValueError(\"reconcile mismatch\")  # reconcile(): discount applied after tax upstream"));
        }
        return List.of();
    }

    /** Recent committers to the implicated file since the last release (J9). */
    @Override
    public List<Contact> recentCommitters(String project, String filePath) {
        fixtures.requireCapturedOrUnavailable(session.current(), G, "GitLab");
        if (fixtures.hasIncident(session.current())) {
            return fixtures.<List<Contact>>find(session.current(), G, "recentCommitters",
                    FixtureKeys.of(project, filePath), new TypeReference<List<Contact>>() {})
                    .orElseGet(List::of);
        }
        if (!"payment_service.py".equals(filePath)) {
            return List.of();
        }
        String fileLink = "%s/%s".formatted(project, filePath);
        return List.of(
                new Contact("Priya Nair", "priya.nair@example.com", "gitlab",
                        "changed reconcile() most recently (touched the discount/tax order of operations)",
                        fileLink, "2 commits since v2.3.1 — latest 2026-07-21"),
                new Contact("Marcus Chen", "marcus.chen@example.com", "gitlab",
                        "committed the surrounding payment gateway code", fileLink,
                        "1 commit since v2.3.1 — 2026-07-18"));
    }
}
