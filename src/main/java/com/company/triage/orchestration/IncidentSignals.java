package com.company.triage.orchestration;

import com.company.triage.model.IncidentContext;
import com.company.triage.model.SymptomTokens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@link DeterministicDiagnosisEngine} can work out about an incident <b>from the
 * incident itself</b>, and how it turns that into per-platform query parameters (FND-62).
 *
 * <p><b>Why this exists.</b> "Deterministic" was being read as "hardcoded": the engine sent a
 * fixed Confluence string (FND-59), always searched {@code allowedScopes.get(0)} whatever the
 * incident was about, and passed a GitLab project as a <i>literal</i> that bypassed
 * {@code triage.gitlab.allowed-projects} entirely. That is fine for the one seeded demo
 * incident and wrong for every other one — and this engine is also the FND-7 fallback, so
 * "every other one" is exactly when it runs for real.
 *
 * <p>Deterministic means <b>predictable</b>, not <b>fixed</b>: same incident in, same queries
 * out, no model involved — but the queries are derived from the ticket. Three derivations:
 *
 * <ol>
 *   <li><b>Identifiers</b> — an order/correlation id to search logs by. The old pattern only
 *       matched {@code INC-ORD-\d+}, the demo fixture's exact shape; anything else fell back
 *       to the literal {@code "error"}. Now several common shapes, most specific first.</li>
 *   <li><b>Keywords</b> — distinctive terms from the symptom text, function words removed, so
 *       a knowledge search gets signal rather than a whole English sentence.</li>
 *   <li><b>Platform targeting</b> — which allowlisted Sumo scope / GitLab project to search.
 *       Ranked by name overlap with the affected application, then <b>all of them are swept
 *       in that order</b>. Ranking alone would be a guess, and a wrong guess silently loses
 *       the evidence: the demo incident's CI is "Order Portal" while the failing system is
 *       Payment Service downstream — unguessable from the ticket, which is the whole point of
 *       the diagnosis. Sweeping is affordable precisely because this engine is not the agent:
 *       the allowlist is small and bounded by config, and there is no per-call LLM budget to
 *       spend (the J8 tool budget bounds the ADK path, not this one). Rank-then-sweep gets
 *       the most likely source first for a readable trace, and correctness regardless.</li>
 * </ol>
 */
