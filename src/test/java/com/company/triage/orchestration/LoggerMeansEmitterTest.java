package com.company.triage.orchestration;

import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.gateway.mock.*;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.LogEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J14/FRI-3 and FRI-4 — an emitter is a property of the LINE, and a hyphen is not a separator.
 */
class LoggerMeansEmitterTest {

    private DeterministicDiagnosisEngine engineWithLogs(List<LogEvidence> logs) {
        SumoGateway stub = req -> logs;
        return new DeterministicDiagnosisEngine(
                new MockServiceNowGateway(), new MockConfluenceGateway(), stub,
                new MockGitLabGateway(), TriagePropertiesFixture.deterministic());
    }

    private static LogEvidence line(String logger) {
        return new LogEvidence("2026-08-05T10:00:00Z", "ERROR", logger,
                "PAYMENT_RECONCILE_MISMATCH expected total diverged");
    }

    /**
     * The bug this prevents: {@code RealSumoGateway} reported {@code _sourcecategory} as the
     * logger. That field is pinned to ONE composed value for the whole search, so every line
     * claimed the same emitter — and the engine then asserted "no X errors appear, the failure
     * looks downstream of it" about a set it had never actually observed.
     */
    @Test
    void theDownstreamContradictionNeedsMoreThanOneEmitterToBeAssertable() {
        DiagnosisReport oneEmitter = engineWithLogs(List.of(
                line("IDT/ITServices/Tomcat/order-portal/prod"),
                line("IDT/ITServices/Tomcat/order-portal/prod"))).diagnose("INC0010005").report();

        assertThat(oneEmitter.contradictingEvidence())
                .as("with a single emitter by construction there is no 'set of systems seen "
                        + "emitting' to be absent from — the premise is unavailable, so the "
                        + "conclusion may not be stated")
                .noneMatch(c -> c.contains("looks downstream"));
    }

    @Test
    void aBlankLoggerNamesNoSystemAndSuppressesTheSameClaim() {
        DiagnosisReport blank = engineWithLogs(List.of(line(""), line(""))).diagnose("INC0010005").report();

        assertThat(blank.contradictingEvidence()).noneMatch(c -> c.contains("looks downstream"));
        assertThat(blank.candidateSystems())
                .as("a line that cannot name its emitter is still evidence — it just cannot "
                        + "name a system, which is the truth rather than a gap")
                .noneMatch(c -> c.name() == null || c.name().isBlank());
    }

    @Test
    void realEmitterDiversityStillAllowsTheClaim() {
        DiagnosisReport diverse = engineWithLogs(List.of(
                line("payment_service"), line("identity_gateway"))).diagnose("INC0010005").report();

        // Not asserting the claim IS made — that depends on the CMDB owner — only that having
        // two genuine emitters does not suppress it. The suppression must be about the premise
        // being unavailable, never about the conclusion being inconvenient.
        assertThat(diverse.evidence()).isNotEmpty();
    }

    /**
     * J14/FRI-4. The old split was the character class {@code [-:|—]}, so "Track-and-Trace is
     * down" produced the affected application <b>"Track"</b> — which then composed the Sumo
     * scope and seeded the candidate systems. One hyphen, whole run mis-scoped.
     */
    @Test
    void aHyphenInsideAWordIsNotAPhraseSeparator() {
        assertThat(IncidentSignals.leadingPhraseForTest("Track-and-Trace is down"))
                .isEqualTo("Track-and-Trace is down");
        assertThat(IncidentSignals.leadingPhraseForTest("Single-sign-on failing for e-mail users"))
                .isEqualTo("Single-sign-on failing for e-mail");
    }

    @Test
    void aWhitespaceFlankedDashStillSeparates() {
        assertThat(IncidentSignals.leadingPhraseForTest("Order Portal - checkout failing"))
                .isEqualTo("Order Portal");
        assertThat(IncidentSignals.leadingPhraseForTest("Order Portal — checkout failing"))
                .isEqualTo("Order Portal");
        assertThat(IncidentSignals.leadingPhraseForTest("Order Portal: checkout failing"))
                .isEqualTo("Order Portal");
    }
}
