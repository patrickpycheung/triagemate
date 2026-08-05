package com.company.triage.gateway.real;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline proof that the similar-incident scorer DISCRIMINATES, not merely that it runs.
 *
 * <p>The live test asserts {@code INC0010012} ranks first for the demo incident, but that
 * ticket is also the most recently created — so a scorer that returned a constant would pass
 * it purely on the {@code ORDERBYDESCsys_created_on} tiebreak. These use the real subject
 * lines from the instance and assert the SEPARATION between the twin and the unrelated
 * cluster, which creation order cannot fake.
 */
class SimilarIncidentScoringTest {

    private static final String PROBE =
            "Hazards being recorded on handheld are not appearing in Delivery Hazards application. "
                    + "See attached for details.";
    /** The genuine duplicate — same subject, same fault. */
    private static final String TWIN = PROBE;
    /** The other five Delivery Hazards tickets: same application, different fault. */
    private static final String UNRELATED = "Delivery Hazards - All hazards are no longer present";

    @Test
    void theTwinScoresStrictlyHigherThanTheUnrelatedSameApplicationCluster() {
        double twin = RealServiceNowGateway.similarity(PROBE, TWIN);
        double unrelated = RealServiceNowGateway.similarity(PROBE, UNRELATED);

        assertThat(twin).as("identical subjects are a perfect match").isEqualTo(1.0);
        assertThat(twin).as("if these tie, ranking degrades to creation order").isGreaterThan(unrelated);
        assertThat(unrelated)
                .as("the unrelated cluster shares 'hazards'/'delivery' so it must NOT score 0 — "
                        + "the point is separation, not exclusion")
                .isGreaterThan(0.0);
    }

    @Test
    void theApplicationNameAloneDoesNotMakeTwoIncidentsSimilar() {
        // The bug this whole change exists to fix: matching on the project name matched
        // everything in the project equally. Two different faults in the same application
        // must stay well below a real duplicate.
        double unrelated = RealServiceNowGateway.similarity(PROBE, UNRELATED);
        assertThat(unrelated).isLessThan(0.5);
    }

    @Test
    void blankOrUnscoreableSubjectsScoreZeroRatherThanThrowing() {
        assertThat(RealServiceNowGateway.similarity(null, PROBE)).isZero();
        assertThat(RealServiceNowGateway.similarity(PROBE, "")).isZero();
        assertThat(RealServiceNowGateway.similarity("a of to", PROBE))
                .as("all-stopword subjects have no significant words to compare")
                .isZero();
    }
}
