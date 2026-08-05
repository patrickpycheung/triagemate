package com.company.triage.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The strict JSON diagnosis contract (J4) — the spine of the whole app. The
 * ServiceNow work note (J5) and the demo UI (J7) are both renderings of this object.
 *
 * <p>Rules: candidates and assignment are ranked shortlists (never one forced
 * answer); every conclusion ties to {@code evidence} via evidenceRefs; conflicting
 * evidence is surfaced, not hidden; {@code advisory} is always true this phase.
 *
 * <p>J28 appends {@code likelyCause} and {@code likelyResolution} — the only two fields that
 * answer <em>why</em> and <em>what was done</em>. Both are nullable, and null is not a
 * degraded state but the honest and frequent one: see {@link LikelyCause}.
 */
public record DiagnosisReport(
        String incidentNumber,
        OffsetDateTime generatedAt,
        String reportedSymptom,
        String affectedFunction,
        String environment,
        Identifiers identifiers,
        List<CandidateSystem> candidateSystems,
        SuggestedAssignment suggestedAssignment,
        List<Evidence> evidence,
        List<Contact> suggestedContacts,
        List<String> contradictingEvidence,
        List<String> missingInformation,
        String recommendedNextAction,
        Confidence confidenceOverall,
        boolean advisory,
        // J28 — appended rather than inserted so the arity change breaks every positional
        // constructor at COMPILE time. AdkDiagnosisEngine#stampGeneratedAt rebuilds this
        // record positionally; had these been optional, it would have silently dropped them.
        LikelyCause likelyCause,
        LikelyResolution likelyResolution
) {
    /**
     * Prefix on every work note this app writes. Both notes below start with it, and it is
     * the marker that lets a LATER run recognise its own output when it reads the ticket's
     * journal back.
     *
     * <p>FND-67: without this, the app fed on itself. Once FND-61 made the real gateway read
     * comments/work notes, a second diagnosis of the same incident saw the first one's notes
     * as ordinary ticket conversation — so its keywords, identifiers and contact names were
     * partly extracted from its own prior diagnosis, and drifted further from the human's
     * actual words on every re-run. The first real-ServiceNow run surfaced it immediately:
     * "AI Triage" was suggested as a person to talk to, extracted from this very prefix.
     */
    public static final String AI_NOTE_PREFIX = "[AI Triage ·";

    /**
     * Is this journal entry one this app wrote (as opposed to a human's)? Matched anywhere in
     * the entry, not just at position 0, because {@code RealServiceNowGateway} prefixes each
     * journal line with its author ({@code "sys_created_by: <text>"}).
     */
    public static boolean isAiAuthoredNote(String journalEntry) {
        return journalEntry != null && journalEntry.contains(AI_NOTE_PREFIX);
    }

    /**
     * Comment 1 of the automatic write-back (J5): the SOURCES the triage consulted,
     * posted first so the diagnosis that follows is auditable — every claim is one
     * click from its evidence. Each line links to the document / log / file / ticket.
     */
    public String toSourcesNote() {
        StringBuilder b = new StringBuilder();
        b.append("[AI Triage · Sources consulted]\n");
        b.append("Evidence gathered for this incident — links to the exact material used:\n\n");
        if (evidence == null || evidence.isEmpty()) {
            b.append("(no external sources were consulted)\n");
        } else {
            for (Evidence e : evidence) {
                b.append("• ").append(e.source()).append(" — ").append(e.summary());
                if (e.link() != null && !e.link().isBlank()) {
                    b.append("  [").append(e.link()).append("]");
                }
                b.append("\n");
            }
        }
        b.append("\nSee the AI Triage diagnosis comment below for the interpretation of this evidence.");
        return b.toString();
    }

    /**
     * Comment 2 of the automatic write-back (J5): the first-pass DIAGNOSIS — the
     * triage's view and suggested outcome. Advisory only; it never reassigns, closes,
     * or re-prioritises the ticket. Posted after the sources comment.
     */
    public String toDiagnosisNote() {
        StringBuilder b = new StringBuilder();
        b.append("[AI Triage · First-pass diagnosis — advisory only]\n\n");
        b.append("What appears to have happened: ").append(reportedSymptom).append("\n\n");
        b.append("Likely involved systems: ");
        b.append(candidateSystems.stream()
                .map(c -> "%s (%.0f%%)".formatted(c.name(), c.confidence() * 100))
                .reduce((a, c) -> a + ", " + c).orElse("—")).append("\n");
        if (suggestedAssignment != null) {
            b.append("Suggested assignment group: ").append(suggestedAssignment.group())
             .append(" — ").append(suggestedAssignment.confidence().name().toLowerCase())
             .append(" confidence\n");
        }
        if (recommendedNextAction != null) {
            b.append("Recommended next check: ").append(recommendedNextAction).append("\n");
        }
        b.append(causeSection());
        b.append(resolutionSection());
        if (missingInformation != null && !missingInformation.isEmpty()) {
            b.append("Still missing: ").append(String.join(", ", missingInformation)).append("\n");
        }
        b.append("\nAI-assisted and advisory. No reassignment, closure, or priority change has been made — "
                + "the assigned engineer decides. Sources are in the comment above.");
        return b.toString();
    }

    /**
     * J28/PGC-6 — "Why this may be happening", or an explicit statement that it is not
     * established.
     *
     * <p>The abstention branch says <b>"not established"</b> rather than "unknown" on
     * purpose: it implies work was done, and paired with the denominator it is genuinely
     * actionable negative information — "we looked at three similar tickets and none of them
     * recorded what was wrong" tells an engineer something real.
     */
    private String causeSection() {
        if (likelyCause == null) {
            // Carry the denominator even when abstaining — "we looked at 2 and none of them
            // recorded what was wrong" is actionable; a bare "not established" is not. The
            // count is derived from evidence already on the report rather than by threading a
            // second carrier through, so `null likelyCause` stays the single encoding of
            // abstention.
            long considered = evidence == null ? 0 : evidence.stream()
                    .filter(e -> e != null && e.id() != null && e.id().startsWith("e-sim-"))
                    .count();
            return considered == 0
                    ? "\nWhy this may be happening: not established — no similar resolved incidents were found.\n"
                    : "\nWhy this may be happening: not established — 0 of %d similar resolved incidents recorded what was wrong.\n"
                            .formatted(considered);
        }
        StringBuilder b = new StringBuilder("\nWhy this may be happening: ");
        b.append(String.join(", ", likelyCause.citedArtifacts()))
         .append(likelyCause.citedArtifacts().size() == 1 ? " was closed with this note —\n  \"" : " were closed with this note —\n  \"")
         .append(likelyCause.quotedFinding()).append("\"\n");
        b.append("  Based on ").append(likelyCause.supportingCount()).append(" of ")
         .append(likelyCause.consideredCount()).append(" similar resolved incidents.\n");
        return b.toString();
    }

    /**
     * J28/PGC-6 — "How similar incidents WERE resolved". Past tense and closed-vocabulary
     * only: every value rendered here is a {@link ResolutionVerb} or a ServiceNow
     * {@code close_code}, never gathered free text.
     */
    private String resolutionSection() {
        if (likelyResolution == null
                || (likelyResolution.mitigation() == null && likelyResolution.permanentFix() == null)) {
            return "";
        }
        StringBuilder b = new StringBuilder("\nHow similar incidents were resolved:\n");
        appendStep(b, "Mitigation", likelyResolution.mitigation());
        appendStep(b, "Permanent fix", likelyResolution.permanentFix());
        return b.toString();
    }

    private static void appendStep(StringBuilder b, String label, ResolutionStep step) {
        if (step == null) return;
        b.append("  ").append(label).append(" — ").append(step.citedArtifact())
         .append(", closed as: ").append(step.resolutionCode()).append("\n");
    }
}
