package com.company.triage.model;

import java.util.List;

/**
 * Why this incident may be happening (J28/PGC-1) — <b>cited, never asserted</b>.
 *
 * <p>TriageMate has no causal substrate. It owns no topology graph and no deploy-correlation
 * model, and most of what it gathers is <em>selection-driven</em>: the Confluence query is
 * built from the ticket's own keywords, so a page mentioning the symptom is the search
 * working, not evidence about the world. Products that say "the root cause is X" own a
 * substrate; everything precedent-based hedges.
 *
 * <p>The one genuinely causal artifact within reach is a <b>human's closed verdict on a past
 * incident</b> — someone diagnosed it, fixed it, watched it stop, and wrote it in
 * {@code close_notes}. So this record <b>quotes that verdict verbatim</b> and attributes it.
 * Paraphrasing would launder someone else's guess into TriageMate's assertion; quoting keeps
 * the epistemic ownership where it belongs and hands the reader the one thing that lets them
 * judge it — a ticket number they can open.
 *
 * <p><b>A null {@code LikelyCause} is the abstention, and it is the expected output much of
 * the time.</b> Real resolution notes are frequently "Issue resolved" or "Done". That is not
 * a failure: the best-performing published incident-RCA agent won on precision by answering
 * "insufficient information" to two thirds of the cases it got wrong. A schema that made
 * abstention invalid would force fabrication — the mistake this codebase already avoided once
 * when it exempted {@code suggestedAssignment} from the citation rule "because forcing a
 * citation there would push the code toward inventing one".
 *
 * @param quotedFinding   the cited incident's resolution note, <b>verbatim and whole</b>.
 *                        Never paraphrased, never sentence-split — {@code close_notes}
 *                        routinely carries cause and fix in one sentence, and splitting it
 *                        would be inference dressed as extraction.
 * @param citedArtifacts  incident numbers the reader can open, e.g. {@code ["INC0011902"]}
 * @param basis           how this was arrived at; constrains what it must cite (CR-6)
 * @param evidenceRefs    ids of {@link Evidence} in this report backing the claim
 * @param supportingCount how many considered incidents actually carried a resolution note
 * @param consideredCount how many similar incidents were examined. Rendered as the
 *                        denominator — "2 of 2" — which is the <b>entire</b> uncertainty
 *                        signal. There is deliberately no confidence field and no
 *                        percentage: a displayed high confidence measurably degrades human
 *                        judgement, ServiceNow's own similar-incident UI shows no
 *                        percentage, and this project already took "no percentage, no
 *                        progress bar" as direct product feedback. A denominator is better
 *                        than a label because the reader can falsify it by opening the
 *                        tickets.
 */
public record LikelyCause(
        String quotedFinding,
        List<String> citedArtifacts,
        InferenceBasis basis,
        List<String> evidenceRefs,
        int supportingCount,
        int consideredCount
) {}
