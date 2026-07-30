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

        if (!problems.isEmpty()) {
            throw new DiagnosisReportInvalidException(report.incidentNumber(), problems);
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
