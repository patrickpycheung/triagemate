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

        // Derived from the seeded incident's own shortDescription + configurationItem —
        // not the old hardcoded literal, which contained none of these terms.
        assertThat(captured.get())
                .contains("checkout")          // from shortDescription
                .contains("Order Portal")      // from configurationItem
                .doesNotContain("reconcile", "discount", "500");   // the old fixed literal's terms
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

        // FND-62: the correlation id is extracted despite not matching the demo's INC-ORD- shape.
        assertThat(r.identifiers().correlationId()).isEqualTo("BATCH-778812");

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

        // Every emitted step must be DONE (this engine runs synchronously — nothing should
        // ever be left stuck ACTIVE) or, for the conditional gitlab.searchCode path, simply
        // absent. FAILED/ABANDONED are not reachable on this engine's happy path.
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
}
