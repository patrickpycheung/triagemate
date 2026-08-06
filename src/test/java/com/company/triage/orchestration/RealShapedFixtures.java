package com.company.triage.orchestration;

import com.company.triage.gateway.GatewayUnavailableException;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.gateway.mock.MockServiceNowGateway;
import com.company.triage.gateway.mock.MockSumoGateway;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import com.company.triage.model.NewIncident;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * J14/FRI-6 — the real-shaped fixture corpus.
 *
 * <p>Every shape here was found by reading what the REAL connectors actually return, not by
 * imagining an edge case. None of them occur on the seeded demo path (J7), and each one occurs
 * on the first arbitrary real incident — which is the only situation the FND-7 fallback engine
 * exists for. The corpus exists so this class of defect stays closed: a new real-shaped field
 * gets a shape here rather than re-opening J14.
 *
 * <p>The shapes deliberately differ from {@code MockServiceNowGateway}/{@code MockSumoGateway}
 * ONLY in the field under test. The mock's dataset is otherwise reused wholesale, so a failure
 * points at the shape rather than at fixture drift — the mock-fidelity lesson from FND-47,
 * FND-61 and FND-63.
 */
final class RealShapedFixtures {

    private RealShapedFixtures() {}

    /** The mock's own incident number, so its precedents and ownership still resolve. */
    static final String INCIDENT = "INC0010005";

    // ---------------------------------------------------------------- ServiceNow shapes

    /**
     * A ServiceNow gateway that serves the mock dataset with one field rewritten.
     *
     * <p>Delegation rather than a hand-built context: {@code findSimilarIncidents} and
     * {@code findOwnership} keep their real dataset behaviour, so the only variable is the shape.
     */
    static ServiceNowGateway serviceNowWith(UnaryOperator<IncidentContext> reshape) {
        MockServiceNowGateway delegate = new MockServiceNowGateway();
        return new ServiceNowGateway() {
            @Override
            public IncidentContext getIncident(String number) {
                return reshape.apply(delegate.getIncident(number));
            }

            @Override
            public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
                return delegate.findIncidentsCreatedSince(since, limit);
            }

            @Override
            public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
                return delegate.findSimilarIncidents(incident);
            }

            @Override
            public Optional<ServiceOwnership> findOwnership(String applicationName) {
                return delegate.findOwnership(applicationName);
            }

            @Override
            public void addWorkNote(String number, String workNote) {
                delegate.addWorkNote(number, workNote);
            }
        };
    }

    /**
     * Shape 1 — {@code openedAt == null}.
     *
     * <p>J14/FRI-1: {@code opened_at} is a display-formatted string in a real ServiceNow
     * response, so a parse miss (or a genuinely blank field on a machine-created ticket)
     * leaves the anchor absent. The mock always supplies a parseable timestamp, so nothing on
     * the demo path ever exercises the null branch that the log window is derived from.
     */
    static IncidentContext withoutOpenedAt(IncidentContext c) {
        return new IncidentContext(
                c.number(), c.shortDescription(), c.description(), c.caller(),
                c.category(), c.subcategory(),
                null,
                c.environment(), c.currentAssignment(), c.comments(), c.workNotes(),
                c.configurationItem(), c.reassignmentHistory());
    }

    /**
     * Shape 2 — blank {@code cmdb_ci} AND a hyphenated first word in the short description.
     *
     * <p>The two travel together on purpose. J14/FRI-4: with no CI, the leading phrase of the
     * short description is the only system signal left, and a hyphen-splitting
     * {@code leadingPhrase} reduced {@code "E-commerce checkout down for EU users"} to the bare
     * token {@code "E"} — under three characters, so the allowlist ranker got zero signal and
     * the fallback candidate rendered as a row literally named "E" in the UI and in the
     * ServiceNow advisory note. Splitting the two shapes apart would hide that interaction.
     */
    static IncidentContext blankCiWithHyphenatedFirstWord(IncidentContext c) {
        return new IncidentContext(
                c.number(),
                "E-commerce checkout down for EU users",
                c.description(), c.caller(), c.category(), c.subcategory(), c.openedAt(),
                c.environment(), c.currentAssignment(), c.comments(), c.workNotes(),
                "",
                c.reassignmentHistory());
    }

    // ---------------------------------------------------------------- Sumo shapes

    /**
     * A Sumo gateway that returns the seeded window with every line's {@code logger} rewritten.
     *
     * <p>Shapes 3 and 4 both come from the same real-world fact: {@code _sourceName} /
     * {@code logger} is a per-deployment convention, not a guarantee. The mock window carries
     * two distinct, conveniently application-shaped logger values, so the demo path never sees
     * either degenerate case.
     */
    static SumoGateway sumoWithLogger(String logger) {
        MockSumoGateway delegate = new MockSumoGateway();
        return (LogSearchRequest request) -> delegate.search(request).stream()
                .map(e -> new LogEvidence(e.time(), e.level(), logger, e.message()))
                .toList();
    }

    // ---------------------------------------------------------------- failing-connector shape

    /**
     * Shape 5a — a ServiceNow gateway that throws on <b>one</b> call
     * ({@code findSimilarIncidents}), every other call healthy.
     *
     * <p>This is the shape the J14 review flagged as still un-degraded:
     * {@code DeterministicDiagnosisEngine:151} calling {@code RealServiceNowGateway:130},
     * which aborts rather than degrading. A real token expiring, or one ACL on the
     * similar-incident query, produces exactly this — the connector is reachable, one call
     * is not.
     */
    static ServiceNowGateway serviceNowThatFailsSimilarIncidents() {
        MockServiceNowGateway delegate = new MockServiceNowGateway();
        return new ServiceNowGateway() {
            @Override
            public IncidentContext getIncident(String number) {
                return delegate.getIncident(number);
            }

            @Override
            public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
                return delegate.findIncidentsCreatedSince(since, limit);
            }

            @Override
            public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
                throw new GatewayUnavailableException("ServiceNow",
                        new IllegalStateException("401 Unauthorized — token expired mid-run"));
            }

            @Override
            public Optional<ServiceOwnership> findOwnership(String applicationName) {
                return delegate.findOwnership(applicationName);
            }

            @Override
            public void addWorkNote(String number, String workNote) {
                delegate.addWorkNote(number, workNote);
            }
        };
    }

    /**
     * Shape 5b — the same shape on a call where per-call degradation ALREADY holds.
     *
     * <p>{@code gitlab.searchCode} ({@code DeterministicDiagnosisEngine:214}) was one of the
     * aborting calls in the same review row as 5a, and J14/FRI-5 closed it. Keeping it in the
     * corpus is the regression half: it pins the degradation that exists, so 5a's failure is
     * demonstrably about the un-degraded call and not about throwing connectors in general.
     */
    static GitLabGateway gitLabThatFailsCodeSearch() {
        return new GitLabGateway() {
            @Override
            public List<CodeSearchResult> searchCode(String project, String searchTerm) {
                throw new GatewayUnavailableException("GitLab",
                        new IllegalStateException("403 Forbidden — search API disabled for token"));
            }

            @Override
            public List<Contact> recentCommitters(String project, String filePath) {
                return List.of();
            }
        };
    }
}
