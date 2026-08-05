package com.company.triage.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tokenising symptom text into the distinctive terms a search or a similarity score can
 * work with, in one place.
 *
 * <p><b>Why this exists.</b> {@code IncidentSignals} already knew how to do this for query
 * building, and {@link com.company.triage.gateway.SimilarIncidentRanker} needs the same
 * answer for scoring — two copies of a stopword list is precisely the two-sources-of-truth
 * split FND-40 and FND-62 each had to be fixed for. This lives in {@code model} rather than
 * {@code orchestration} so the gateway layer can use it without depending upwards on the
 * engine that happens to be the other caller.
 *
 * <p>The stopword list is <b>function words only</b>, deliberately not domain words:
 * "error", "order", "payment", "hazards" are exactly the terms that make two incidents
 * similar, so stripping them to look clever would defeat the purpose.
 */
public final class SymptomTokens {

    private SymptomTokens() {}

    /** Minimum token length worth keeping. Shorter runs are noise ("is", "on", "to"). */
    private static final int MIN_LENGTH = 4;

    static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "but", "for", "with", "that", "this", "these", "those",
            "when", "they", "them", "from", "some", "just", "only", "been", "have", "has",
            "had", "was", "were", "are", "its", "it", "into", "then", "than", "there",
            "their", "what", "which", "would", "could", "should", "about", "after", "before",
            "not", "get", "got", "gets", "does", "did", "doing", "say", "says", "said",
            "reported", "reports", "report", "please", "also", "very", "much", "many",
            "sometimes", "happens", "happening", "example", "gave", "give", "given",
            "try", "tries", "trying", "one", "two", "few", "all", "any", "out", "off");

    /**
     * Distinctive lowercase terms in first-appearance order, deduped.
     *
     * @param limit maximum tokens to return; {@code <= 0} means no cap.
     */
    public static List<String> extract(String text, int limit) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        for (String token : text.split("[^A-Za-z0-9]+")) {
            String t = token.toLowerCase(Locale.ROOT);
            if (t.length() >= MIN_LENGTH && !STOPWORDS.contains(t)
                    && !t.chars().allMatch(Character::isDigit)) {
                out.add(t);
            }
            if (limit > 0 && out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    /** All distinctive terms, uncapped, as a set — the shape a similarity score wants. */
    public static Set<String> setOf(String text) {
        return new LinkedHashSet<>(extract(text, 0));
    }

    /**
     * Jaccard overlap of two token sets: {@code |A ∩ B| / |A ∪ B|}, in {@code 0..1}.
     *
     * <p>Chosen over raw intersection size because it is length-normalised — a long
     * resolved incident should not outrank a short precise one merely by having more
     * words to collide with. Two empty sets score {@code 0}, not {@code 1}: "we know
     * nothing about either" is not evidence of similarity.
     */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) return 0.0;
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
