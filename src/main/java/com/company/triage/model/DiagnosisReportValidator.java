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
        causeAndResolutionIntegrity(report, knownIds, problems);

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

    /**
     * J28 — CR-6, CR-7, CR-8.
     *
     * <p><b>Every rule here fires only on a POSITIVE claim.</b> A report with
     * {@code likelyCause == null} and {@code likelyResolution == null} passes all three
     * untouched. That is deliberate and load-bearing: abstention is the expected output much
     * of the time, so a rule that punished it would force the very fabrication J28 exists to
     * prevent — and, on the ADK path where validation currently runs outside the retry, every
     * new hard rule is otherwise a new way to degrade mid-demo.
     */
    private static void causeAndResolutionIntegrity(
            DiagnosisReport report, Set<String> knownIds, List<String> problems) {

        LikelyCause cause = report.likelyCause();
        if (cause != null) {
            danglingRefs(List.of(refEntry("likelyCause", cause.evidenceRefs())), knownIds, problems);

            // CR-6 — basis↔source agreement. evidenceRefs prove TRACEABILITY, not SUPPORT:
            // a cause claiming PRIOR_RESOLUTION while citing only a log line satisfies every
            // pre-J28 check. This is the rule that catches a citation of the wrong KIND.
            if (cause.basis() != null && cause.evidenceRefs() != null && !cause.evidenceRefs().isEmpty()) {
                String required = cause.basis().requiredEvidenceSource();
                boolean anyMatches = (report.evidence() == null ? List.<Evidence>of() : report.evidence())
                        .stream()
                        .filter(e -> e != null && cause.evidenceRefs().contains(e.id()))
                        .anyMatch(e -> required.equals(e.source()));
                if (!anyMatches) {
                    problems.add("likelyCause declares basis " + cause.basis() + " but cites no '"
                            + required + "' evidence — a citation of the wrong kind is not support");
                }
            }

            // CR-8 — quote fidelity. quotedFinding must actually appear in some cited
            // evidence, so invention is MECHANICALLY detectable rather than discouraged.
            if (cause.quotedFinding() != null && !cause.quotedFinding().isBlank()) {
                String quote = normalise(cause.quotedFinding());
                boolean grounded = (report.evidence() == null ? List.<Evidence>of() : report.evidence())
                        .stream()
                        .filter(e -> e != null && cause.evidenceRefs() != null
                                && cause.evidenceRefs().contains(e.id()))
                        .anyMatch(e -> e.summary() != null && normalise(e.summary()).contains(quote));
                if (!grounded) {
                    problems.add("likelyCause.quotedFinding does not appear in any evidence it cites "
                            + "— a quotation must be quotable (J28 CR-8)");
                }
            }

            // CR-7 — MEDIUM ceiling. LikelyCause carries no confidence of its own; the
            // constraint is on the REPORT's confidence. Analogical transfer from a past
            // ticket is never HIGH, however good the match looked.
            if (report.confidenceOverall() == Confidence.HIGH) {
                problems.add("confidenceOverall is HIGH on a report whose cause is analogical "
                        + "(likelyCause present) — cap is MEDIUM (J28 CR-7)");
            }

            if (cause.consideredCount() < cause.supportingCount()) {
                problems.add("likelyCause.supportingCount (" + cause.supportingCount()
                        + ") exceeds consideredCount (" + cause.consideredCount() + ")");
            }
        }

        LikelyResolution res = report.likelyResolution();
        if (res != null) {
            checkStep(res.mitigation(), "mitigation", knownIds, problems);
            checkStep(res.permanentFix(), "permanentFix", knownIds, problems);
        }
    }

    private static void checkStep(ResolutionStep step, String label,
                                  Set<String> knownIds, List<String> problems) {
        if (step == null) return;
        danglingRefs(List.of(refEntry("likelyResolution." + label, step.evidenceRefs())),
                knownIds, problems);
        if (step.verb() == null) {
            problems.add("likelyResolution." + label + " has no verb — the closed vocabulary "
                    + "is the safety boundary (J28 PGC-3)");
        }
    }

    /** Whitespace-insensitive containment, so a re-wrapped quote still matches its source. */
    private static String normalise(String s) {
        return s.replaceAll("\\s+", " ").trim();
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
