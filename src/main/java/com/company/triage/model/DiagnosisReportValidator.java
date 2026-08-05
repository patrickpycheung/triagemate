package com.company.triage.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The J4 contract's semantic rules — the ones a Jackson {@code readValue} cannot
 * check on its own, because schema-shaped JSON can still violate them (FND-17).
 *
 * <p>J4's own Rules section always claimed "a validator the agent's final step must
 * satisfy" and its Verification section always claimed it "rejects a report with an
 * empty {@code candidateSystems} or a dangling {@code evidenceRef}" — neither existed
 * until now. Deserialization alone lets a model return valid-looking JSON with zero
 * candidates or an {@code evidenceRefs} entry pointing at an {@code Evidence.id} that
 * was never actually listed, and the UI would have rendered it without complaint.
 *
 * <p>Deliberately narrow: this checks the two properties J4 already promised, not
 * every conceivable invariant (e.g. it does not check confidence ranges or that
 * {@code advisory} is {@code true} — those are typed/constant already, or out of
 * scope for a hackathon-rigor validator). Widen it if the contract grows.
 */
public final class DiagnosisReportValidator {

    private DiagnosisReportValidator() {}

    /**
     * @throws DiagnosisReportInvalidException with every violation found, not just the
     *         first — a model retrying on a single-line error message benefits from
     *         seeing the whole list at once rather than fixing issues one at a time.
     */
    public static void validate(DiagnosisReport report) {
        List<String> problems = new ArrayList<>();

        if (report.candidateSystems() == null || report.candidateSystems().isEmpty()) {
            problems.add("candidateSystems is empty — ranked shortlist required, never zero candidates");
        }
        if (report.evidence() == null || report.evidence().isEmpty()) {
            problems.add("evidence is empty — every conclusion must tie to evidenceRefs (J4 Rules)");
        }

        Set<String> knownIds = (report.evidence() == null ? List.<Evidence>of() : report.evidence())
                .stream().map(Evidence::id).collect(Collectors.toSet());

        danglingRefs(report.candidateSystems() == null ? List.of() : report.candidateSystems().stream()
                .map(c -> refEntry(c.name(), c.evidenceRefs())).toList(), knownIds, problems);

        if (report.suggestedAssignment() != null) {
            danglingRefs(List.of(refEntry(
                    "suggestedAssignment(" + report.suggestedAssignment().group() + ")",
                    report.suggestedAssignment().evidenceRefs())), knownIds, problems);
        }

        duplicateEvidenceIds(report, problems);
        uncitedCandidates(report, problems);

        if (!problems.isEmpty()) {
            throw new DiagnosisReportInvalidException(report.incidentNumber(), problems);
        }
    }

    /**
     * J13/ECI-1 — evidence ids must be unique.
     *
     * <p>The dangling-ref rule above asks "does this id exist?", which a DUPLICATE id passes
     * trivially — so a report could carry three different GitLab hits all labelled
     * {@code e-code}, and every {@code evidenceRefs: ["e-code"]} would validate while pointing
     * at whichever one the reader happened to scroll to first. An id that does not identify is
     * not an id, and the whole citation contract rests on it doing so.
     *
     * <p>Reported as a list of the offending ids rather than the first, matching this class's
     * report-everything contract: a model repairing its output benefits from the full set.
     */
    private static void duplicateEvidenceIds(DiagnosisReport report, List<String> problems) {
        if (report.evidence() == null) return;
        Set<String> seen = new java.util.LinkedHashSet<>();
        Set<String> duplicated = new java.util.LinkedHashSet<>();
        for (Evidence e : report.evidence()) {
            if (e != null && e.id() != null && !seen.add(e.id())) {
                duplicated.add(e.id());
            }
        }
        for (String id : duplicated) {
            problems.add("duplicate evidence id '" + id + "' — ids must be unique, or an "
                    + "evidenceRef naming it cannot identify which item it cites");
        }
    }

    /**
     * J13/ECI-2 — a candidate system must cite at least one piece of evidence.
     *
     * <p>J4's rule is "every conclusion ties to evidenceRefs". A candidate with an EMPTY
     * {@code evidenceRefs} is a conclusion with no tie at all — it passed validation only
     * because the dangling-ref check iterates the refs, and an empty list has nothing to
     * iterate. On the deterministic path this happened whenever a system was observed in logs
     * but no {@code e-log} Evidence was gathered for it; the report then named a suspect
     * system and pointed at nothing.
     *
     * <p>Deliberately NOT extended to {@code suggestedAssignment}: the honest
     * "Unassigned — no ownership or similar-incident signal" fallback legitimately has only
     * the ticket itself to cite, and forcing a citation there would push the code toward
     * inventing one.
     */
    private static void uncitedCandidates(DiagnosisReport report, List<String> problems) {
        if (report.candidateSystems() == null) return;
        for (CandidateSystem c : report.candidateSystems()) {
            if (c == null) continue;
            if (c.evidenceRefs() == null || c.evidenceRefs().isEmpty()) {
                problems.add("candidate system '" + c.name() + "' cites no evidence — every "
                        + "conclusion must tie to at least one evidenceRef (J4 Rules)");
            }
        }
    }

    private record RefEntry(String owner, List<String> refs) {}

    private static RefEntry refEntry(String owner, List<String> refs) {
        return new RefEntry(owner, refs == null ? List.of() : refs);
    }

    private static void danglingRefs(List<RefEntry> entries, Set<String> knownIds, List<String> problems) {
        for (RefEntry e : entries) {
            for (String ref : e.refs()) {
                if (!knownIds.contains(ref)) {
                    problems.add("dangling evidenceRef '" + ref + "' on " + e.owner()
                            + " — no Evidence with that id exists in this report");
                }
            }
        }
    }
}
