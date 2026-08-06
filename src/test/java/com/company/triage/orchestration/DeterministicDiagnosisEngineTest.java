package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.Contact;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.KnowledgeDoc;
import com.company.triage.orchestration.trace.Platform;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceCollector;
import com.company.triage.orchestration.trace.TraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (offline) smoke test of the bounded flow over the mock dataset (J7).
 * Proves the demo-critical outcomes without any network or LLM.
 */
class DeterministicDiagnosisEngineTest {

    private final DeterministicDiagnosisEngine engine = new DeterministicDiagnosisEngine(
            new MockServiceNowGateway(), new MockConfluenceGateway(),
            new MockSumoGateway(), new MockGitLabGateway(),
            TriagePropertiesFixture.deterministic());

    /**
     * FND-54: the mock used to echo ANY number into the seeded context, so a stage typo
     * produced a confident, complete diagnosis of an incident that does not exist — and it
     * made FND-48's 404 unreachable in the demo config. The dataset models exactly one
     * incident; anything else must be a clean not-found.
     */
    @Test
    void unknownIncidentNumberIsRejectedNotFabricated() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.diagnose("INC9999999"))
                .isInstanceOf(com.company.triage.gateway.IncidentNotFoundException.class)
                .hasMessageContaining("INC9999999");
    }

    @Test
    void diagnosesTheSeededIncidentEndToEnd() {
        DiagnosisResult result = engine.diagnose("INC0010005");
        DiagnosisReport r = result.report();

        // Advisory, never mutating
        assertThat(r.advisory()).isTrue();

        // Correct app in the candidate shortlist
        assertThat(r.candidateSystems()).extracting("name").contains("Payment Service");

        // Correct owning team suggested
        assertThat(r.suggestedAssignment().group()).isEqualTo("Payments Platform Support");

        // Log↔code citation resolved to payment_service.py:44 (RC3)
        assertThat(r.evidence()).anySatisfy(e ->
                assertThat(e.summary()).contains("payment_service.py:44"));

        // Evidence spans multiple sources (ServiceNow + Confluence + Sumo + GitLab)
        assertThat(r.evidence()).extracting("source")
                .contains("servicenow-incident", "confluence", "sumo", "gitlab");

        // Who-to-talk-to (J9): contacts gathered from wiki authors + recent committers
        assertThat(r.suggestedContacts()).isNotEmpty();
        assertThat(r.suggestedContacts()).extracting(Contact::source)
                .anyMatch(s -> s.contains("confluence"))
                .anyMatch(s -> s.contains("gitlab"));

        // FND-64: corroboration across ALL THREE name-bearing sources ranks first. Priya is
        // named in a ticket work note, edited KB001234, and committed payment_service.py.
        // Was "confluence+gitlab" before ServiceNow contributed names at all.
        Contact top = r.suggestedContacts().get(0);
        assertThat(top.name()).isEqualTo("Priya Nair");
        assertThat(top.source()).isEqualTo("servicenow+confluence+gitlab");
        // Merged from a prose mention (no handle) plus API records — the handle must survive.
        assertThat(top.handle()).isEqualTo("priya.nair@example.com");

        // All three sources contribute; Sumo deliberately contributes none (no identity in logs).
        assertThat(r.suggestedContacts()).extracting(Contact::source)
                .anyMatch(s -> s.contains("servicenow"))
                .anyMatch(s -> s.contains("confluence"))
                .anyMatch(s -> s.contains("gitlab"));
        assertThat(r.suggestedContacts()).extracting(Contact::source)
                .noneMatch(s -> s.contains("sumo"));

        // Names extracted from FREE TEXT, not just API metadata: Priya from a ticket work
        // note, Marcus from the runbook's "Escalation contact:" prose.
        assertThat(r.suggestedContacts()).extracting(Contact::name).contains("Marcus Chen");
        // The ticket's own commenters, from the journal author prefix.
        assertThat(r.suggestedContacts()).extracting(Contact::name).contains("jane.customer", "m.chen");

        // Trace shows the tools were actually consulted
        assertThat(result.trace()).anyMatch(s -> s.startsWith("sumo.search"));
        assertThat(result.trace()).anyMatch(s -> s.startsWith("contacts:"));
    }

    /**
     * FND-59: the Confluence query used to be the fixed literal
     * "checkout order payment reconcile 500" for every incident — it only ever looked
     * correct because it happened to match the one seeded demo incident's keywords via
     * MockConfluenceGateway's containment check. This pins the query to what the incident
     * actually says (shortDescription + configurationItem), same as the orderId/scope/window
     * derivations the engine already does for similar-incidents and Sumo. A spy gateway
     * captures the exact string passed, independent of what any mock's matching logic accepts.
     */
    @Test
    void confluenceQueryIsDerivedFromTheIncidentNotHardcoded() {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var delegate = new MockConfluenceGateway();
        ConfluenceGateway spy = new ConfluenceGateway() {
            @Override
            public List<KnowledgeDoc> search(String query) {
                captured.set(query);
                return delegate.search(query);   // keep the downstream evidence/justification wiring intact
            }
        };
        var engineWithSpy = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), spy,
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        engineWithSpy.diagnose("INC0010005");

        // J27: the query is the seeded incident's own configurationItem, alone. The property
        // this test was filed for (FND-59) is unchanged — the query is DERIVED from the
        // incident rather than being a fixed literal — only its form narrowed from
        // "shortDescription + configurationItem" to the system name on its own.
        assertThat(captured.get())
                .isEqualTo("Order Portal")                         // from configurationItem
                .doesNotContain("reconcile", "discount", "500");   // the old fixed literal's terms
    }

    /**
     * J14 — the fallback engine must not throw on a real ticket whose {@code opened_at}
     * did not parse.
     *
     * <p>{@code openedAt} anchors the ±10m Sumo window, and it was dereferenced unguarded.
     * Against a real instance it can be null: reads use {@code sysparm_display_value=true},
     * so ServiceNow renders datetimes in the requesting user's display format, and anything
     * {@code parseTime} did not recognise became null silently. The NPE landed <b>inside the
     * FND-7 fallback</b> — the one code path whose entire job is to still work when the ADK
     * engine has already failed — so the orchestrator would degrade to it and then return a
     * 500. That is the worst failure shape this app has, and no test covered it because every
     * fixture supplied a parseable date.
     *
     * <p>Asserts the honest degrade, not just the absence of a throw: the search must be
     * reported as SKIPPED with its reason, and {@code missingInformation} must say logs were
     * never searched rather than claiming a clean empty match (the FND-8 class).
     */
    @Test
    void survivesAnIncidentWhoseOpenedAtIsNull() {
        var engineForUndatedIncident = new DeterministicDiagnosisEngine(
                undatedIncidentGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        var result = engineForUndatedIncident.diagnose("INC0010010");

        DiagnosisReport report = result.report();
        assertThat(report).isNotNull();
        // The guarantee is unchanged: a real ticket whose date did not parse still yields a
        // report rather than an exception out of the FALLBACK engine.
        //
        // What CHANGED (operator instruction, 2026-08-05): the log window is now the last 24
        // hours rather than ±10m around opened_at, so the search no longer depends on that
        // field at all. The original J14/FRI-2 remedy — skip the search, disclose why — is
        // therefore obsolete for this cause, and asserting it would pin behaviour the design
        // has moved past. Nothing dereferences openedAt on this path now, which is a stronger
        // guarantee than the guard it replaces.
        assertThat(result.trace())
                .as("the search runs on a time window that no longer needs opened_at")
                .anySatisfy(line -> assertThat(line).contains("sumo.search"));
    }

    /** A real-shaped ticket whose {@code opened_at} failed to parse (J14). */
    private static com.company.triage.gateway.ServiceNowGateway undatedIncidentGateway() {
        return new com.company.triage.gateway.ServiceNowGateway() {
            @Override
            public com.company.triage.model.IncidentContext getIncident(String number) {
                return new com.company.triage.model.IncidentContext(
                        number,
                        "Hazards being recorded on handheld are not appearing in Delivery Hazards application",
                        "See attached for details.",
                        "Adela Cervantsz", "Inquiry / Help", null,
                        null,                       // <-- opened_at did not parse
                        null, null,
                        List.of(), List.of(), "Delivery Hazards", List.of());
            }
            @Override public List<com.company.triage.model.ResolvedIncident> findSimilarIncidents(
                    com.company.triage.model.IncidentContext c) { return List.of(); }
            @Override public java.util.Optional<com.company.triage.model.ServiceOwnership> findOwnership(String a) {
                return java.util.Optional.empty();
            }
            @Override public void addWorkNote(String number, String note) {}
            @Override public List<com.company.triage.model.NewIncident> findIncidentsCreatedSince(
                    java.time.OffsetDateTime since, int limit) { return List.of(); }
        };
    }

    /** An incident with nothing to do with the seeded demo scenario. */
    private static com.company.triage.gateway.ServiceNowGateway unrelatedIncidentGateway() {
        return new com.company.triage.gateway.ServiceNowGateway() {
            @Override
            public com.company.triage.model.IncidentContext getIncident(String number) {
                return new com.company.triage.model.IncidentContext(
                        number,
                        "Nightly invoice export to the ledger is failing",
                        "The finance batch job aborts partway. Correlation id BATCH-778812.",
                        "finance.ops", "Software", "Batch failure",
                        java.time.OffsetDateTime.parse("2026-07-30T02:00:00+10:00"),
                        "Production", "Finance Systems",
                        List.of(), List.of(), "Ledger Export Service", List.of());
            }
            @Override public List<com.company.triage.model.ResolvedIncident> findSimilarIncidents(
                    com.company.triage.model.IncidentContext c) { return List.of(); }
            @Override public java.util.Optional<com.company.triage.model.ServiceOwnership> findOwnership(String a) {
                return java.util.Optional.empty();
            }
            @Override public void addWorkNote(String number, String note) {}
            @Override public List<com.company.triage.model.NewIncident> findIncidentsCreatedSince(
                    java.time.OffsetDateTime since, int limit) { return List.of(); }
        };
    }

    /**
     * FND-63 — the bug that made this engine unusable as the FND-7 fallback.
     *
     * <p>The report's candidateSystems and their evidenceRefs were hardcoded, and two refs
     * ({@code e-kb-KB001234}, {@code e-sim-INC0011902}) were literal ids from the seeded demo
     * fixture. For any other incident those Evidence entries don't exist, so
     * {@code DiagnosisReportValidator}'s dangling-evidenceRef rule threw — meaning the
     * orchestrator would degrade to this engine and then get a 500 out of it, defeating FND-7
     * precisely when it mattered. Nothing caught it because every existing test used the one
     * seeded incident, for which the hardcoded ids happen to resolve.
     */
    @Test
    void producesAValidReportForAnIncidentUnrelatedToTheSeededScenario() {
        var engineForOtherIncident = new DeterministicDiagnosisEngine(
                unrelatedIncidentGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        // Must not throw — the J4 validator runs inside diagnose().
        DiagnosisResult result = engineForOtherIncident.diagnose("INC0077777");
        DiagnosisReport r = result.report();

        // FND-63: the narrative must describe THIS incident, not the demo's.
        assertThat(r.reportedSymptom()).contains("invoice export");
        assertThat(r.reportedSymptom()).doesNotContain("checkout", "reconcile");
        assertThat(r.recommendedNextAction()).doesNotContain("payment_service.py");

        // FND-62: the identifier is extracted despite not matching the demo's INC-ORD- shape.
        //
        // J13/ECI-5 (2026-08-06): it lands in orderId, not correlationId. This previously
        // asserted correlationId because the engine wrote the SAME string into both fields
        // regardless of what it was — so the assertion passed without saying anything about
        // typing. BATCH-778812 matches DASHED_ID, i.e. a business reference, and the card maps
        // DASHED_ID -> orderId with UUID/HEX_TRACE -> correlationId. A null correlationId here
        // is the honest answer: nothing in this ticket determined one.
        assertThat(r.identifiers().orderId()).isEqualTo("BATCH-778812");
        assertThat(r.identifiers().correlationId())
                .as("no correlation id was determined — null is honest, and a wrong TYPE is not")
                .isNull();

        // Every evidenceRef must resolve to Evidence actually in this report (the rule that
        // used to throw). Belt and braces alongside the validator inside diagnose().
        var ids = r.evidence().stream().map(com.company.triage.model.Evidence::id).toList();
        assertThat(r.candidateSystems()).allSatisfy(c -> assertThat(ids).containsAll(c.evidenceRefs()));
        assertThat(ids).containsAll(r.suggestedAssignment().evidenceRefs());

        // No ownership and no similar incidents for this one — say so rather than inventing
        // the demo's "Payments Platform Support".
        assertThat(r.suggestedAssignment().group()).doesNotContain("Payments Platform Support");
        assertThat(r.missingInformation()).isNotEmpty();
    }

    /**
     * STREAM-003 review remediation (TASK-008 test gap): the existing trace-string
     * assertions above only prove the OLD {@code List<String> trace} field didn't
     * regress — they say nothing about the NEW {@link TraceStep} emissions {@code
     * emitStep} adds alongside every trace line. This drives {@code diagnose(incidentNumber,
     * sink)} through a real {@link TraceCollector} (the same collector {@code
     * DiagnosisOrchestrator} uses in production) and asserts the resulting steps have the
     * correct platform/state/callId/durationMs shape — for a ServiceNow tool-mapped step
     * ({@code servicenow.getIncident} → {@link Platform#SERVICENOW}) and a TRIAGEMATE
     * pseudo-platform step ({@code understand:} / {@code contacts:}).
     */
    @Test
    void diagnoseEmitsTraceStepsWithCorrectShapeThroughATraceCollector() {
        TraceCollector collector = new TraceCollector();

        DiagnosisResult result = engine.diagnose("INC0010005", collector.forAttempt(0));

        // Sanity: the collector's steps match up with the result the caller gets back.
        assertThat(result.report().incidentNumber()).isEqualTo("INC0010005");

        List<TraceStep> steps = collector.steps();
        assertThat(steps).isNotEmpty();

        // Every emitted step must be DONE — this engine runs synchronously, so nothing
        // should ever be left stuck ACTIVE. FAILED/ABANDONED are not reachable on this
        // engine's happy path. (gitlab.searchCode used to be conditionally absent here;
        // it now always emits — see everyPlatformAppearsInTheTraceEvenWhenACallIsSkipped.)
        assertThat(steps).allSatisfy(step -> assertThat(step.state()).isIn(StepState.DONE));

        // callId follows the synthetic "det-<seq>" convention (LT1 §67), one per emitted
        // step, with no gaps or duplicates.
        assertThat(steps).extracting(TraceStep::callId)
                .allMatch(id -> id.matches("det-\\d+"))
                .doesNotHaveDuplicates();

        // A ServiceNow tool-mapped step: servicenow.getIncident -> Platform.SERVICENOW,
        // with a real (non-null) measured duration and its result text carried through.
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.tool()).isEqualTo("servicenow.getIncident");
            assertThat(step.platform()).isEqualTo(Platform.SERVICENOW);
            assertThat(step.state()).isEqualTo(StepState.DONE);
            assertThat(step.durationMs()).isNotNull();
            assertThat(step.durationMs()).isGreaterThanOrEqualTo(0L);
            assertThat(step.result()).contains("servicenow.getIncident");
            assertThat(step.callId()).matches("det-\\d+");
        });

        // A TRIAGEMATE pseudo-platform step: understand: -> internal bookkeeping, not a
        // real integration call.
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.tool()).isEqualTo("understand:");
            assertThat(step.platform()).isEqualTo(Platform.TRIAGEMATE);
            assertThat(step.state()).isEqualTo(StepState.DONE);
            assertThat(step.durationMs()).isNotNull();
        });

        // A second TRIAGEMATE step: contacts: — proves this engine emits more than one
        // TRIAGEMATE-mapped step, not just understand:.
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.tool()).isEqualTo("contacts:");
            assertThat(step.platform()).isEqualTo(Platform.TRIAGEMATE);
            assertThat(step.state()).isEqualTo(StepState.DONE);
        });

        // Every step carries attempt 0 (stamped by collector.forAttempt(0)) and belongs to
        // the DETERMINISTIC engine.
        assertThat(steps).allMatch(step -> step.attempt() == 0);
        assertThat(steps).allMatch(step -> step.engine() == DiagnosisResult.Engine.DETERMINISTIC);
    }

    /**
     * All four connected systems must be visible in the trace of a normal run — the demo's
     * whole point is showing which systems were consulted, and a system that silently
     * contributes nothing to the trace reads as one the product doesn't integrate with.
     *
     * <p>Three separate gaps used to break this: {@code gitlab.searchCode} emitted nothing
     * at all when the log lines carried no error token to search for, and BOTH contact
     * lookups ({@code confluence.contributors}, {@code gitlab.recentCommitters}) were real
     * integration calls folded invisibly into the single TRIAGEMATE {@code contacts:} row —
     * one internal step standing in for two external systems actually being consulted.
     */
    @Test
    void everyPlatformAppearsInTheTraceOnANormalRun() {
        TraceCollector collector = new TraceCollector();
        engine.diagnose("INC0010005", collector.forAttempt(0));

        assertThat(collector.steps()).extracting(TraceStep::platform)
                .contains(Platform.SERVICENOW, Platform.CONFLUENCE, Platform.SUMO, Platform.GITLAB);

        // Specifically: both GitLab calls, each as its own row rather than one standing in
        // for the other.
        assertThat(collector.steps()).extracting(TraceStep::tool)
                .contains("gitlab.searchCode", "gitlab.recentCommitters", "confluence.contributors");
    }

    /**
     * The skipped-call path: when the logs carry no error token there is nothing to search
     * code for, so no GitLab call is made — but the row must still appear, saying plainly
     * that it was skipped and why. The honesty contract cuts both ways: never claim a call
     * that didn't happen, but never hide the decision either.
     */
    @Test
    void everyPlatformAppearsInTheTraceEvenWhenACallIsSkipped() {
        // A Sumo gateway whose log lines contain no ERROR_TOKEN-shaped word, so the engine
        // takes the "no error token" branch and never calls gitLab.searchCode.
        DeterministicDiagnosisEngine noTokenEngine = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                request -> List.of(new com.company.triage.model.LogEvidence(
                        "2026-01-01T00:00:00Z", "INFO", "payment_service",
                        "nothing notable happened here")),
                new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        TraceCollector collector = new TraceCollector();
        noTokenEngine.diagnose("INC0010005", collector.forAttempt(0));

        List<TraceStep> steps = collector.steps();

        // GitLab is still represented, and the row states it was skipped — it does NOT
        // claim a search happened.
        assertThat(steps).extracting(TraceStep::platform).contains(Platform.GITLAB);
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.tool()).isEqualTo("gitlab.searchCode");
            assertThat(step.platform()).isEqualTo(Platform.GITLAB);
            assertThat(step.result()).contains("skipped");
        });
        // ...and so is the committer lookup, which had no code file to work from.
        assertThat(steps).anySatisfy(step -> {
            assertThat(step.tool()).isEqualTo("gitlab.recentCommitters");
            assertThat(step.result()).contains("skipped");
        });
    }

    // --- J24/SFF-2,3,4: the affected system, and saying so when it was inferred -----------

    /**
     * J24/SFF-2 + SFF-3 — the exact shape from the live instance
     * ({@code docs/Siyad_Findings.md} §2/§3): a ticket whose CMDB entry is absent, so the
     * affected system is inferred from the subject line.
     *
     * <p>Before J24 this produced a Sumo scope built from the sentence fragment
     * {@code hazards-being-recorded-on}, which matches no real {@code _sourceCategory}, and
     * then reported "0 line(s)" — a false negative presented to the reader as evidence that
     * the system was quiet. Now the search is skipped, the trace says why, and the report
     * discloses that every system-scoped conclusion rests on an inference.
     */
    @Test
    void anInferredAffectedSystemIsDisclosedAndItsScopeIsNotSearched() {
        var engineForCilessIncident = new DeterministicDiagnosisEngine(
                noCmdbIncidentGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        var result = engineForCilessIncident.diagnose("INC0010010");

        assertThat(result.report().missingInformation())
                .as("the inference must be stated, not silently relied on")
                .anySatisfy(m -> assertThat(m).contains("no configuration item"));
        assertThat(result.trace())
                .anySatisfy(line -> assertThat(line)
                        .contains("sumo.search → skipped")
                        .contains("inferred from the ticket's subject line"));
        assertThat(result.report().missingInformation())
                .noneSatisfy(m -> assertThat(m).contains("No log lines matched"));
    }

    /**
     * J24/SFF-2 — the counterpart: a CMDB-sourced system is NOT flagged as inferred, and its
     * scope is searched normally. Without this the previous test would pass trivially if the
     * code simply always disclosed.
     */
    @Test
    void aCmdbSourcedAffectedSystemIsNotFlaggedAsInferred() {
        var result = engine.diagnose("INC0010005");   // seeded incident: cmdb_ci = "Order Portal"

        assertThat(result.report().missingInformation())
                .noneSatisfy(m -> assertThat(m).contains("inferred from its subject line"));
        assertThat(result.trace())
                .anySatisfy(line -> assertThat(line).contains("sumo.search("));
    }

    /** J24/SFF-4 — an environment nobody stated is disclosed as an assumption. */
    @Test
    void aDefaultedEnvironmentIsDisclosed() {
        var engineForEnvlessIncident = new DeterministicDiagnosisEngine(
                noCmdbIncidentGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), new MockGitLabGateway(),
                TriagePropertiesFixture.deterministic());

        var result = engineForEnvlessIncident.diagnose("INC0010010");

        assertThat(result.report().missingInformation())
                .anySatisfy(m -> assertThat(m).contains("does not state a usable environment"));
    }

    /**
     * The live ticket's shape (INC0010010): no CMDB entry, no environment, a real subject
     * line. Dated, so the J14 opened_at path is not what is under test here.
     */
    private static com.company.triage.gateway.ServiceNowGateway noCmdbIncidentGateway() {
        return new com.company.triage.gateway.ServiceNowGateway() {
            @Override
            public com.company.triage.model.IncidentContext getIncident(String number) {
                return new com.company.triage.model.IncidentContext(
                        number,
                        "Hazards being recorded on handheld are not appearing in Delivery Hazards application",
                        "See attached for details.",
                        "Adela Cervantsz", "Inquiry / Help", null,
                        java.time.OffsetDateTime.parse("2026-08-02T21:37:16Z"),
                        null,          // <-- no environment
                        null,
                        List.of(), List.of(),
                        null,          // <-- no cmdb_ci
                        List.of());
            }
            @Override public List<com.company.triage.model.ResolvedIncident> findSimilarIncidents(
                    com.company.triage.model.IncidentContext c) { return List.of(); }
            @Override public java.util.Optional<com.company.triage.model.ServiceOwnership> findOwnership(String a) {
                return java.util.Optional.empty();
            }
            @Override public void addWorkNote(String number, String note) {}
            @Override public List<com.company.triage.model.NewIncident> findIncidentsCreatedSince(
                    java.time.OffsetDateTime since, int limit) { return List.of(); }
        };
    }

    // --- J13: evidence and citation integrity ------------------------------------------

    /**
     * J13/ECI-1 + ECI-4 — several GitLab hits for one token must not collide on the id
     * {@code e-code}, and the fan-out is capped.
     *
     * <p>Every hit was added as {@code "e-code"}, so a multi-hit run produced Evidence entries
     * sharing one id and any {@code evidenceRefs: ["e-code"]} could not say which file it
     * meant. The dangling-ref rule passed it trivially — the id existed, several times over.
     */
    @Test
    void multipleCodeHitsGetDistinctIdsAndAreCapped() {
        var manyHits = new MockGitLabGateway() {
            @Override
            public List<com.company.triage.model.CodeSearchResult> searchCode(String project, String term) {
                List<com.company.triage.model.CodeSearchResult> out = new java.util.ArrayList<>();
                for (int i = 1; i <= 7; i++) {
                    out.add(new com.company.triage.model.CodeSearchResult(
                            "order-payments/payment-service", "src/file" + i + ".py", i, "raise " + term));
                }
                return out;
            }
        };
        var engineWithManyHits = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), manyHits, TriagePropertiesFixture.deterministic());

        var report = engineWithManyHits.diagnose("INC0010005").report();

        List<String> codeIds = report.evidence().stream()
                .map(com.company.triage.model.Evidence::id)
                .filter(id -> id.startsWith("e-code"))
                .toList();
        assertThat(codeIds).as("capped at MAX_CODE_EVIDENCE").hasSize(3);
        assertThat(codeIds).as("ids must be unique — an id that does not identify is not an id")
                .doesNotHaveDuplicates();
        // And the whole report still validates, which is the point: uniqueness by construction.
        assertThat(report.evidence()).extracting(com.company.triage.model.Evidence::id)
                .doesNotHaveDuplicates();
    }

    /**
     * J13/ECI-2 — a candidate must never cite a DIFFERENT system's log line.
     *
     * <p>Every logger seen in the window became a candidate citing {@code e-log}, but that
     * Evidence describes only the first ERROR line's emitter. A report could therefore name
     * one system as a suspect and offer another system's log line as the reason — a citation
     * that does not support its claim, which is worse than no citation because it looks
     * rigorous.
     */
    @Test
    void candidatesNeverCiteAnotherSystemsLogLine() {
        var mixedLoggers = new MockSumoGateway() {
            @Override
            public List<com.company.triage.model.LogEvidence> search(
                    com.company.triage.model.LogSearchRequest request) {
                return List.of(
                        new com.company.triage.model.LogEvidence("2026-07-29T12:00:00Z", "ERROR",
                                "payment_service", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471"),
                        new com.company.triage.model.LogEvidence("2026-07-29T12:00:01Z", "INFO",
                                "order_portal", "checkout submitted"));
            }
        };
        var engineWithMixedLoggers = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                mixedLoggers, new MockGitLabGateway(), TriagePropertiesFixture.deterministic());

        var report = engineWithMixedLoggers.diagnose("INC0010005").report();

        // Order Portal only ever emitted an INFO line, so it must not cite the PAYMENT
        // SERVICE error line. It may still be a candidate — the CMDB names it as the owning
        // application, which is its OWN evidence (e-cmdb) — and that is the distinction this
        // rule is about: cite what supports you, not whatever happens to exist.
        assertThat(report.candidateSystems())
                .filteredOn(c -> "Order Portal".equals(c.name()))
                .allSatisfy(c -> assertThat(c.evidenceRefs())
                        .as("must not cite another system's log line")
                        .doesNotContain("e-log"));
        // ...and the omission is disclosed rather than silent.
        assertThat(report.missingInformation())
                .anySatisfy(m -> assertThat(m).contains("appeared in the searched log window"));
        // Every surviving candidate cites something that exists.
        List<String> ids = report.evidence().stream()
                .map(com.company.triage.model.Evidence::id).toList();
        assertThat(report.candidateSystems()).allSatisfy(c -> {
            assertThat(c.evidenceRefs()).isNotEmpty();
            assertThat(ids).containsAll(c.evidenceRefs());
        });
    }

    /**
     * J13/ECI-3 — the 0.86 tier means "this system's log line is tied to the code that emits
     * it". It was granted whenever ANY code hit existed, without checking the hit belonged to
     * the candidate's system, so a match in an unrelated allowlisted project could float an
     * unrelated candidate to the top of the shortlist.
     */
    @Test
    void theCodeCitationConfidenceRequiresTheHitToBelongToThatSystem() {
        var unrelatedProjectHit = new MockGitLabGateway() {
            @Override
            public List<com.company.triage.model.CodeSearchResult> searchCode(String project, String term) {
                // A hit in a DIFFERENT system than the one that logged the error.
                return List.of(new com.company.triage.model.CodeSearchResult(
                        "logistics/delivery-hazards", "src/unrelated.py", 9, "raise " + term));
            }
        };
        var engineWithUnrelatedHit = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                new MockSumoGateway(), unrelatedProjectHit, TriagePropertiesFixture.deterministic());

        var report = engineWithUnrelatedHit.diagnose("INC0010005").report();

        assertThat(report.candidateSystems())
                .filteredOn(c -> "Payment Service".equals(c.name()))
                .allSatisfy(c -> assertThat(c.confidence())
                        .as("0.86 requires the code hit to be THIS system's")
                        .isLessThan(0.86));
    }

    /**
     * J29/LLF-2 — "we could not read the severity" and "this line is INFO" are different
     * facts that both land on the 0.45 tier at :420. On the real Sumo estate the structured
     * level field is absent on every row, so before J29 every live run produced the lower
     * number with nothing in the report saying why. The scoring is right — an unreadable
     * level must NOT be promoted to the ERROR tier, because inventing severity to raise
     * confidence is the dishonesty J13 exists to prevent — so the fix is disclosure.
     */
    @Test
    void anUnreadableLogLevelIsDisclosedRatherThanSilentlyScoredAsNonError() {
        var unreadableLevels = new MockSumoGateway() {
            @Override
            public List<com.company.triage.model.LogEvidence> search(
                    com.company.triage.model.LogSearchRequest request) {
                return List.of(
                        // "" is what RealSumoGateway.parseLevel returns when neither the
                        // structured field nor _raw yields a level (J29/LLF-1).
                        new com.company.triage.model.LogEvidence("2026-07-29T12:00:00Z", "",
                                "payment_service", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471"),
                        new com.company.triage.model.LogEvidence("2026-07-29T12:00:01Z", "",
                                "payment_service", "retrying reconcile"));
            }
        };
        var engineWithUnreadableLevels = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                unreadableLevels, new MockGitLabGateway(), TriagePropertiesFixture.deterministic());

        var report = engineWithUnreadableLevels.diagnose("INC0010005").report();

        assertThat(report.missingInformation())
                .as("the run must say the severity was unreadable")
                .anySatisfy(m -> assertThat(m).contains("severity could not be read").contains("2"));
    }

    /**
     * J29/LLF-2 — the disclosure is conditional. A window where every level parsed must read
     * exactly as it did before; a caveat that fires on healthy data trains readers to skip
     * the whole section.
     */
    @Test
    void aWindowWithReadableLevelsAddsNoUnreadableSeverityDisclosure() {
        var readableLevels = new MockSumoGateway() {
            @Override
            public List<com.company.triage.model.LogEvidence> search(
                    com.company.triage.model.LogSearchRequest request) {
                return List.of(
                        new com.company.triage.model.LogEvidence("2026-07-29T12:00:00Z", "ERROR",
                                "payment_service", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471"),
                        new com.company.triage.model.LogEvidence("2026-07-29T12:00:01Z", "INFO",
                                "payment_service", "retrying reconcile"));
            }
        };
        var engineWithReadableLevels = new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                readableLevels, new MockGitLabGateway(), TriagePropertiesFixture.deterministic());

        var report = engineWithReadableLevels.diagnose("INC0010005").report();

        assertThat(report.missingInformation())
                .as("no caveat when every level was readable")
                .noneSatisfy(m -> assertThat(m).contains("severity could not be read"));
    }

    /** The captured real delivery-hazards ERROR line, abridged to one line (J29/LLF-3). */
    private static final String REAL_ERROR_MESSAGE =
            "9bc066ca org.springframework.dao.DataIntegrityViolationException: could not execute "
            + "statement [ERROR: null value in column \"facility_id\" of relation \"hazard\" "
            + "violates not-null constraint  Detail: Failing row contains (4630355, "
            + "unsafe_assets, null, 194117, HEAD INJURY, GNAF_FRONTAGE";

    private DeterministicDiagnosisEngine engineLogging(String level, String logger, String message) {
        var sumo = new MockSumoGateway() {
            @Override
            public List<com.company.triage.model.LogEvidence> search(
                    com.company.triage.model.LogSearchRequest request) {
                return List.of(new com.company.triage.model.LogEvidence(
                        "2026-08-05T14:26:46Z", level, logger, message));
            }
        };
        return new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(),
                sumo, new MockGitLabGateway(), TriagePropertiesFixture.deterministic());
    }

    private static String errorTokenIn(java.util.List<String> trace) {
        return trace.stream().filter(l -> l.contains("errorToken=")).findFirst()
                .map(l -> l.substring(l.indexOf("errorToken=") + "errorToken=".length()))
                .orElse(null);
    }

    /**
     * J29/LLF-3 — on the real line the SCREAMING_SNAKE {@code ERROR_TOKEN} matches
     * {@code GNAF_FRONTAGE}, a location-data COLUMN NAME out of the SQL {@code Detail: Failing
     * row contains (…)} tail. Searching GitLab for it is a wasted call; the thrown class is
     * what a human would search. A stack-trace shape on the line therefore outranks the
     * free-text token.
     */
    @Test
    void aThrownExceptionClassOutranksASnakeCaseDataValueOnTheRealErrorLine() {
        var result = engineLogging("ERROR", "a.c.a.h.c.e.CustomRestExceptionHandler",
                REAL_ERROR_MESSAGE).diagnose("INC0010005");

        assertThat(errorTokenIn(result.trace()))
                .as("the thrown class, not a column name from the SQL detail tail")
                .isEqualTo("DataIntegrityViolationException")
                .isNotEqualTo("GNAF_FRONTAGE")
                // Spring Boot abbreviates the logger name, so `\\w+(\\.\\w+)+Exception` — the
                // naive detector — matches a class that exists in no source file and returns
                // nothing from GitLab. The detector must read the thrown FQN instead.
                .isNotEqualTo("a.c.a.h.c.e.CustomRestException");
    }

    /**
     * J29/LLF-3 — the other direction, and the one that matters for the demo: a line carrying
     * a genuine error CODE and no exception FQN must still yield that code. Preferring the
     * class unconditionally would regress every such line to nothing.
     */
    @Test
    void aGenuineErrorCodeWithNoExceptionFqnStillWins() {
        var result = engineLogging("ERROR", "payment_service",
                "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471 expected=11.50 charged=11.25")
                .diagnose("INC0010005");

        assertThat(errorTokenIn(result.trace())).isEqualTo("PAYMENT_RECONCILE_MISMATCH");
    }

    /** J29/LLF-3 — neither shape present: no term, and GitLab is skipped as it already was. */
    @Test
    void aLineWithNeitherShapeYieldsNoTermAndSkipsGitLab() {
        var result = engineLogging("ERROR", "payment_service",
                "connection reset by peer while reconciling").diagnose("INC0010005");

        assertThat(errorTokenIn(result.trace())).isEqualTo("null");
        assertThat(result.trace()).anySatisfy(l -> assertThat(l)
                .contains("gitlab.searchCode → skipped (no error token"));
    }
}
