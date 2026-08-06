package com.company.triage.gateway.fixture;

import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.model.CodeSearchResult;
import com.company.triage.model.Contact;
import com.company.triage.model.IncidentContext;
import com.company.triage.model.KnowledgeDoc;
import com.company.triage.model.LogEvidence;
import com.company.triage.model.LogSearchRequest;
import com.company.triage.model.NewIncident;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.ServiceOwnership;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Pass-through decorators that write every real gateway answer to a {@link FixtureStore}.
 *
 * <p>They are pure observers: each method delegates, records, and returns the delegate's
 * result unchanged. A recording failure is swallowed inside the store, so turning
 * recording on can slow a real run down slightly but can never change its outcome.
 *
 * <p>WRITES ARE NOT RECORDED. {@code addWorkNote} is a mutation of a real ticket and has
 * no interesting response to replay; the mock keeps its own idempotency behaviour.
 * {@code findIncidentsCreatedSince} is likewise skipped — it is the poller's cursor query,
 * whose answer depends on wall-clock time and would replay as a stale, self-triggering
 * list rather than as evidence.
 */
public final class RecordingGateways {

    private RecordingGateways() {}

    // ------------------------------------------------------------ ServiceNow

    public record ServiceNow(ServiceNowGateway delegate, FixtureStore store, FixtureSession session)
            implements ServiceNowGateway {

        private static final String G = "servicenow";

        @Override
        public IncidentContext getIncident(String number) {
            // Latch first: if the call throws (unknown incident), later gateways in the same
            // run should still file under the incident the operator asked for.
            session.set(number);
            IncidentContext result = delegate.getIncident(number);
            store.record(session.current(), G, "getIncident", FixtureKeys.of(number), result);
            return result;
        }

        @Override
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
            return delegate.findIncidentsCreatedSince(since, limit);   // not recorded — see class javadoc
        }

        @Override
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext incident) {
            List<ResolvedIncident> result = delegate.findSimilarIncidents(incident);
            store.record(session.current(), G, "findSimilarIncidents",
                    FixtureKeys.of(incident == null ? "" : incident.number()), result);
            return result;
        }

        @Override
        public Optional<ServiceOwnership> findOwnership(String applicationName) {
            Optional<ServiceOwnership> result = delegate.findOwnership(applicationName);
            // Recorded as the unwrapped value (null when absent) so the fixture reads as
            // "this is what ServiceNow knows", not as a serialised Optional wrapper.
            store.record(session.current(), G, "findOwnership",
                    FixtureKeys.of(applicationName), result.orElse(null));
            return result;
        }

        @Override
        public void addWorkNote(String number, String workNote) {
            delegate.addWorkNote(number, workNote);                    // not recorded — it is a write
        }
    }

    // ------------------------------------------------------------ Confluence

    public record Confluence(ConfluenceGateway delegate, FixtureStore store, FixtureSession session)
            implements ConfluenceGateway {

        private static final String G = "confluence";

        @Override
        public List<KnowledgeDoc> search(String query) {
            List<KnowledgeDoc> result = delegate.search(query);
            store.record(session.current(), G, "search", FixtureKeys.of(query), result);
            return result;
        }

        @Override
        public List<Contact> contributors(KnowledgeDoc doc) {
            List<Contact> result = delegate.contributors(doc);
            store.record(session.current(), G, "contributors",
                    FixtureKeys.of(doc == null ? "" : doc.id()), result);
            return result;
        }
    }

    // ------------------------------------------------------------------ Sumo

    public record Sumo(SumoGateway delegate, FixtureStore store, FixtureSession session)
            implements SumoGateway {

        private static final String G = "sumo";

        @Override
        public List<LogEvidence> search(LogSearchRequest request) {
            List<LogEvidence> result = delegate.search(request);
            store.record(session.current(), G, "search", FixtureKeys.forSumo(request), result);
            return result;
        }
    }

    // ---------------------------------------------------------------- GitLab

    public record GitLab(GitLabGateway delegate, FixtureStore store, FixtureSession session)
            implements GitLabGateway {

        private static final String G = "gitlab";

        @Override
        public List<CodeSearchResult> searchCode(String project, String searchTerm) {
            List<CodeSearchResult> result = delegate.searchCode(project, searchTerm);
            store.record(session.current(), G, "searchCode",
                    FixtureKeys.of(project, searchTerm), result);
            return result;
        }

        @Override
        public List<Contact> recentCommitters(String project, String filePath) {
            List<Contact> result = delegate.recentCommitters(project, filePath);
            store.record(session.current(), G, "recentCommitters",
                    FixtureKeys.of(project, filePath), result);
            return result;
        }
    }
}
