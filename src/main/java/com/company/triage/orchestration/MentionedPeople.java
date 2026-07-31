package com.company.triage.orchestration;

import com.company.triage.model.Contact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * People named in free text — ServiceNow ticket prose and Confluence page bodies (FND-64).
 *
 * <p>J9 ("who should I talk to?") was previously answered only from <b>API metadata</b>:
 * Confluence page author/last-editor and GitLab recent committers. Two real sources of names
 * were left on the floor:
 *
 * <ul>
 *   <li><b>ServiceNow</b> — nothing at all was extracted. Yet the ticket is where a human
 *       already wrote down who else is involved: the person who left each comment, and anyone
 *       they named in it ("escalated after speaking with Priya Nair"). That is a *stronger*
 *       signal than "edited the runbook 8 months ago", because it means someone is already
 *       engaged with this specific incident.</li>
 *   <li><b>Confluence page bodies</b> — only the page's author/editor metadata was read, never
 *       the text. Runbooks routinely name an escalation contact or owner in prose, and that
 *       named person is often more relevant than whoever last fixed a typo on the page.</li>
 * </ul>
 *
 * <p>Sumo is deliberately absent: log lines carry no identity in this app's fixtures or in
 * any realistic Sumo setup here, so there is nothing to extract (confirmed with the operator).
 *
 * <p><b>Precision over recall.</b> A wrong name on a triage report sends an engineer to bother
 * an uninvolved colleague, so the tiers below are ordered by how much they can be trusted, and
 * the loosest one (a bare capitalised pair) is filtered hard against known system/team
 * vocabulary — "Payment Service", "Service Desk" and "Order Portal" all have person-name shape
 * and none of them are people.
 */
final class MentionedPeople {

    private MentionedPeople() {}

    private static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern HANDLE = Pattern.compile("(?<![\\w@])@([A-Za-z][\\w.-]{2,})\\b");

    /** A ServiceNow journal line as FND-61 renders it: {@code "sys_created_by: text"}. */
    private static final Pattern JOURNAL_AUTHOR = Pattern.compile("^([A-Za-z][\\w.'-]{1,40}):\\s");

    private static final String NAME = "[A-Z][a-zA-Z'\\u2019-]{1,20}\\s+[A-Z][a-zA-Z'\\u2019-]{1,20}";

    /**
     * High precision: a person-shaped name introduced by a phrase that only makes sense about
     * a person. "spoke with Priya Nair" is a person; "in Payment Service" is not.
     */
    private static final Pattern CUED_NAME = Pattern.compile(
            "(?i:\\b(?:spoke (?:to|with)|talked (?:to|with)|escalated to|escalated by|raised (?:with|by)|"
                    + "assigned to|handed (?:to|over to)|confirmed (?:by|with)|reported by|contacted|"
                    + "asked|per|from|cc:?|contact|owner|owned by|reach out to|ping)\\s+)(" + NAME + ")");

    /** Loosest tier: any capitalised pair. Only survives if it clears {@link #NOT_A_PERSON}. */
    private static final Pattern BARE_NAME = Pattern.compile("\\b(" + NAME + ")\\b");

    /**
     * Words that mark a capitalised pair as a system, team or process rather than a person.
     * If EITHER token matches, the pair is rejected.
     */
    private static final Set<String> NOT_A_PERSON = Set.of(
            "service", "services", "portal", "platform", "support", "desk", "api", "gateway",
            "server", "system", "systems", "team", "group", "squad", "production", "staging",
            "error", "errors", "payment", "payments", "order", "orders", "batch", "ledger",
            "export", "checkout", "incident", "change", "request", "known", "runbook",
            "confluence", "gitlab", "sumo", "logic", "servicenow", "now", "table", "mismatch",
            "reconcile", "application", "software", "database", "cluster", "queue", "job",
            "environment", "release", "version", "build", "customer", "customers", "user",
            "users", "caller", "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "january", "february", "march", "april", "june", "july",
            "august", "september", "october", "november", "december");

