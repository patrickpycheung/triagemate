package com.company.triage.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * J28 — the cause/resolution contract: CR-6, CR-7, CR-8 and the rendering rules.
 *
 * <p>The single most important test in here is {@link #abstentionIsValid()}. Every other rule
 * constrains a positive claim; that one pins the negative case, and it is the one a future
 * "tighten the validator" change is most likely to break. If abstention ever becomes invalid,
 * the schema stops permitting "we don't know" and starts <em>requiring</em> an answer — which
 * is how a system that has no causal substrate ends up inventing one.
 */
class CauseAndResolutionContractTest {

    private static final Evidence SIM = new Evidence("e-sim-INC0011902", "servicenow-incident",
            "INC0011902 (91% similar) resolved by Payments Platform Support: Resolved - Code Fix "
                    + "— Discount was applied after tax in the gateway; reconcile check failed.",
            "INC0011902");
    private static final Evidence LOG = new Evidence("e-log", "sumo", "PAYMENT_RECONCILE_MISMATCH", "l");

    private DiagnosisReport report(LikelyCause cause, LikelyResolution res, Confidence overall) {
        return new DiagnosisReport("INC0010005", OffsetDateTime.now(), "symptom", "function",
                "Production", new Identifiers("id", null, "id"),
                List.of(new CandidateSystem("Payment Service", 0.8, List.of("e-log"))),
                new SuggestedAssignment("Team", Confidence.MEDIUM, List.of("e-log")),
                List.of(SIM, LOG), List.of(), List.of(), List.of(),
                "next action", overall, true, cause, res);
    }

    private static LikelyCause goodCause() {
        return new LikelyCause(
                "Discount was applied after tax in the gateway; reconcile check failed.",
                List.of("INC0011902"), InferenceBasis.PRIOR_RESOLUTION,
                List.of("e-sim-INC0011902"), 1, 2);
    }

    // ---------- abstention ----------

    @Test
    void abstentionIsValid() {
        assertThatCode(() -> DiagnosisReportValidator.validate(report(null, null, Confidence.MEDIUM)))
                .as("null cause and null resolution must be a legal report — abstention is the "
                        + "expected output much of the time, and a rule that forbade it would "
                        + "force fabrication")
                .doesNotThrowAnyException();
    }

    @Test
    void abstentionIsAlsoValidAtHighConfidence() {
        assertThatCode(() -> DiagnosisReportValidator.validate(report(null, null, Confidence.HIGH)))
                .as("CR-7 caps confidence only when an analogical cause is present; it must not "
                        + "constrain reports that make no cause claim at all")
                .doesNotThrowAnyException();
    }

    @Test
    void abstentionRendersTheDenominatorNotJustSilence() {
        String note = report(null, null, Confidence.MEDIUM).toDiagnosisNote();
        assertThat(note)
                .contains("not established")
                .contains("0 of 1 similar resolved incidents recorded what was wrong");
        assertThat(note).doesNotContain("How similar incidents were resolved");
    }

    // ---------- CR-6: basis ↔ evidence source ----------

    @Test
    void cr6RejectsACauseCitingTheWrongKindOfEvidence() {
        LikelyCause miscited = new LikelyCause("PAYMENT_RECONCILE_MISMATCH",
                List.of("INC0011902"), InferenceBasis.PRIOR_RESOLUTION,
                List.of("e-log"),        // a log line, not a prior resolution
                1, 2);
        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report(miscited, null, Confidence.MEDIUM)))
                .isInstanceOf(DiagnosisReportInvalidException.class)
                .hasMessageContaining("basis PRIOR_RESOLUTION")
                .hasMessageContaining("servicenow-incident");
    }

    @Test
    void cr6AcceptsACauseCitingTheRightKind() {
        assertThatCode(() -> DiagnosisReportValidator.validate(report(goodCause(), null, Confidence.MEDIUM)))
                .doesNotThrowAnyException();
    }

    // ---------- CR-7: MEDIUM ceiling ----------

    @Test
    void cr7CapsReportConfidenceWhenTheCauseIsAnalogical() {
        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report(goodCause(), null, Confidence.HIGH)))
                .isInstanceOf(DiagnosisReportInvalidException.class)
                .hasMessageContaining("cap is MEDIUM");
    }

    // ---------- CR-8: quote fidelity ----------

    @Test
    void cr8RejectsAQuoteThatAppearsInNoCitedEvidence() {
        LikelyCause invented = new LikelyCause(
                "The database ran out of connections during the nightly batch.",
                List.of("INC0011902"), InferenceBasis.PRIOR_RESOLUTION,
                List.of("e-sim-INC0011902"), 1, 2);
        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report(invented, null, Confidence.MEDIUM)))
                .isInstanceOf(DiagnosisReportInvalidException.class)
                .hasMessageContaining("a quotation must be quotable");
    }

    @Test
    void cr8ToleratesRewrappedWhitespace() {
        LikelyCause rewrapped = new LikelyCause(
                "Discount was applied after tax in the gateway;\n   reconcile check failed.",
                List.of("INC0011902"), InferenceBasis.PRIOR_RESOLUTION,
                List.of("e-sim-INC0011902"), 1, 2);
        assertThatCode(() -> DiagnosisReportValidator.validate(report(rewrapped, null, Confidence.MEDIUM)))
                .as("a quote re-wrapped for the note must still match its source")
                .doesNotThrowAnyException();
    }

    @Test
    void supportingCountMayNotExceedConsidered() {
        LikelyCause impossible = new LikelyCause(
                "Discount was applied after tax in the gateway; reconcile check failed.",
                List.of("INC0011902"), InferenceBasis.PRIOR_RESOLUTION,
                List.of("e-sim-INC0011902"), 5, 2);
        assertThatThrownBy(() -> DiagnosisReportValidator.validate(report(impossible, null, Confidence.MEDIUM)))
                .isInstanceOf(DiagnosisReportInvalidException.class)
                .hasMessageContaining("exceeds consideredCount");
    }

    // ---------- rendering ----------

    @Test
    void theNoteQuotesAttributesAndCountsWithoutInventingAPercentage() {
        LikelyResolution res = new LikelyResolution(
                null,
                new ResolutionStep(ResolutionVerb.CHECK, "Resolved - Code Fix",
                        "INC0011902", List.of("e-sim-INC0011902")));
        String note = report(goodCause(), res, Confidence.MEDIUM).toDiagnosisNote();

        assertThat(note).contains("Why this may be happening:");
        assertThat(note).contains("INC0011902 was closed with this note");
        assertThat(note).contains("Discount was applied after tax in the gateway");
        assertThat(note).contains("Based on 1 of 2 similar resolved incidents.");
        assertThat(note).contains("How similar incidents were resolved:");
        assertThat(note).contains("Permanent fix — INC0011902, closed as: Resolved - Code Fix");

        assertThat(note)
                .as("the resolution section is closed-vocabulary only — no gathered free text")
                .doesNotContain("Discount was applied after tax in the gateway; reconcile check failed.\n  Permanent");
        assertThat(note)
                .as("no confidence percentage is ever rendered for the cause (the denominator "
                        + "is the whole uncertainty signal)")
                .doesNotContain("% confident");
    }

    @Test
    void theLabelIsPastTenseSoItSurvivesBeingWrong() {
        LikelyResolution res = new LikelyResolution(
                new ResolutionStep(ResolutionVerb.CONSULT_RUNBOOK, "Resolved - Known Error",
                        "INC0011455", List.of("e-sim-INC0011902")),
                null);
        String note = report(goodCause(), res, Confidence.MEDIUM).toDiagnosisNote();

        assertThat(note)
                .as("historical framing is the safety property — a factual report about the past "
                        + "stays true even when the prior fix does not apply here")
                .contains("How similar incidents were resolved")
                .doesNotContain("How to resolve")
                .doesNotContain("You should");
    }
}
