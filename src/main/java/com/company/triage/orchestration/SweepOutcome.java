package com.company.triage.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * J31/ASO-2 — what a multi-project search actually did, as opposed to what its last attempt did.
 *
 * <p>This replaces a boolean. The boolean was assigned in a catch block, so it recorded
 * whichever attempt failed most recently and silently discarded everything else the sweep had
 * learned: a project searched successfully and empty, followed by an unreachable one, reported
 * <em>"could not search"</em> — a degradation — for a search that ran and truthfully found
 * nothing. Those two readings lead a triager to opposite conclusions, which is the whole reason
 * J30/GEB-3 exists; GEB-3 simply specified it for a single attempt, and J30/GEB-1 later made
 * multiple attempts the normal case.
 *
 * <p><b>Five outcomes, not two.</b> The one that is easy to miss is the fourth: once a failed
 * project no longer ends the sweep (ASO-1), <em>failure followed by a hit</em> becomes an
 * ordinary result, and reporting only the hit implies the search was complete when one project
 * was never reached. Every state below either names its failures or has none.
 *
 * <p>Failures carry the <b>project</b>. {@code GatewayUnavailableException} holds only the
 * system name, so two failed entries produced identical text and a partial result could not say
 * what it had missed — which is the only thing that makes "partial" actionable rather than
 * merely honest.
 */
final class SweepOutcome {

    private final List<String> searched = new ArrayList<>();
    /** Ordered so the report lists failures in the order they were attempted. */
    private final Map<String, String> failures = new LinkedHashMap<>();

    void searched(String project) { searched.add(project); }

    void failed(String project, String error) { failures.put(project, error); }

    boolean anySucceeded() { return !searched.isEmpty(); }

    boolean anyFailed() { return !failures.isEmpty(); }

    /**
     * True only when no project could be searched. This is the narrow condition the "COULD NOT
     * SEARCH" claim is allowed to rest on — <b>not</b> "some attempt failed", which is what the
     * old boolean meant.
     */
    boolean nothingWasSearched() { return searched.isEmpty() && !failures.isEmpty(); }

    /**
     * One line per unreachable project, for {@code missingInformation} (J14/FRI-5 signal 2).
     * Empty when nothing failed — a clean sweep must not invent a gap to admit.
     */
    List<String> missingInformation() {
        List<String> out = new ArrayList<>();
        failures.forEach((project, error) -> out.add(
                "%s — could not search %s, so the log line was not tied to the code that emits it"
                        .formatted(error, project)));
        return out;
    }

    /**
     * The trace/report phrasing for this sweep. Matches the voice of the sibling honesty rules
     * (J25/KQR-4 for Confluence, J29/LLF-2 for log levels) rather than inventing new wording.
     *
     * @param hitCount citations produced by the sweep, after J13/ECI-4's cap
     */
    String describe(int hitCount) {
        String failed = String.join(", ", failures.keySet());
        if (hitCount > 0) {
            return anyFailed()
                    // The hits are real, and they may not be all of them — say both.
                    ? "%d hit(s), but %s could not be searched — there may be more"
                            .formatted(hitCount, failed)
                    : "%d hit(s) (log↔code citation)".formatted(hitCount);
        }
        if (nothingWasSearched()) {
            return "COULD NOT SEARCH (%s unreachable) — this is not 'no code matched'".formatted(failed);
        }
        return anyFailed()
                // Searched some, could not reach others: neither a clean negative nor a
                // blanket failure. Nothing is known about the projects that were not reached.
                ? "no match in %s; %s could not be searched — this is a PARTIAL answer"
                        .formatted(String.join(", ", searched), failed)
                : "0 hit(s) — searched %s and found nothing".formatted(String.join(", ", searched));
    }
}
