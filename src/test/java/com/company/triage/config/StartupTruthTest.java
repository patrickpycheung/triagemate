package com.company.triage.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * J20/STV-3 and STV-5 — prove the MECHANISM, not the annotations.
 *
 * <p>Asserting that a field carries {@code @NotEmpty} tests the source file. These tests
 * construct the config the way Spring's validator sees it and check that a run which cannot
 * work is refused at the boundary, which is the actual guarantee.
 *
 * <p>The {@code @NotEmpty} halves are decisions, not niceties. An empty allowlist is
 * representable and means <b>deny everything</b> — a silently zero-capability run, which is
 * the same "looks fine, does nothing" failure the banner work exists to kill. A deployment
 * that genuinely wants no code search sets {@code triage.connectors.gitlab=mock}.
 */
class StartupTruthTest {

    private static jakarta.validation.Validator validator() {
        return jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void anEmptyGitLabAllowlistIsRejectedRatherThanMeaningDenyEverything() {
        var gitlab = new TriageProperties.GitLab(List.of());
        assertThat(validator().validate(gitlab))
                .as("empty means deny-everything — a zero-capability run that looks healthy")
                .isNotEmpty();
    }

    @Test
    void aNullGitLabAllowlistIsRejectedRatherThanNormalisedToEmpty() {
        var gitlab = new TriageProperties.GitLab(null);
        assertThat(validator().validate(gitlab))
                .as("normalising null to List.of() would make the app boot on config that "
                        + "cannot work, moving the diagnosis from a named startup error to "
                        + "'why did search_code reject everything?' an hour later")
                .isNotEmpty();
    }

    @Test
    void anEmptySumoEnvironmentListIsRejected() {
        var sumo = new TriageProperties.Sumo("p/{project}/{environment}", null, "idx", List.of(), 20, 30);
        assertThat(validator().validate(sumo)).isNotEmpty();
    }

    @Test
    void aMissingSourceCategoryPatternIsRejectedAtTheBoundaryNotAtFirstUse() {
        var sumo = new TriageProperties.Sumo(null, null, "idx", List.of("prod"), 20, 30);
        assertThat(validator().validate(sumo))
                .as("sourceCategoryFor calls pattern.replace unguarded — absence throws "
                        + "downstream, so it is validated here")
                .isNotEmpty();
    }

    @Test
    void aValidSumoConfigPassesAndDefaultsItsOptionalOverrides() {
        var sumo = new TriageProperties.Sumo("p/{project}/{environment}", null, "idx", List.of("prod"), 20, 30);
        assertThat(validator().validate(sumo)).isEmpty();
        assertThat(sumo.sourceCategoryOverrides())
                .as("this one IS optional and sourceCategoryFor already handles absence, so "
                        + "normalising it is safe — unlike the allowlists, where absence has "
                        + "no defined meaning")
                .isEmpty();
        assertThat(sumo.sourceCategoryFor("payment-service", "prod"))
                .isEqualTo("p/payment-service/prod");
    }
}
