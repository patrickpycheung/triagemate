package com.company.triage.model;

import java.util.List;

/**
 * One step of a {@link LikelyResolution} (J28/PGC-1a) — built <b>entirely from closed
 * vocabularies</b>.
 *
 * <p>Both content fields are constrained: {@link #verb} is our own enum, and
 * {@link #resolutionCode} is ServiceNow's {@code close_code}, itself a controlled list
 * ("Resolved - Code Fix", "Resolved - Known Error"). <b>No free text reaches this record.</b>
 *
 * <p>That is deliberate and it is the concept's main safety property. Free text gathered from
 * a ticket, a wiki page or a log line is attacker- and mistake-influenceable, and a stale
 * runbook is the same failure with no attacker at all. Such text may reach the
 * <em>cause</em> section as an attributed quotation — where the reader can see whose words
 * they are and open the source — but it can never reach the section that tells them what to
 * do.
 *
 * @param verb           what the reader is invited to do; observation-only by construction
 * @param resolutionCode the cited incident's {@code close_code}, verbatim
 * @param citedArtifact  the incident number, e.g. {@code "INC0011902"}. <b>Singular on
 *                       purpose</b> — one step cites one incident's outcome. Only
 *                       {@link LikelyCause} aggregates, which is why that one holds a list.
 * @param evidenceRefs   ids of {@link Evidence} in the same report backing this step
 */
public record ResolutionStep(
        ResolutionVerb verb,
        String resolutionCode,
        String citedArtifact,
        List<String> evidenceRefs
) {}
