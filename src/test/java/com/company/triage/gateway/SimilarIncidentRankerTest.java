package com.company.triage.gateway;

import com.company.triage.model.IncidentContext;
import com.company.triage.model.ResolvedIncident;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J26. The selection half of the similar-incident redesign.
 *
 * <p>The old implementation had no selection at all — one {@code LIKE} filter on the first
 * word of the subject line, and {@code 0.5} written onto every row it happened to return.
 * These tests pin the three properties that made it wrong: the CI is a first-class match key,
 * the score is computed rather than asserted, and a candidate that resembles nothing is
 * dropped instead of reported at a flattering 50%.
 */
class SimilarIncidentRankerTest {

    /** The live ticket from {@code docs/Siyad_Findings.md} §2. */
    private static IncidentContext hazardsIncident() {
        return new IncidentContext("INC0010010",
                "Hazards being recorded on handheld are not appearing in Delivery Hazards application.",
                "Hazards recorded by the handheld device do not sync through to the application.",
                "Adela Cervantsz", "Inquiry / Help", null, null, null, null,
                List.of(), List.of(), "Delivery Hazards", List.of());
    }

    private static SimilarIncidentRanker.Candidate candidate(
            String number, String shortDescription, String ci, String category) {
        return new SimilarIncidentRanker.Candidate(number, shortDescription,
                "Delivery Support", "Resolved - Code Fix", "", ci, category);
    }

    /**
     * The regression that matters most: this is the incident the old query could never find.
     * Its wording overlaps only on "hazards" — the reporter said "captured"/"saved" where the
     * new ticket says "recorded"/"appearing" — so text alone barely scores it. The shared CI
     * is what makes it the strongest available routing signal, and the old implementation
     * never looked at the CI at all.
     */
    @Test
    void ranksASameSystemIncidentAboveAnUnrelatedTextualCoincidence() {
        var sameSystem = candidate("INC2616763",
                "Hazards captured on handhelds not being saved", "Delivery Hazards", "Inquiry / Help");
        var differentSystem = candidate("INC0009000",
                "Application login page not appearing", "Payroll Portal", "Inquiry / Help");

        List<ResolvedIncident> ranked = SimilarIncidentRanker.rank(
                hazardsIncident(), List.of(differentSystem, sameSystem), 0.25, 5);

        assertThat(ranked).extracting(ResolvedIncident::number).containsExactly("INC2616763");
    }

    /** A same-CI match with no textual overlap at all still clears the default floor. */
    @Test
    void aSameSystemIncidentQualifiesOnTheConfigurationItemAlone() {
        var noWordsInCommon = candidate("INC0008888",
                "Nightly batch job aborted", "Delivery Hazards", "Software");

        List<ResolvedIncident> ranked = SimilarIncidentRanker.rank(
                hazardsIncident(), List.of(noWordsInCommon), 0.25, 5);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).similarity()).isEqualTo(0.3);
    }

    /**
     * The fabricated-score fault. Nothing about this candidate resembles the incident, so it
     * must be dropped — where the old code would have reported it at "50% similar" purely
     * because a substring matched.
     */
    @Test
    void dropsCandidatesThatResembleNothing() {
        var unrelated = candidate("INC0007777",
                "Printer on level 4 out of toner", "Print Services", "Hardware");

        assertThat(SimilarIncidentRanker.rank(hazardsIncident(), List.of(unrelated), 0.25, 5))
                .isEmpty();
    }

    /** Scores are real: a closer incident must outrank a weaker one, and be ordered by it. */
    @Test
    void ordersByComputedScoreAndRespectsTheCap() {
        var strong = candidate("INC0001",
                "Hazards recorded on handheld are not appearing in Delivery Hazards application",
                "Delivery Hazards", "Inquiry / Help");
        var weaker = candidate("INC0002",
                "Handheld device sync delays", "Delivery Hazards", "Software");

        List<ResolvedIncident> ranked = SimilarIncidentRanker.rank(
                hazardsIncident(), List.of(weaker, strong), 0.25, 5);

        assertThat(ranked).extracting(ResolvedIncident::number).containsExactly("INC0001", "INC0002");
        assertThat(ranked.get(0).similarity()).isGreaterThan(ranked.get(1).similarity());
        assertThat(ranked.get(0).similarity()).isBetween(0.0, 1.0);

        assertThat(SimilarIncidentRanker.rank(hazardsIncident(), List.of(weaker, strong), 0.25, 1))
                .extracting(ResolvedIncident::number).containsExactly("INC0001");
    }

    /**
     * A re-triage of an already-resolved ticket retrieves the ticket itself — same CI, every
     * word in common. Reporting "INC0010010 (100% similar)" in an advisory note posted onto
     * INC0010010 reads as a malfunction, so the incident is excluded from its own results.
     */
    @Test
    void neverReportsTheIncidentAsSimilarToItself() {
        var itself = candidate("INC0010010",
                "Hazards being recorded on handheld are not appearing in Delivery Hazards application.",
                "Delivery Hazards", "Inquiry / Help");

        assertThat(SimilarIncidentRanker.rank(hazardsIncident(), List.of(itself), 0.25, 5)).isEmpty();
    }

    /** Retrieval runs two overlapping passes by design, so the same row can arrive twice. */
    @Test
    void collapsesDuplicatesFromOverlappingRetrievalPasses() {
        var row = candidate("INC2616763",
                "Hazards captured on handhelds not being saved", "Delivery Hazards", "Inquiry / Help");

        assertThat(SimilarIncidentRanker.rank(hazardsIncident(), List.of(row, row), 0.25, 5))
                .hasSize(1);
    }
}
