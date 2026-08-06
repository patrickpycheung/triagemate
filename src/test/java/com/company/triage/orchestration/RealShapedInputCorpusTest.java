package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.*;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * J14/FRI-6 — the deterministic engine against the shapes REAL connectors emit.
 *
 * <p>This is what makes J14 a card rather than four commits. Every finding in it came from the
 * same place: the engine was only ever exercised against {@code MockServiceNowGateway}, whose
 * data is hand-tuned and well-formed, so each real-world shape arrived as a separate surprise —
 * a null {@code openedAt} that made the FALLBACK engine throw, a blank {@code cmdb_ci}, a
 * logger that was really the query's scope. The corpus is the artefact that stops finding
 * number six.
 *
 * <p>The assertion is deliberately one thing: a <b>J4-valid report</b>. Not a particular
 * candidate, not a particular trace line — those differ legitimately per shape. The guarantee
 * FND-63 established is that "degraded" means a weaker report, never a 500, and this engine is
 * the FND-7 fallback: if it throws, nothing catches it.
 */
class RealShapedInputCorpusTest {

    private record Shape(String name, ServiceNowGateway snow, SumoGateway sumo, GitLabGateway gitLab) {}

    private static IncidentContext base() {
        return new MockServiceNowGateway().getIncident("INC0010005");
    }

    /** A ServiceNow stub serving one incident built from a lambda over the seeded one. */
    private static ServiceNowGateway snowServing(IncidentContext ctx) {
        return new ServiceNowGateway() {
            public IncidentContext getIncident(String n) { return ctx; }
            public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
            public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
            public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) { return List.of(); }
            public void addWorkNote(String n, String note) {}
        };
    }

    private static LogEvidence line(String logger) {
        return new LogEvidence("2026-08-05T10:00:00Z", "ERROR", logger, "PAYMENT_RECONCILE_MISMATCH failed");
    }

    private static Stream<Shape> corpus() {
        IncidentContext b = base();
        return Stream.of(
                // FRI-1/FRI-2: a real ticket can carry no opened_at at all. This made the
                // FALLBACK engine throw, which is the one thing a safety net may never do.
                new Shape("openedAt == null",
                        snowServing(new IncidentContext(b.number(), b.shortDescription(), b.description(),
                                b.caller(), b.category(), b.subcategory(), null, b.environment(),
                                b.currentAssignment(), b.comments(), b.workNotes(), b.configurationItem(),
                                b.reassignmentHistory())),
                        new MockSumoGateway(), new MockGitLabGateway()),

                // J24/SFF-1 + FRI-4: before the reference-field parse was fixed this was EVERY
                // real ticket, so the subject-line fallback was silently the primary path — and
                // a hyphenated first word then mis-scoped the whole run.
                new Shape("blank cmdb_ci with a hyphenated first word",
                        snowServing(new IncidentContext(b.number(), "Track-and-Trace is down", b.description(),
                                b.caller(), b.category(), b.subcategory(), b.openedAt(), b.environment(),
                                b.currentAssignment(), b.comments(), b.workNotes(), "",
                                b.reassignmentHistory())),
                        new MockSumoGateway(), new MockGitLabGateway()),

                // FRI-3: what the gateway produced when it reported _sourcecategory as the
                // logger — one value for every line, by construction.
                new Shape("all log lines share one logger",
                        new MockServiceNowGateway(),
                        req -> List.of(line("IDT/prod/one"), line("IDT/prod/one")),
                        new MockGitLabGateway()),

                // FRI-3: and what it produces now when no emitter can be determined.
                new Shape("all log lines have a blank logger",
                        new MockServiceNowGateway(),
                        req -> List.of(line(""), line("")),
                        new MockGitLabGateway()),

                // FRI-5: one connector down must cost its own evidence, not the run.
                new Shape("a gateway that throws on one call",
                        new MockServiceNowGateway(),
                        req -> { throw new GatewayUnavailableException("Sumo Logic",
                                new IllegalStateException("401")); },
                        new MockGitLabGateway()),

                // The same, one layer out: GitLab unreachable during the sweep (J30/GEB-2).
                new Shape("GitLab unreachable during the allowlist sweep",
                        new MockServiceNowGateway(), new MockSumoGateway(),
                        new GitLabGateway() {
                            public List<CodeSearchResult> searchCode(String p, String t) {
                                throw new GatewayUnavailableException("GitLab",
                                        new IllegalStateException("403 at the perimeter"));
                            }
                            public List<Contact> recentCommitters(String p, String f) { return List.of(); }
                        })
        );
    }

    @TestFactory
    Stream<DynamicTest> everyRealShapedInputStillProducesAValidReport() {
        return corpus().map(shape -> DynamicTest.dynamicTest(shape.name(), () -> {
            var engine = new DeterministicDiagnosisEngine(shape.snow(), new MockConfluenceGateway(),
                    shape.sumo(), shape.gitLab(), TriagePropertiesFixture.deterministic());

            assertThatCode(() -> engine.diagnose("INC0010005"))
                    .as("this engine is the FND-7 fallback — if it throws, nothing catches it")
                    .doesNotThrowAnyException();
            DiagnosisResult result = engine.diagnose("INC0010005");

            // diagnose() validates internally (DiagnosisReportValidator), so reaching here
            // already proves J4-validity. Assert the parts a reader actually needs survive.
            assertThat(result.report().candidateSystems())
                    .as("degraded means a WEAKER report, never an empty one")
                    .isNotEmpty();
            assertThat(result.report().evidence()).isNotEmpty();
            assertThat(result.report().advisory()).isTrue();
        }));
    }
}