record IncidentSignals(
        String app,
        AppSource appSource,
        /** The ticket's subject line as written — J25/KQR-1 uses it verbatim for the
         *  knowledge search, because a relevance-ranked search needs the connective
         *  structure that keyword extraction throws away. */
        String symptomText,
        List<String> keywords,
        String primaryIdentifier,
        List<String> identifiers
) {

    /**
     * J24/SFF-2 — where {@link #app} came from.
     *
     * <p>With the FND-67-era parse bug fixed (J24/SFF-1: reference fields arrived as
     * {@code {display_value, link}} objects and parsed to {@code ""}), a blank CI is now rare
     * AND meaningful. That changes what the fallback means: it used to fire on essentially
     * every real ticket, so it was effectively the primary path; now it fires only when the
     * ticket genuinely has no configuration item.
     *
     * <p>The fallback stays — a CI really can be unset — but it stops being invisible. The
     * honesty contract applies to a DERIVATION, not just to a fetch: guessing which system is
     * affected is allowed, guessing silently is not.
     */
    enum AppSource {
        /** The CMDB named it — {@code cmdb_ci} on the incident. */
        FROM_CMDB_CI,
        /** Nothing named it; inferred from the ticket's subject line. */
        FROM_SUBJECT_LINE,
        /** The ticket carries neither a CI nor a usable subject line. */
        UNKNOWN
    }

    /** Whether {@link #app} was inferred rather than read from the CMDB (J24/SFF-2). */
    boolean appWasInferred() {
        return appSource != AppSource.FROM_CMDB_CI;
    }

    /** Ticket-ish ids: {@code INC-ORD-4471}, {@code ORD-1234}, {@code PAY-99}. Most specific. */
    private static final Pattern DASHED_ID = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d{3,}\\b");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** Trace/correlation ids: a long bare hex run. Least specific, so ranked last. */
    private static final Pattern HEX_TRACE = Pattern.compile("\\b[0-9a-f]{16,32}\\b");

    static IncidentSignals from(IncidentContext inc) {
        String symptom = text(inc.shortDescription());
        String detail = text(inc.description());
        // FND-67: exclude this app's own advisory notes. Once the real gateway started
        // reading the journal (FND-61), a re-diagnosis of the same incident would otherwise
        // draw keywords and identifiers out of its OWN previous diagnosis — each run drifting
        // further from what the human actually wrote.
        String conversation = java.util.stream.Stream
                .concat(safe(inc.comments()).stream(), safe(inc.workNotes()).stream())
                .filter(e -> !com.company.triage.model.DiagnosisReport.isAiAuthoredNote(e))
                .collect(java.util.stream.Collectors.joining(" "));
        String rawText = (symptom + " " + detail + " " + conversation).trim();

        List<String> ids = extractIdentifiers(rawText, inc.number());

        // The affected application, best available signal. configurationItem is the CMDB's
        // answer and the most structured thing we have; fall back to the symptom line so a
        // ticket with no CI still targets something rather than defaulting blindly.
        //
        // FND-67: the first real ServiceNow ticket had an EMPTY cmdb_ci, so the fallback put
        // a whole sentence in `app` — which then got appended verbatim to the Confluence
        // query and fed to allowlist ranking as if it were a system name. Cap the fallback at
        // a name-sized leading fragment: enough to target with, not a paragraph.
        //
        // J24/SFF-2: record WHICH of those two answered. Before SFF-1 fixed the reference-field
        // parse, cmdb_ci was blank on every real ticket, so this fallback was silently the
        // primary path — the sentence fragment "Hazards being recorded on" reached the Sumo
        // slug and matched nothing. Now the fallback is genuinely exceptional, and when it
        // fires the report says so rather than presenting a guess as a reading.
        String app;
        AppSource appSource;
        if (notBlank(inc.configurationItem())) {
            app = inc.configurationItem().trim();
            appSource = AppSource.FROM_CMDB_CI;
        } else {
            app = leadingPhrase(symptom);
            appSource = notBlank(app) ? AppSource.FROM_SUBJECT_LINE : AppSource.UNKNOWN;
        }

        return new IncidentSignals(app, appSource, symptom, extractKeywords(rawText),
                ids.isEmpty() ? null : ids.get(0), ids);
    }

    /** Identifiers in the ticket text, most specific pattern first, deduped, in order. */
    private static List<String> extractIdentifiers(String rawText, String incidentNumber) {
        Set<String> found = new LinkedHashSet<>();
        for (Pattern p : List.of(DASHED_ID, UUID, HEX_TRACE)) {
            Matcher m = p.matcher(rawText);
            while (m.find()) {
                String hit = m.group();
                // The incident's own number is not a useful log-search term — it identifies
                // the ticket, not the failing transaction.
                if (!hit.equalsIgnoreCase(text(incidentNumber))) {
                    found.add(hit);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Distinctive terms, in first-appearance order. Capped so a long ticket can't dominate.
     *
     * <p>J26: the tokenising and the stopword list moved to {@link SymptomTokens} when the
     * similar-incident ranker needed the same answer. Two copies of a stopword list is the
     * two-sources-of-truth split FND-40 and FND-62 were each filed for; this stays a named
     * method because the CAP is a property of query building, not of tokenising.
     */
    private static List<String> extractKeywords(String rawText) {
        return SymptomTokens.extract(rawText, 8);
    }

    /**
     * Knowledge search: the symptom AS WRITTEN plus the affected system.
     *
     * <p>J25/KQR-1. This used to be the extracted keyword bag plus the app. Measured against
     * the real instance (2026-08-05, INC0010010), with {@code siteSearch} now doing the
     * retrieval, the two forms differ in what they surface:
     *
     * <ul>
     *   <li>keyword bag + app → six pages, all generic application documentation;</li>
     *   <li>subject line + app → the same documentation <b>plus</b>
     *       {@code "INC2616763 - Hazards captured on handhelds not being saved"} — a PRIOR
     *       INCIDENT of the same fault — and {@code "UC17.30 Hazards created by handheld for
     *       facility not setup"}, a use case describing the exact scenario.</li>
     * </ul>
     *
     * <p>A prior incident for the same symptom is the single most useful thing a triage can
     * put in front of a human, and only the second form finds it. Keyword extraction discards
     * exactly the connective structure ("not appearing in", "being recorded on") that a
     * relevance-ranked search uses to tell one hazard page from another.
     *
     * <p>The app name is still appended when it came from the CMDB — the subject line does
     * not always name the affected system — but is skipped when {@code app} was itself
     * inferred FROM that subject line (J24/SFF-2), where appending it would just duplicate
     * words already present and dilute the ranking.
     */
    String confluenceQuery() {
        String symptom = String.join(" ", keywords);
        if (notBlank(symptomText)) {
            symptom = symptomText.trim();
        }
        if (appSource == AppSource.FROM_CMDB_CI && notBlank(app)
                && !symptom.toLowerCase(Locale.ROOT).contains(app.toLowerCase(Locale.ROOT))) {
            return (symptom + " " + app).trim();
        }
        return symptom;
    }

    /**
     * Log search: the transaction identifier when the ticket carries one — by far the most
     * selective term available. Otherwise the top keywords; {@code "error"} only as a genuine
     * last resort, which is what the old code used unconditionally whenever the one hardcoded
     * order-id pattern missed.
     */
    String logQuery() {
        if (primaryIdentifier != null) return primaryIdentifier;
        if (!keywords.isEmpty()) return String.join(" ", keywords.subList(0, Math.min(3, keywords.size())));
        return "error";
    }

    /**
     * The allowlist, most-likely-relevant first, by token overlap with {@link #app}. Never
     * filters: an entry that matches nothing still appears, last — see the class javadoc on
     * why the engine sweeps rather than picks. Stable, so equal scores keep config order.
     */
    static List<String> rankAllowlist(String app, List<String> allowlist) {
        Set<String> appTokens = tokens(app);
        List<String> ranked = new ArrayList<>(allowlist);
        ranked.sort(Comparator.comparingInt((String entry) -> {
            Set<String> t = tokens(entry);
            t.retainAll(appTokens);
            return -t.size();          // negative → higher overlap first
        }));
        return List.copyOf(ranked);
    }

    /**
     * The project slug used to compose a Sumo {@code _sourceCategory} — the affected
     * application, lowercased and hyphenated ("Delivery Hazards" → "delivery-hazards"),
     * which is the convention the log estate's category names follow.
     */
    static String projectSlug(String app) {
        String slug = text(app).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")     // spaces, slashes, punctuation → one hyphen
                .replaceAll("(^-+)|(-+$)", "");    // no leading/trailing hyphens
        return slug;
    }

    /**
     * Maps the incident's free-text environment onto one of the deployment codes the log
     * categories use ({@code pdev/ptest/stest/vtest/prod}).
     *
     * <p>Best-effort by design: the field is human-entered and its vocabulary isn't fixed,
     * so anything unrecognised falls back to {@code prod}. That default is the safe one —
     * an incident worth triaging is overwhelmingly a production incident, and guessing a
     * lower environment would search a category with no relevant logs in it. The caller can
     * always pass an explicit environment instead (the ADK tool exposes it as a parameter).
     */
    static String environmentCode(String environment, List<String> allowed, String fallback) {
        return resolveEnvironment(environment, allowed, fallback).code();
    }

    /**
     * J24/SFF-4 — the environment, plus which rung of the ladder answered.
     *
     * <p>{@code u_environment} does not exist on the live AusPost instance, so this silently
     * fell through to the {@code prod} default and pointed the log search at the wrong
     * environment — the second wrong component of the {@code _sourceCategory} in
     * {@code docs/Siyad_Findings.md} §3 (sent {@code /prod/}, expected {@code /ptest/}). The
     * value being a default rather than a reading was invisible.
     *
     * <p>Rejected: inferring the environment from the CI name. It reads as clever and fails
     * silently in exactly the cases it matters.
     */
    static EnvironmentChoice resolveEnvironment(String environment, List<String> allowed, String fallback) {
        String e = text(environment).toLowerCase(Locale.ROOT);
        String guess = null;
        if (e.contains("prod")) guess = "prod";
        else if (e.contains("vat") || e.contains("uat") || e.contains("vtest")) guess = "vtest";
        else if (e.contains("stag") || e.contains("stest")) guess = "stest";
        else if (e.contains("dev") || e.contains("pdev")) guess = "pdev";
        else if (e.contains("test") || e.contains("sit") || e.contains("qa")) guess = "ptest";
        // Only honour the guess if it's actually a configured environment.
        if (guess != null && (allowed == null || allowed.isEmpty() || allowed.contains(guess))) {
            return new EnvironmentChoice(guess, EnvironmentSource.FROM_TICKET);
        }
        // A value was present but named an environment this deployment does not configure —
        // worth distinguishing from "the ticket said nothing at all", because it means the
        // ticket and the config disagree rather than the ticket being silent.
        return new EnvironmentChoice(fallback,
                guess != null ? EnvironmentSource.TICKET_VALUE_NOT_CONFIGURED
                        : notBlank(e) ? EnvironmentSource.TICKET_VALUE_UNRECOGNISED
                        : EnvironmentSource.DEFAULTED);
    }

    /** J24/SFF-4: the resolved environment and the rung of the ladder that supplied it. */
    record EnvironmentChoice(String code, EnvironmentSource source) {
        boolean wasDefaulted() {
            return source != EnvironmentSource.FROM_TICKET;
        }
    }

    enum EnvironmentSource {
        /** The ticket's own environment field named a configured environment. */
        FROM_TICKET,
        /** The ticket named an environment this deployment does not configure. */
        TICKET_VALUE_NOT_CONFIGURED,
        /** The ticket carried a value nothing recognised. */
        TICKET_VALUE_UNRECOGNISED,
        /** The ticket said nothing; the global default answered. */
        DEFAULTED
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : text(s).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    private static String text(String s) { return s == null ? "" : s; }

    private static List<String> safe(List<String> l) { return l == null ? List.of() : l; }

    /**
     * FND-67: the leading name-like fragment of a subject line — up to the first separator
     * ({@code -}, {@code :}, {@code |}) and at most 4 words. "Delivery Hazards - All hazards
     * are no longer present" → "Delivery Hazards". Used only when {@code cmdb_ci} is empty,
     * which real tickets frequently are.
     */
    private static String leadingPhrase(String symptom) {
        if (symptom == null || symptom.isBlank()) return "";
        String head = symptom.split("[-:|\\u2014]", 2)[0].trim();
        String[] words = head.split("\\s+");
        return words.length <= 4 ? head : String.join(" ", java.util.Arrays.copyOf(words, 4));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
