package com.company.triage.gateway;

import com.company.triage.model.IncidentContext;
import com.company.triage.model.ResolvedIncident;
import com.company.triage.model.SymptomTokens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores and ranks candidate resolved incidents against the incident being triaged.
 *
 * <p><b>Why this exists (J26).</b> {@code RealServiceNowGateway.findSimilarIncidents} used to
 * be a single {@code short_descriptionLIKE<first word of the subject line>} filter with a
 * hardcoded {@code 0.5} similarity on every row. Three things were wrong with that, and only
 * the first one showed up as "always zero hits":
 *
 * <ol>
 *   <li><b>The retrieval key was chosen by position, not by information.</b> The first word of
 *       a real subject line is a sentence opener — "Unable", "Users", "Cannot", "Hazards".
 *       Against the live instance (INC0010010, {@code docs/Siyad_Findings.md} §2) the whole
 *       query reduced to {@code short_descriptionLIKEHazards}.</li>
 *   <li><b>{@code cmdb_ci} — the field that names the affected system, and the single
 *       strongest match key available — was never used at all.</b> Doubly dead before
 *       J24/SFF-1, since the reference-field parse returned {@code ""} for it anyway.</li>
 *   <li><b>The score was fabricated.</b> The engine renders it as
 *       {@code "%s (%.0f%% similar)"}, so every hit would have advertised "50% similar" to a
 *       human reading an advisory note on a real ticket. A number nobody computed is worse
 *       than no number: it is unearned confidence in output the app posts under its own name.</li>
 * </ol>
 *
 * <p>Retrieval is now deliberately <b>wide</b> (see the gateway's two passes) and selection
 * happens here, where it can be reasoned about and unit-tested without HTTP. Pure function of
 * its inputs — no network, no clock, no config lookups.
 */
public final class SimilarIncidentRanker {

    private SimilarIncidentRanker() {}

    /**
     * Weights. Text dominates because it is what "similar symptoms" actually means, but a
     * same-CI match alone ({@value #WEIGHT_CI}) clears a default floor of 0.25 on its own:
     * a resolved incident against the same system is worth showing a human even when the two
     * reporters described the fault in completely different words — which is the normal case,
     * not the exception. Category is a weak tiebreak; it is coarse enough that agreement is
     * only mild evidence.
     */
    private static final double WEIGHT_TEXT = 0.6;
    private static final double WEIGHT_CI = 0.3;
    private static final double WEIGHT_CATEGORY = 0.1;

    /**
     * One retrieved row, before scoring. Carries the two fields used for matching
     * ({@code configurationItem}, {@code category}) that {@link ResolvedIncident} has no
     * place for — they are inputs to the score, not something the report needs to render.
     */
    public record Candidate(
            String number,
            String shortDescription,
            String resolutionGroup,
            String resolutionCode,
            String resolutionNotes,
            String configurationItem,
            String category
    ) {}

    /**
     * Ranks candidates against the incident, keeping only those at or above {@code floor}.
     *
     * <p>Duplicates (the same incident retrieved by more than one pass) are collapsed on
     * {@code number}, keeping the first occurrence — retrieval order is CI-pass first, and
     * both passes produce identical scores for the same row anyway.
     *
     * @param floor minimum score to keep, {@code 0..1}. A candidate that matches nothing
     *              scores 0 and is always dropped, whatever the floor.
     * @param max   maximum results to return; {@code <= 0} means no cap.
     * @return highest-scoring first; ties keep retrieval order (stable sort), which is the
     *         instance's own {@code ORDERBYDESCresolved_at} — so equally-similar incidents
     *         surface most-recently-resolved first.
     */
    public static List<ResolvedIncident> rank(IncidentContext incident, List<Candidate> candidates,
                                              double floor, int max) {
        if (incident == null || candidates == null || candidates.isEmpty()) return List.of();

        Set<String> incidentTokens = SymptomTokens.setOf(
                join(incident.shortDescription(), incident.description()));
        String incidentCi = normalise(incident.configurationItem());
        String incidentCategory = normalise(incident.category());

        Map<String, ResolvedIncident> byNumber = new LinkedHashMap<>();
        List<ResolvedIncident> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c == null || c.number() == null || c.number().isBlank()) continue;
            if (byNumber.containsKey(c.number())) continue;
            // An incident is never similar to itself. It can legitimately be retrieved —
            // a re-triage of an already-resolved ticket matches its own CI and every one
            // of its own words — and "INC0010010 (100% similar) resolved by ..." pointing
            // at the ticket the note is being posted onto reads as a malfunction.
            if (c.number().equalsIgnoreCase(incident.number())) continue;

            double score = score(c, incidentTokens, incidentCi, incidentCategory);
            if (score < floor || score <= 0.0) continue;

            ResolvedIncident r = new ResolvedIncident(c.number(), c.shortDescription(),
                    c.resolutionGroup(), c.resolutionCode(), c.resolutionNotes(), round(score));
            byNumber.put(c.number(), r);
            scored.add(r);
        }

        scored.sort(Comparator.comparingDouble(ResolvedIncident::similarity).reversed());
        return max > 0 && scored.size() > max ? List.copyOf(scored.subList(0, max)) : List.copyOf(scored);
    }

    private static double score(Candidate c, Set<String> incidentTokens,
                                String incidentCi, String incidentCategory) {
        double text = SymptomTokens.jaccard(incidentTokens,
                SymptomTokens.setOf(join(c.shortDescription(), c.resolutionNotes())));

        // Exact-after-normalisation only. A "contains" match here would rate every CI whose
        // name is a substring of another as a hit ("Delivery" vs "Delivery Hazards"), which
        // is a different system, not a similar one.
        double ci = !incidentCi.isEmpty() && incidentCi.equals(normalise(c.configurationItem())) ? 1.0 : 0.0;
        double category = !incidentCategory.isEmpty()
                && incidentCategory.equals(normalise(c.category())) ? 1.0 : 0.0;

        return clamp(WEIGHT_TEXT * text + WEIGHT_CI * ci + WEIGHT_CATEGORY * category);
    }

    private static String join(String a, String b) {
        return ((a == null ? "" : a) + " " + (b == null ? "" : b)).trim();
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static double clamp(double v) {
        return v < 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
    }

    /** Two decimal places — the score is rendered as a whole percentage; false precision helps nobody. */
    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
