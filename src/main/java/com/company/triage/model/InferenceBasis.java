package com.company.triage.model;

/**
 * How a {@link LikelyCause} was arrived at (J28/PGC-1) — and, via CR-6, what kind of evidence
 * it is therefore obliged to cite.
 *
 * <p>This is the load-bearing field of the whole concept. {@code evidenceRefs} prove
 * <em>traceability</em> — that a cited id exists — but not <em>support</em>: a cause claiming
 * to come from a prior resolution while citing only a log line passes every check J4 had
 * before. {@code basis} makes the *kind* of citation checkable, and because
 * {@link Evidence#source()} is already a closed vocabulary the check is a small switch.
 *
 * <p><b>There is deliberately no {@code NONE} constant.</b> Abstention is a {@code null}
 * {@code likelyCause}, full stop. Two encodings of "we don't know" diverge the moment one
 * code path sets one and not the other.
 */
public enum InferenceBasis {

    /**
     * A human closed a similar incident and wrote what happened. The only basis enabled
     * today (J28/PGC-5) — and the only one that is genuinely <em>causal</em> rather than
     * correlational: someone diagnosed it, fixed it, watched it stop, and wrote it down.
     * Must cite {@code servicenow-incident} evidence.
     */
    PRIOR_RESOLUTION,

    /**
     * A runbook or known-error article describes this symptom. Must cite {@code confluence}
     * evidence.
     *
     * <p><b>Gated off until J25 lands.</b> The Confluence query is currently a keyword bag
     * that returned five unrelated pages on the live instance, so quote fidelity (CR-8)
     * would only prove that an irrelevant page was quoted accurately.
     */
    KNOWN_ERROR_DOC,

    /**
     * A code path plausibly emits the observed error. Must cite {@code gitlab} evidence.
     *
     * <p><b>Gated off until J13 lands.</b> Code-evidence ids currently collide and a
     * citation can name a different system than the one that emitted the line.
     */
    CODE_PATH;

    /**
     * The {@link Evidence#source()} value a cause on this basis must cite (CR-6). Kept here
     * rather than in the validator so the mapping lives next to the constant it describes.
     */
    public String requiredEvidenceSource() {
        return switch (this) {
            case PRIOR_RESOLUTION -> "servicenow-incident";
            case KNOWN_ERROR_DOC -> "confluence";
            case CODE_PATH -> "gitlab";
        };
    }
}
