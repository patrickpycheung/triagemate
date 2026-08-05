package com.company.triage.model;

/**
 * How similar incidents were resolved (J28/PGC-2) — <b>history, not instruction</b>.
 *
 * <p>The two halves are separated because they are different kinds of act. A
 * <b>mitigation</b> restores service now and is usually reversible; a <b>permanent fix</b>
 * stops recurrence and usually is not. They carry different evidence requirements, different
 * actors and very different costs when wrong. Merged into one "resolution" line they force
 * the reader to do that risk triage themselves at 3am — and invite applying a code-fix line
 * with the reflex appropriate to a restart.
 *
 * <p>Either half may be null; both null means the resolution section is omitted entirely.
 *
 * <p>The rendered label is deliberately past-tense — <i>"How similar incidents
 * <b>were</b> resolved"</i>. A historical claim <b>survives being wrong</b>: if the prior fix
 * does not apply here, the sentence is still true. A prescriptive label ("how to resolve
 * this") does not survive, and it is also the sentence a reader is most likely to execute
 * without checking. The advisory posture is kept structurally rather than by disclaimer.
 *
 * @param mitigation   restore service now — nullable
 * @param permanentFix stop it recurring — nullable
 */
public record LikelyResolution(
        ResolutionStep mitigation,
        ResolutionStep permanentFix
) {}
