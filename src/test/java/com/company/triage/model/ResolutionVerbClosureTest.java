package com.company.triage.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * J28/PGC-3 — the resolution vocabulary is <b>closed</b>, and every constant in it is
 * observation-only.
 *
 * <p>This is the test that keeps the advisory posture true. The app's "it comments, it never
 * acts" guarantee is architectural for the machine — {@code ServiceNowGateway} exposes no
 * mutating method, so a compromised model has nothing to call. A resolution section breaks
 * that argument's second leg, because its purpose is that a <b>human</b> reads it and acts:
 * the blast radius stops being bounded by machine capability and becomes bounded by human
 * compliance.
 *
 * <p>So the bound lives in the type, and this test defends it from the two ways it erodes:
 * something outside the enum being accepted at runtime, and something mutating being added to
 * the enum in a later change that "just needed one more verb".
 */
class ResolutionVerbClosureTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anUnknownVerbIsRejectedRatherThanPassedThrough() {
        String json = """
                {"verb":"RESTART_SERVICE","resolutionCode":"Resolved - Code Fix",
                 "citedArtifact":"INC0011902","evidenceRefs":["e-sim-INC0011902"]}""";

        assertThatThrownBy(() -> mapper.readValue(json, ResolutionStep.class))
                .as("a verb outside the vocabulary must fail loudly, not arrive as a string a "
                        + "human might act on")
                .hasMessageContaining("RESTART_SERVICE");
    }

    /**
     * A maintained list, deliberately duplicated from the enum. Adding a constant fails here
     * and forces whoever adds it to read {@link ResolutionVerb}'s reasoning and state the case
     * — which is the entire point. It is not redundant with the enum; it is a speed bump in
     * front of it.
     */
    @Test
    void everyVerbIsObservationOnly() {
        List<String> approved = List.of(
                "CHECK", "COMPARE", "REPRODUCE_NON_PROD", "CONTACT", "CONSULT_RUNBOOK", "GATHER");

        assertThat(Arrays.stream(ResolutionVerb.values()).map(Enum::name).toList())
                .as("""
                        ResolutionVerb has changed. Every constant must be OBSERVATION-ONLY — \
                        "go look at X", never "go change X". A verification step is \
                        self-limiting and a wrong one costs minutes; a remediation step \
                        changes production state and a wrong one during an outage deepens the \
                        outage. If you are adding RESTART, ROLLBACK, CLEAR_CACHE, RERUN_JOB or \
                        ENABLE_DEBUG_LOGGING (which is a state mutation wearing an observer's \
                        coat), this test is the objection — answer it in the concept card \
                        first.""")
                .containsExactlyInAnyOrderElementsOf(approved);
    }

    @Test
    void thereIsNoEscapeHatch() {
        assertThat(Arrays.stream(ResolutionVerb.values()).map(Enum::name))
                .as("an OTHER/CUSTOM constant would re-open free text and defeat the enum")
                .noneMatch(n -> n.equals("OTHER") || n.equals("CUSTOM") || n.equals("UNKNOWN"));
    }

    @Test
    void everyInferenceBasisDeclaresARequiredEvidenceSource() {
        for (InferenceBasis b : InferenceBasis.values()) {
            assertThat(b.requiredEvidenceSource())
                    .as("CR-6 switches on this; a missing mapping would silently disable the rule")
                    .isNotBlank();
        }
        assertThat(Arrays.stream(InferenceBasis.values()).map(Enum::name))
                .as("abstention is a null likelyCause — a NONE constant would be a second, "
                        + "divergent encoding of the same thing")
                .doesNotContain("NONE");
    }
}
