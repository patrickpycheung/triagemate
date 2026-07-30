package com.company.triage.model;

/**
 * A suggested person to talk to about this incident (J9). Derived from the same
 * evidence the triage already gathered — never a fresh people-search:
 * <ul>
 *   <li>{@code confluence} — someone who authored / last edited a runbook the triage cited;</li>
 *   <li>{@code gitlab} — someone who recently committed to the implicated source file
 *       (git history since the last release).</li>
 * </ul>
 *
 * <p>Advisory and display-only (J8): shown in the triage UI so the assigned engineer
 * knows who has context. Deliberately NOT auto-posted into the ServiceNow ticket —
 * names/emails are not pushed into the record.
 *
 * @param name    display name, e.g. "Priya Nair"
 * @param handle  contact handle — email or {@code @username} (may be blank if unknown)
 * @param source  where the signal came from: {@code confluence}, {@code gitlab}, or
 *                {@code confluence+gitlab} once merged across both
 * @param reason  human-readable why-relevant, e.g. "last edited the reconcile runbook"
 * @param link    profile / page / file link for the engineer to follow
 * @param signal  the recency/volume signal, e.g. "2 commits since v2.3.1" or
 *                "last edited 2026-07-20"
 */
public record Contact(
        String name,
        String handle,
        String source,
        String reason,
        String link,
        String signal
) {}