    /**
     * Names in one blob of text.
     *
     * @param knownSystemNames CI / assignment group / candidate system names for THIS incident.
     *                         These are the highest-value denylist entries because they are the
     *                         exact system-shaped phrases this ticket is about, and they'd
     *                         otherwise be the most likely false positives.
     */
    static List<String> namesIn(String text, Set<String> knownSystemNames) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();

        Matcher cued = CUED_NAME.matcher(text);
        while (cued.find()) addIfPerson(out, cued.group(1), knownSystemNames);

        Matcher bare = BARE_NAME.matcher(text);
        while (bare.find()) addIfPerson(out, bare.group(1), knownSystemNames);

        return List.copyOf(out);
    }

    private static void addIfPerson(Set<String> out, String candidate, Set<String> knownSystemNames) {
        String trimmed = candidate.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String known : knownSystemNames) {
            if (known != null && !known.isBlank()
                    && (lower.equals(known.toLowerCase(Locale.ROOT))
                        || known.toLowerCase(Locale.ROOT).contains(lower))) {
                return;
            }
        }
        for (String token : lower.split("\\s+")) {
            if (NOT_A_PERSON.contains(token)) return;
        }
        out.add(trimmed);
    }

    /** Email addresses and {@code @handles} — unambiguous, so no denylist needed. */
    static List<String> handlesIn(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher email = EMAIL.matcher(text);
        while (email.find()) out.add(email.group());
        Matcher handle = HANDLE.matcher(text);
        while (handle.find()) out.add(handle.group(1));
        return List.copyOf(out);
    }

    /**
     * Contacts from the ServiceNow ticket: whoever left each comment/work note, plus anyone
     * named in the ticket prose. Author beats mention — having written on the ticket is the
     * stronger signal of engagement.
     */
    static List<Contact> fromIncident(String number, String description, String shortDescription,
                                      List<String> comments, List<String> workNotes,
                                      Set<String> knownSystemNames) {
        Map<String, Contact> byKey = new LinkedHashMap<>();
        List<String> journal = new ArrayList<>();
        if (comments != null) journal.addAll(comments);
        if (workNotes != null) journal.addAll(workNotes);

        for (String entry : journal) {
            if (entry == null || entry.isBlank()) continue;
            Matcher author = JOURNAL_AUTHOR.matcher(entry);
            if (author.find()) {
                String who = author.group(1);
                byKey.putIfAbsent(who.toLowerCase(Locale.ROOT), new Contact(who, who,
                        "servicenow", "commented on this incident", number,
                        "wrote on the ticket"));
            }
        }

        String prose = String.join(" ",
                shortDescription == null ? "" : shortDescription,
                description == null ? "" : description,
                String.join(" ", journal));
        for (String name : namesIn(prose, knownSystemNames)) {
            byKey.putIfAbsent(name.toLowerCase(Locale.ROOT), new Contact(name, null,
                    "servicenow", "named in the incident description or comments", number,
                    "mentioned on the ticket"));
        }
        for (String handle : handlesIn(prose)) {
            byKey.putIfAbsent(handle.toLowerCase(Locale.ROOT), new Contact(handle, handle,
                    "servicenow", "named in the incident description or comments", number,
                    "mentioned on the ticket"));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Contacts named in a Confluence page's body — the escalation contact or owner a runbook
     * names in prose, which the page's author/editor metadata does not capture.
     */
    static List<Contact> fromPageBody(String pageTitle, String pageUrl, String body,
                                      Set<String> knownSystemNames) {
        List<Contact> out = new ArrayList<>();
        for (String name : namesIn(body, knownSystemNames)) {
            out.add(new Contact(name, null, "confluence",
                    "named in the runbook \"%s\"".formatted(pageTitle), pageUrl,
                    "mentioned on the page"));
        }
        for (String handle : handlesIn(body)) {
            out.add(new Contact(handle, handle, "confluence",
                    "named in the runbook \"%s\"".formatted(pageTitle), pageUrl,
                    "mentioned on the page"));
        }
        return List.copyOf(out);
    }
}
