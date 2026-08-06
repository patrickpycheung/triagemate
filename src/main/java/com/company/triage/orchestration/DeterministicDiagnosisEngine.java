package com.company.triage.orchestration;

import com.company.triage.config.TriageProperties;
import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.model.*;
import com.company.triage.orchestration.trace.StepCatalog;
import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceSink;
import com.company.triage.orchestration.trace.TraceStep;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, deterministic implementation of the bounded phase flow (J1/J2). It runs
 * the SAME ordered steps the ADK agent runs — fetch → clarify → similar+ownership →
 * knowledge → bounded logs → targeted code → assemble report — but with scripted
 * reasoning instead of an LLM, so the demo works with zero network. The live agentic
 * version lives in src/main/adk (profile {@code adk}).
 *
 * <p>Default engine (active unless {@code triage.engine=adk}). Registered unconditionally
 * (not gated on {@code triage.engine}) so it is always available as {@link
 * DiagnosisOrchestrator}'s automatic fallback (FND-7) when the ADK engine is primary but
 * fails to converge — no LLM/network dependency means this path can't fail the same way.
 */
@Component
public class DeterministicDiagnosisEngine implements DiagnosisEngine {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeterministicDiagnosisEngine.class);

    private static final Pattern ERROR_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");

    /**
     * J29/LLF-3: does this line carry a THROWN exception's fully-qualified name?
     *
     * <p>The card originally proposed {@code \w+(\.\w+)+Exception}. Against the captured real
     * delivery-hazards line that matches {@code a.c.a.h.c.e.CustomRestException} — Spring
     * Boot's ABBREVIATED logger name, truncated mid-word — a class that exists in no source
     * file, so a GitLab search for it returns nothing. Requiring three or more dotted segments
     * and a word boundary after {@code Exception} is what makes this read the thrown
     * {@code org.springframework.dao.DataIntegrityViolationException} instead.
     */
    private static final Pattern EXCEPTION_FQN =
            Pattern.compile("\\b\\w+(?:\\.\\w+){2,}\\.\\w*Exception\\b");

    /**
     * J29/LLF-3: the class's own name, which is what a human searches GitLab for.
     *
     * <p>Safe to run over the whole line rather than only over the {@link #EXCEPTION_FQN}
     * match: the trailing {@code \b} skips {@code CustomRestExceptionHandler}, since there is
     * no word boundary after {@code Exception} there.
     */
    private static final Pattern EXCEPTION_CLASS =
            Pattern.compile("\\b[A-Z][a-zA-Z0-9]*(?:Exception|Error|Violation|Failure)\\b");

    /**
     * J13/ECI-4: most code citations one reader can use for a single error token.
     *
     * <p>Bounds two things at once, because {@code codeHits} is also what
     * {@link #gatherContacts} loops over to call {@code recentCommitters}: the number of
     * {@code e-code} Evidence rows in the report, AND the number of GitLab API calls the run
     * makes. A common token could previously yield ~20 of the former and ~40 of the latter.
     *
     * <p>Three, not ten: the citation exists to let a human open the file that emits the log
     * line. Past a handful, extra rows cost attention on stage and add nothing — and the
     * trace discloses the truncation rather than quietly showing 3 of 12.
     */
    private static final int MAX_CODE_EVIDENCE = 3;

    /**
     * The one and only Sumo search term (operator instruction, 2026-08-05).
     *
     * <p>The scope already targets the affected application — {@code _sourceCategory} is
     * composed from its slug and environment — so the term's job is only to select the
     * interesting lines within it. {@code ERROR} does exactly that and nothing more.
     *
     * <p>What this replaced: a term derived from the ticket (a transaction identifier, else
     * the top extracted keywords, else the literal {@code "error"}). Those derived words were
     * narrowing real searches to zero rows — the same class of failure J25 found in the
     * Confluence query, where our own cleverness about which words to send was the problem
     * rather than the solution. Scope by config, filter by severity, and let the human read
     * what came back.
     */
    private static final String SUMO_QUERY_TERM = "ERROR";

    /**
     * J25/KQR-2: the share of wanted terms a Confluence page must match to be cited.
     *
     * <p>0.34 means "at least one solid title hit, or two body mentions, out of three wanted
     * terms" — enough to exclude the Teradata data-model PDF that was cited as evidence for a
     * delivery-hazards incident on the live instance, without demanding the runbook repeat the
     * whole subject line.
     *
     * <p><b>A constant, not a config key</b> — a deliberate deviation from the card, which
     * specifies "a configured floor". {@code TriageProperties} has no Confluence section, so
     * adding one means a new component on the top-level record and an edit to every fixture
     * that constructs it. The card's actual intent — that the threshold be named, explained,
     * and tunable in one place rather than buried in an expression — is met here; promoting it
     * to config is a five-minute change if anyone ever needs to tune it without a rebuild.
     */
    private static final double KNOWLEDGE_RELEVANCE_FLOOR = 0.34;

    private final ServiceNowGateway serviceNow;
    private final ConfluenceGateway confluence;
    private final SumoGateway sumo;
    private final GitLabGateway gitLab;
    private final TriageProperties.Sumo sumoProps;
    private final List<String> sumoAllowedEnvironments;
    private final String sumoDefaultEnvironment;
    private final List<String> gitLabProjectAllowlist;

    public DeterministicDiagnosisEngine(ServiceNowGateway serviceNow, ConfluenceGateway confluence,
                                        SumoGateway sumo, GitLabGateway gitLab,
                                        TriageProperties props) {
        this.serviceNow = serviceNow;
        this.confluence = confluence;
        this.sumo = sumo;
        this.gitLab = gitLab;
        // FND-40: the Sumo settings come from config, not a hardcoded copy — the two paths
        // (this engine and TriageMateTools) previously held independently-maintained lists
        // that agreed only by coincidence. Now both compose their _sourceCategory from the
        // same configured pattern, so there is one source of truth.
        this.sumoProps = props.sumo();
        this.sumoAllowedEnvironments = props.sumo().allowedEnvironments();
        // "prod" when it's a configured environment, else the first one, so a deployment
        // that renames its environments still gets a valid category rather than a blank.
        // J19/ICF-1: one derivation, owned by the config record both engines hold.
        this.sumoDefaultEnvironment = props.sumo().defaultEnvironment();
        // FND-62: the GitLab project was a hardcoded literal here, so this engine ignored
        // triage.gitlab.allowed-projects while the ADK path enforced it — the same
        // two-sources-of-truth split FND-40 fixed for Sumo scopes and missed here.
        this.gitLabProjectAllowlist = props.gitlab().allowedProjects();
    }

    @Override
    public DiagnosisResult diagnose(String incidentNumber, TraceSink sink) {
        // TASK-008 (J11 LT1/LT2): every existing trace.add(...) line below gets a matching
        // TraceStep emitted to `sink`, via StepCatalog's dotted-key lookup, ALONGSIDE the
        // string line — never instead of it. `callId` is the synthetic `det-<seq>` scheme
        // (LT1 §67); `seq` is a simple running counter over the steps actually emitted (the
        // conditional gitlab.searchCode step, when skipped, leaves no gap — seq tracks
        // emission order, not a fixed line number). This engine runs synchronously with no
        // real async gap (2-19ms total per F-2), so ACTIVE→DONE is emitted back-to-back for
        // each step; durationMs is still measured (never a hardcoded 0) so the row shape stays
        // honest for LT3's replay renderer.
        AtomicInteger stepSeq = new AtomicInteger(0);
        List<String> trace = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        log.info("deterministic triage of {} — starting bounded investigation", incidentNumber);

        // ---- Step 1: fetch incident (ServiceNow) ------------------------------
        IncidentContext inc = serviceNow.getIncident(incidentNumber);
        String traceGetIncident = "servicenow.getIncident(%s) → CI=%s, env=%s".formatted(
                incidentNumber, inc.configurationItem(), inc.environment());
        trace.add(traceGetIncident);
        emitStep(sink, stepSeq, "servicenow.getIncident", traceGetIncident, StepState.DONE);

        // FND-63: the ticket itself is evidence — it is a real source with real content, and
        // every conclusion below is at minimum grounded in what it says. It was never cited,
        // which left an incident whose other four sources all come back empty with ZERO
        // evidence and therefore no valid J4 report at all (the contract requires ≥1). That
        // is the no-signal case the FND-7 fallback most needs to survive: "here is what the
        // ticket says and nothing corroborated it" is a legitimate, honest triage outcome,
        // whereas failing to produce a report is not.
        evidence.add(new Evidence("e-incident", "servicenow-incident",
                joinNonBlank(" — ", inc.shortDescription(), inc.description()), incidentNumber));

        // ---- Step 2: clarify symptom (extract identifiers + keywords) ---------
        // FND-62: was a single regex for the demo fixture's exact id shape
        // (INC-ORD-\d+), with everything else falling through to the literal "error".
        // IncidentSignals derives ids, keywords and the affected app from the ticket.
        IncidentSignals signals = IncidentSignals.from(inc);
        String orderId = signals.primaryIdentifier();
        String traceUnderstand = "understand: id=%s, keywords=%s, app=%s".formatted(
                orderId, signals.keywords(), signals.app());
        trace.add(traceUnderstand);
        emitStep(sink, stepSeq, "understand:", traceUnderstand, StepState.DONE);

        // ---- Step 3: similar incidents + ownership ----------------------------
        // Declared here rather than at the knowledge step: FND-90 added two earlier users
        // (similar incidents, CMDB ownership), and a degradation list has to exist before the
        // first call that can degrade.
        List<String> gatewayFailures = new ArrayList<>();   // J14/FRI-5
        // FND-90 / J14/FRI-5: this call site was NAMED in FRI-5's mechanism and never wrapped,
        // while sumo.search, gitLab.searchCode and confluence.search all were. Precedent is
        // not decoration here: this engine is D2, the fallback the demo runbook keeps hot
        // because "the demo cannot hard-fail on stage", and the orchestrator has no net under
        // it — a throw from here became an HTTP 500 with no report at all, at exactly the
        // moment the fallback was supposed to be saving the run.
        //
        // getIncident stays unwrapped, deliberately: without the ticket there is nothing to
        // diagnose. Similar incidents are ENRICHMENT — losing them costs precedent, not the
        // diagnosis.
        List<ResolvedIncident> similarGathered;
        boolean similarFailed = false;
        try {
            similarGathered = serviceNow.findSimilarIncidents(inc);
        } catch (com.company.triage.gateway.GatewayUnavailableException e) {
            similarGathered = List.of();
            similarFailed = true;
            gatewayFailures.add(e.getMessage()
                    + " — no past incidents were compared, so \"no precedent found\" is not a "
                    + "conclusion this run is entitled to");
            log.warn("  {} unreachable for similar incidents — continuing without precedent", e.system(), e);
        }
        // Effectively-final copy: assignment below captures this in a lambda.
        final List<ResolvedIncident> similar = similarGathered;
        for (ResolvedIncident r : similar) {
            // J28: the resolution NOTE is appended so the evidence carries what the cause
            // section quotes. Without it, CR-8 (quote fidelity) could never pass — a
            // quotation must be checkable against the thing it cites — and the "Sources
            // consulted" note would show a close code while the diagnosis quoted prose the
            // reader could not see the origin of.
            evidence.add(new Evidence("e-sim-" + r.number(), "servicenow-incident",
                    "%s (%.0f%% similar) resolved by %s: %s%s".formatted(
                            r.number(), r.similarity() * 100, r.resolutionGroup(), r.resolutionCode(),
                            notBlank(r.resolutionNotes()) ? " — " + r.resolutionNotes().trim() : ""),
                    r.number()));
        }
        String traceFindSimilar = similarFailed
                ? "servicenow.findSimilarIncidents → COULD NOT SEARCH (ServiceNow unreachable) "
                  + "— this is not 'no similar incidents'"
                : "servicenow.findSimilarIncidents → %d hits".formatted(similar.size());
        trace.add(traceFindSimilar);
        emitStep(sink, stepSeq, "servicenow.findSimilarIncidents", traceFindSimilar,
                similarFailed ? StepState.FAILED : StepState.DONE);

        // FND-90 — same rule, same reason. Ownership drives the suggested assignment group,
        // so losing it degrades routing; it must not end the run.
        Optional<ServiceOwnership> ownership;
        boolean ownershipFailed = false;
        try {
            ownership = serviceNow.findOwnership(inc.configurationItem());
        } catch (com.company.triage.gateway.GatewayUnavailableException e) {
            ownership = Optional.empty();
            ownershipFailed = true;
            gatewayFailures.add(e.getMessage()
                    + " — the CMDB owner was not looked up, so no assignment group is suggested "
                    + "from ownership");
            log.warn("  {} unreachable for CMDB ownership — continuing without it", e.system(), e);
        }
        ownership.ifPresent(o -> evidence.add(new Evidence("e-cmdb", "servicenow-cmdb",
                "CMDB: %s owned by %s".formatted(o.application(), o.supportGroup()), o.source())));
        String traceFindOwnership = "servicenow.findOwnership(%s) → %s".formatted(
                inc.configurationItem(), ownership.map(ServiceOwnership::supportGroup).orElse("none"));
        trace.add(traceFindOwnership);
        emitStep(sink, stepSeq, "servicenow.findOwnership", traceFindOwnership,
                ownershipFailed ? StepState.FAILED : StepState.DONE);

        // ---- Step 4: knowledge (Confluence) -----------------------------------
        // FND-59: this used to be the fixed literal "checkout order payment reconcile 500"
        // for every incident — it only ever "worked" because it happened to match the one
        // seeded demo incident's keywords. Now built from the incident's own symptom text
        // (shortDescription) plus the affected system (configurationItem), same as the
        // orderId/scope/window derivations already used for the similar-incidents and Sumo
        // lookups below.
        // FND-62: keywords + app rather than the whole sentence + app, so the search gets
        // distinctive terms instead of English function words.
        List<String> knowledgeMisses = new ArrayList<>();
        boolean codeSearchFailed = false;                   // J30/GEB-3
        String confluenceQuery = signals.confluenceQuery();
        // J25/KQR-4 + J14/FRI-5: the safety net degrades PER CALL. An unreachable Confluence
        // costs this run its runbook evidence and says so; it does not cost the run.
        List<KnowledgeDoc> returned;
        // FND-89: the trace row's STATE must match what happened, not just its text.
        boolean confluenceFailed = false;
        try {
            returned = confluence.search(confluenceQuery);
        } catch (com.company.triage.gateway.GatewayUnavailableException e) {
            returned = List.of();
            confluenceFailed = true;
            knowledgeMisses.add(e.getMessage() + " — no runbook evidence was gathered "
                    + "(this is a connector failure, not an absence of runbooks)");
            log.warn("  {} unreachable — continuing without knowledge evidence", e.system(), e);
        }

        // J25/KQR-2: a returned page must clear a relevance floor before it becomes EVIDENCE.
        // KQR-1 fixed the query (siteSearch, not text ~); this bounds what we do with the
        // answer. On the live instance a five-result response included a Teradata data-model
        // PDF, and all five were cited — an evidence list padded with irrelevant entries
        // damages the credibility of the entries that are real. Precision over recall, the
        // same trade FND-67 already made for contact extraction.
        List<KnowledgeDoc> docs = returned.stream()
                .filter(d -> knowledgeRelevance(d, signals) >= KNOWLEDGE_RELEVANCE_FLOOR)
                .toList();
        for (KnowledgeDoc d : docs) {
            evidence.add(new Evidence("e-kb-" + d.id(), "confluence",
                    "%s (%s): %s".formatted(d.title(), d.id(), d.snippet()), d.url()));
        }
        int dropped = returned.size() - docs.size();
        if (!returned.isEmpty() && docs.isEmpty()) {
            // Say it, rather than letting "no runbook" and "runbooks that matched nothing"
            // look identical — J25's own distinction between a failed search and an empty one.
            knowledgeMisses.add("%d Confluence page(s) were returned but none matched the symptom terms"
                    .formatted(returned.size()));
        }
        String traceConfluenceSearch = returned.isEmpty()
                ? "confluence.search(query=\"%s\") → 0 page(s)".formatted(confluenceQuery)
                : "confluence.search(query=\"%s\") → %d page(s), %d cleared the relevance floor"
                        .formatted(confluenceQuery, returned.size(), docs.size());
        if (dropped > 0) {
            log.info("  J25/KQR-2 · {} of {} Confluence page(s) below the relevance floor — not cited",
                    dropped, returned.size());
        }
        trace.add(traceConfluenceSearch);
        emitStep(sink, stepSeq, "confluence.search", traceConfluenceSearch,
                confluenceFailed ? StepState.FAILED : StepState.DONE);

        // ---- Step 5: bounded logs (Sumo) --------------------------------------
        // The _sourceCategory is COMPOSED, not chosen: project slug (from the affected app)
        // + environment (from the incident's own environment field) expanded through the
        // configured pattern. This replaced a literal scope allowlist that we swept in
        // relevance order — that allowlist held demo-fixture values that exist in no real
        // Sumo, and scopes were never an access boundary anyway (any credential that can
        // search sees every environment). One derived category also means one query instead
        // of a sweep, since there is no longer a list of candidates to guess between.
        String logQuery = signals.logQuery();
        String projectSlug = IncidentSignals.projectSlug(signals.app());
        IncidentSignals.EnvironmentChoice env = IncidentSignals.resolveEnvironment(
                inc.environment(), sumoAllowedEnvironments, sumoDefaultEnvironment);
        String environment = env.code();
        String scope = sumoProps.sourceCategoryFor(projectSlug, environment);
        // J24/SFF-3: a slug derived from a SENTENCE FRAGMENT can never match a real
        // _sourceCategory, so issuing it burns a call and then reports "0 lines" as though the
        // system had been searched and found quiet — a false negative presented as evidence.
        // That is exactly what happened on the live instance (docs/Siyad_Findings.md §3): the
        // CI parse dropped "Delivery Hazards", `app` fell back to the subject line, and the
        // query went to .../hazards-being-recorded-on/... which exists nowhere.
        //
        // Skip only when BOTH are true — the app was inferred rather than read from the CMDB,
        // AND the derived slug matches no configured GitLab project. A CMDB-sourced name is
        // trusted even if it matches no allowlist entry (the allowlist is a GitLab concept and
        // may legitimately not list every system that logs to Sumo).
        boolean slugIsAGuess = signals.appWasInferred()
                && IncidentSignals.rankAllowlist(signals.app(), gitLabProjectAllowlist).stream()
                        .noneMatch(p -> IncidentSignals.projectSlug(p).equals(projectSlug));
        // J14: `openedAt` is the ANCHOR for the ±10m window, and against a real instance it
        // can be null — ServiceNow returns `opened_at` in the requesting user's display
        // format under `sysparm_display_value=true`, and any format `parseTime` doesn't
        // recognise degrades to null. This line used to dereference it unguarded, so a
        // ticket whose date didn't parse threw an NPE **inside the deterministic engine** —
        // the FND-7 fallback, the one path whose entire job is to survive when the other one
        // fails. The orchestrator would degrade to it and then get a 500 out of it, which is
        // the single worst failure shape this app has.
        //
        // A window is skipped, not faked: anchoring on `now()` for a ticket opened days ago
        // searches the wrong ten minutes and reports "0 lines" as though the system had been
        // searched and found quiet — a false negative presented as evidence. Say plainly
        // that the search did not happen and why, exactly as the gitlab.searchCode branch
        // below already does.
        // Operator instruction, 2026-08-05 — three changes to what we send Sumo:
        //
        //  1. NO derived search terms. `signals.logQuery()` built a term from the ticket
        //     (identifier, else top keywords, else "error"), and those custom words were
        //     narrowing real searches to nothing. The term is now the single literal
        //     SUMO_QUERY_TERM below: scope the search to the application, then look for its
        //     errors. `logQuery()` is still used by nothing else on this path — see its
        //     javadoc for why it is retained for the ADK path's benefit.
        //  2. NO `_index` clause — disabled in application.yml (the mechanism survives).
        //  3. The window is the LAST DAY OF DATA, not ±10m around opened_at. A ticket is
        //     routinely raised hours or days after the event, and on a real instance the
        //     data near opened_at may no longer be retained; searching recent data is what
        //     actually returns log lines.
        //
        // Change (3) has a consequence worth stating: the window no longer depends on
        // opened_at AT ALL, so J14/FRI-2's "skip the search when there is no anchor" guard is
        // now unreachable for this reason and has been removed. The NPE it existed to prevent
        // is likewise gone — nothing dereferences openedAt here any more. J24/SFF-3's skip
        // (the derived scope matches no configured project) is a different rule and stays.
        OffsetDateTime searchTo = OffsetDateTime.now();
        OffsetDateTime searchFrom = searchTo.minusDays(1);
        LogSearchRequest sumoRequest = slugIsAGuess ? null
                : new LogSearchRequest(scope, sumoProps.index(), SUMO_QUERY_TERM,
                        searchFrom, searchTo, sumoProps.maxResults());
        // J14/FRI-5: three different "no logs" outcomes, kept distinct — no anchor to search
        // from (handled above via sumoRequest == null), searched and found nothing, and could
        // not search at all. Only the middle one is a finding.
        List<LogEvidence> logs;
        boolean sumoFailed = false;   // FND-89 — the row's STATE, not only its text
        if (sumoRequest == null) {
            logs = List.of();
        } else {
            try {
                logs = sumo.search(sumoRequest);
            } catch (com.company.triage.gateway.GatewayUnavailableException e) {
                logs = List.of();
                sumoFailed = true;   // FND-89
                gatewayFailures.add(e.getMessage()
                        + " — the log window was not searched, so \"no errors found\" is not a "
                        + "conclusion this run is entitled to");
                log.warn("  {} unreachable — continuing without log evidence", e.system(), e);
            }
        }
        LogEvidence errorLine = logs.stream()
                .filter(l -> "ERROR".equals(l.level())).findFirst().orElse(null);
        String errorToken = errorLine == null ? null : searchTermFor(errorLine.message());

        // J13/ECI-5 — each typed field is populated ONLY from a signal that determined THAT
        // field. This used to be `new Identifiers(orderId, null, orderId)` at understand-time:
        // the same string in two differently-named fields whatever it was, so a UUID (a
        // correlation id) was also reported as an orderId, and errorCode was hardcoded null.
        // The record's own javadoc says any field may be null, so null is the honest value
        // rather than a gap — a wrong TYPE is not.
        //
        // Built here rather than at :187 because errorCode carries the Sumo-derived token,
        // which does not exist until the log search above has run. Nothing between the two
        // points reads `ids`.
        Identifiers ids = new Identifiers(
                signals.primaryIdentifierKind() == IncidentSignals.IdentifierKind.CORRELATION
                        ? signals.primaryIdentifier() : null,
                errorToken,
                signals.primaryIdentifierKind() == IncidentSignals.IdentifierKind.ORDER
                        ? signals.primaryIdentifier() : null);
        if (errorLine != null) {
            evidence.add(new Evidence("e-log", "sumo",
                    "%s log [%s]: %s".formatted(errorLine.logger(), errorLine.level(), errorLine.message()),
                    scope));
        }
        // Show the query EXACTLY as sent, index clause included. The trace previously
        // printed scope and search term separately, which made it impossible to tell from
        // the UI whether the _index clause was being applied at all — and without it a
        // real Sumo search returns zero rows every time, so it is the one part of the
        // query most worth being able to see.
        String traceSumoSearch;
        if (sumoRequest != null) {
            traceSumoSearch = "sumo.search(%s) [window=last 24h, max=%d] → %d line(s); errorToken=%s"
                    .formatted(sumoRequest.toSumoQuery(), sumoProps.maxResults(), logs.size(), errorToken);
        } else {
            // J24/SFF-3: say WHAT was rejected and why, so the trace shows a decision rather
            // than an absence — and name the guessed slug, because that is the thing an
            // operator needs to see to recognise the CMDB is not naming the system.
            traceSumoSearch = ("sumo.search → skipped (the affected system was inferred from the "
                    + "ticket's subject line, not its CMDB entry, and the derived scope '%s' "
                    + "matches no configured project — searching it would report a false "
                    + "'no logs found')").formatted(projectSlug);
        }
        trace.add(traceSumoSearch);
        emitStep(sink, stepSeq, "sumo.search", traceSumoSearch,
                sumoFailed ? StepState.FAILED : StepState.DONE);

        // ---- Step 6: targeted code search + log↔code citation (RC3) -----------
        // FND-63: was the literal "Order submission (checkout)". The ticket's own
        // category/subcategory is the structured answer to "what function is affected";
        // fall back to the symptom line when they're unset.
        String function = joinNonBlank(" / ", inc.category(), inc.subcategory());
        if (function.isBlank()) function = text(inc.shortDescription());
        List<CodeSearchResult> codeHits = List.of();
        if (errorToken != null) {
            // FND-62: was the literal "order-payments/payment-service" — hardcoded, and
            // bypassing triage.gitlab.allowed-projects entirely, so config and behaviour
            // could silently disagree (FND-40's exact class, fixed there for Sumo scopes and
            // missed here). Same rank-then-sweep as the log search above.
            List<String> projectsToTry =
                    IncidentSignals.rankAllowlist(signals.app(), gitLabProjectAllowlist);
            SweepOutcome sweep = new SweepOutcome();
            // J31/ASO-1 + ASO-2. Two rules that were already converged but not implemented:
            //
            //   ASO-1 (restores J30/GEB-2) — a project that cannot resolve is a SKIPPED
            //   project, not the end of the sweep. This loop used to `break` on failure, so a
            //   single unresolvable entry ranked first stopped every remaining candidate from
            //   being searched at all. GEB-2, GEB-2's own verification row, and
            //   GatewayUnavailableException's javadoc all say "continue"; only the code said
            //   otherwise.
            //
            //   ASO-2 — the outcome is a property of the WHOLE sweep, not of whichever attempt
            //   happened to go last. `codeSearchFailed` was a boolean assigned in the catch, so
            //   a real project searched successfully and empty, followed by a phantom entry's
            //   404, reported "could not search" for a search that ran and truthfully found
            //   nothing. A boolean cannot express five outcomes.
            for (String project : projectsToTry) {
                try {
                    codeHits = gitLab.searchCode(project, errorToken);
                    sweep.searched(project);
                    // J31/ASO-4: one row per ATTEMPT. The trace spine already models a call
                    // (TraceSink.before/after wrap one), and a sweep is N calls — so N rows,
                    // each naming its project. This is what makes "partial" legible rather
                    // than merely stated: the reader sees which project answered and which
                    // did not, instead of one summary row asserting a single outcome for
                    // several different things that happened.
                    emitStep(sink, stepSeq, "gitlab.searchCode",
                            "gitlab.searchCode(%s) → %d hit(s)".formatted(project, codeHits.size()),
                            StepState.DONE);
                } catch (com.company.triage.gateway.GatewayUnavailableException e) {
                    // J14/FRI-5: one unreachable connector costs its own evidence, not the run.
                    // The PROJECT is recorded alongside the error: the exception carries only
                    // the system ("GitLab is unreachable: …"), so two failed entries were
                    // indistinguishable and a partial result could not name what it missed.
                    sweep.failed(project, e.getMessage());
                    codeHits = List.of();
                    // J31/ASO-4 + FND-89: this attempt FAILED and says so. Previously the only
                    // GitLab row was the aggregate, always DONE, so an unreachable project was
                    // indistinguishable in the live trace from one that answered.
                    emitStep(sink, stepSeq, "gitlab.searchCode",
                            "gitlab.searchCode(%s) → unreachable".formatted(project),
                            StepState.FAILED);
                    log.warn("  {} unreachable for project {} — continuing to the next candidate",
                            e.system(), project, e);
                    continue;
                }
                if (!codeHits.isEmpty()) break;
            }
            // Failures are reported once the sweep is complete, so the message can say whether
            // anything else succeeded. Recording it inside the loop would state a conclusion
            // before the evidence for it existed.
            gatewayFailures.addAll(sweep.missingInformation());
            codeSearchFailed = sweep.nothingWasSearched();
            // J13/ECI-4: cap the fan-out. GitLab's blob search can return many hits for a
            // common token; each became an Evidence entry AND a recentCommitters API call, so
            // an unlucky token meant ~20 evidence rows and ~40 calls in one run. The report is
            // read by a human on stage — past a handful of citations for one fact, each extra
            // row costs attention and buys nothing.
            List<CodeSearchResult> citedHits = codeHits.size() > MAX_CODE_EVIDENCE
                    ? codeHits.subList(0, MAX_CODE_EVIDENCE) : codeHits;
            // J13/ECI-1: unique ids by CONSTRUCTION. Every hit used to be added as "e-code",
            // so a multi-hit run produced several Evidence entries sharing one id and any
            // evidenceRef naming it was ambiguous — the reader could not tell which file the
            // citation meant. Suffix from the second onward, so the common single-hit case
            // keeps the stable "e-code" id that existing refs and tests use.
            for (int i = 0; i < citedHits.size(); i++) {
                CodeSearchResult h = citedHits.get(i);
                evidence.add(new Evidence(i == 0 ? "e-code" : "e-code-" + (i + 1), "gitlab",
                        "log line '%s' is emitted at %s:%d".formatted(errorToken, h.filePath(), h.line()),
                        "%s/%s#L%d".formatted(h.project(), h.filePath(), h.line())));
            }
            if (codeHits.size() > citedHits.size()) {
                // Never truncate silently — a report that shows 3 of 12 matches without saying
                // so reads as "there are 3", which is a different claim.
                trace.add("gitlab.searchCode → %d hit(s), citing the first %d (cap: %d)"
                        .formatted(codeHits.size(), citedHits.size(), MAX_CODE_EVIDENCE));
            }
            codeHits = citedHits;
            // J30/GEB-3: once the sweep SURVIVES a miss (J14/FRI-5), "0 hit(s)" became
            // ambiguous — it reads the same for "searched and found nothing" and "never
            // successfully searched anything", and those lead a triager to opposite
            // conclusions. One means the code is probably not the culprit; the other means
            // nothing has been learned at all.
            //
            // This is the same honesty rule as J25/KQR-4 (failed vs empty Confluence search)
            // and J29/LLF-2 (an unreadable log level). Third instance of the pattern; the
            // card notes it is worth stating once as a rule if a fourth appears.
            // J31/ASO-2: one line, derived from what the sweep actually did. Previously an
            // if/else over a boolean, which could only ever say "0 hit(s)" or "COULD NOT
            // SEARCH" — the three states in between had no way to be expressed.
            //
            // The parens around the concatenation are load-bearing: `"…%s…" + "…".formatted(a)`
            // binds `formatted` to the SECOND literal only, so the first literal's placeholders
            // were printed raw and every degraded run traced `term='%s', projects=%s`. Found in
            // J31's CDS round; pinned by `theCouldNotSearchLineIsActuallyFormatted`.
            String traceGitLabSearch = ("gitlab.searchCode(term='%s', projects=%s) → %s")
                    .formatted(errorToken, projectsToTry, sweep.describe(codeHits.size()));
            trace.add(traceGitLabSearch);
            // J31/ASO-4 + FND-89: the row may only claim DONE if something was actually
            // searched. A step whose text says the search failed and whose state says it
            // succeeded is exactly what J14/FRI-5 forbids.
            emitStep(sink, stepSeq, "gitlab.searchCode", traceGitLabSearch,
                    sweep.nothingWasSearched() ? StepState.FAILED : StepState.DONE);
        } else {
            // The code search is only meaningful with an error token to search FOR — the
            // whole point is the log↔code citation, and there is no log line to cite.
            // Previously this branch emitted nothing at all, so GitLab silently vanished
            // from the trace for any incident whose logs carried no error token, making a
            // four-system triage look like a three-system one. Emit the row and say
            // plainly that it was skipped and why: the honesty contract cuts both ways —
            // don't claim a call that didn't happen, but don't hide the decision either.
            String traceGitLabSkipped =
                    "gitlab.searchCode → skipped (no error token in the log lines to search code for)";
            trace.add(traceGitLabSkipped);
            emitStep(sink, stepSeq, "gitlab.searchCode", traceGitLabSkipped, StepState.DONE);
        }

        // ---- Step 7: who to talk to (J9) --------------------------------------
        // Derived from the SAME evidence already gathered: authors/editors of the
        // runbooks the triage cited, plus recent committers to the implicated file.
        // Known system/team names for THIS incident — the highest-value denylist for name
        // extraction, since "Order Portal"/"Payment Service" have person-name shape.
        java.util.Set<String> knownSystemNames = new java.util.LinkedHashSet<>();
        if (inc.configurationItem() != null) knownSystemNames.add(inc.configurationItem());
        if (inc.currentAssignment() != null) knownSystemNames.add(inc.currentAssignment());
        ownership.ifPresent(o -> { knownSystemNames.add(o.application()); knownSystemNames.add(o.supportGroup()); });
        // J14/FRI-3: only a real emitter names a system. A blank logger used to arrive here
        // as prettifySystem("") and seed J9's person filter with a non-name.
        logs.forEach(l -> { if (notBlank(l.logger())) knownSystemNames.add(prettifySystem(l.logger())); });
        // FND-67: the TITLE names the thing that is broken, so its Title Case phrases are
        // system names, not people. The first real ServiceNow run suggested "Delivery
        // Hazards" as someone to talk to — straight out of its own subject line. This is the
        // general form of that fix; a denylist of domain words would only ever have fixed
        // the one ticket in front of us. A person named ONLY in the subject line is
        // suppressed as a consequence — an accepted precision-over-recall trade, and the
        // caller is captured structurally below regardless.
        if (inc.shortDescription() != null) knownSystemNames.add(inc.shortDescription());
        List<Contact> contacts = gatherContacts(inc, docs, codeHits, knownSystemNames,
                sink, stepSeq, trace);
        String traceContacts = "contacts: %d suggested (from %d doc(s) + %d code file(s), merged across sources)"
                .formatted(contacts.size(), docs.size(), codeHits.size());
        trace.add(traceContacts);
        emitStep(sink, stepSeq, "contacts:", traceContacts, StepState.DONE);

        // ---- Step 8: assemble the diagnosis report (J4) -----------------------
        // FND-63: candidates and their evidenceRefs were hardcoded, and two of the refs
        // ("e-kb-KB001234", "e-sim-INC0011902") were literal ids from the seeded demo
        // fixture. For any other incident those Evidence entries don't exist, so the J4
        // validator's dangling-evidenceRef rule threw — meaning THIS ENGINE COULD NOT
        // ACTUALLY SERVE AS THE FND-7 FALLBACK for any incident but the demo one: the
        // orchestrator would degrade to it and then get a 500 out of it. Now derived from
        // the evidence actually gathered above, so refs are dangling-free by construction.
        java.util.Set<String> gathered = evidence.stream()
                .map(Evidence::id).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        // Candidate systems come from the two signals that actually name a system: the
        // loggers that emitted the log lines, and the CMDB's owning application. A system
        // with a resolved log↔code citation ranks above one seen only in logs, which ranks
        // above one known only from the CMDB path.
        Map<String, CandidateSystem> byName = new LinkedHashMap<>();
        String codeBackedSystem = codeHits.isEmpty() ? null : prettifySystem(codeHits.get(0).project());
        // The ONE system the e-log Evidence actually describes: the emitter of the first ERROR
        // line. Everything below turns on the difference between "a system we have evidence
        // for" and "a system we merely saw a log line from".
        String loggedErrorSystem = errorLine == null ? null : prettifySystem(errorLine.logger());
        int unsupportedSystems = 0;
        for (LogEvidence l : logs) {
            String name = prettifySystem(l.logger());
            // J13/ECI-2: only the error line's own system may cite e-log. Previously EVERY
            // logger-derived candidate cited it, so a report could name "Order Portal" as a
            // suspect and point at a Payment Service log line as the reason — a citation that
            // does not support the claim it is attached to, which is worse than no citation
            // because it looks rigorous.
            boolean isTheLoggedErrorSystem = name.equals(loggedErrorSystem);
            if (!isTheLoggedErrorSystem) {
                // Seen in the window, but nothing in this report evidences it. Dropping is the
                // precision-over-recall trade FND-67 already made for contact extraction: an
                // advisory report's value is that every row is backed, so an unbacked suspect
                // costs more than it adds. Disclosed below rather than silently discarded.
                unsupportedSystems++;
                continue;
            }
            // J13/ECI-3: the 0.86 tier means "the log line is tied to the code that emits it".
            // It was granted whenever ANY code hit existed, without checking the hit belonged
            // to THIS system — so a match in an unrelated allowlisted project inflated an
            // unrelated candidate to the top of the shortlist. Require agreement.
            boolean hasCode = codeBackedSystem != null && codeBackedSystem.equals(name);
            double confidence = hasCode ? 0.86 : ("ERROR".equals(l.level()) ? 0.70 : 0.45);
            List<String> refs = refsThatExist(gathered, hasCode ? List.of("e-log", "e-code") : List.of("e-log"));
            byName.merge(name, new CandidateSystem(name, confidence, refs),
                    (a, b) -> a.confidence() >= b.confidence() ? a : b);
        }
        ownership.ifPresent(o -> {
            if (notBlank(o.application())) {
                byName.putIfAbsent(o.application(), new CandidateSystem(o.application(), 0.55,
                        refsThatExist(gathered, List.of("e-cmdb"))));
            }
        });
        if (byName.isEmpty()) {
            // Nothing observed — name the best thing we know was looked at, at a confidence
            // that admits nothing backs it.
            //
            // FND-67: this used to test `configurationItem != null` and then use it directly.
            // The first real ServiceNow ticket had cmdb_ci = "" (empty, not null), so the
            // report shipped a candidate system with an EMPTY NAME at 0.30 — a blank row on
            // stage. Blank-check, and fall back to the subject line's leading phrase when the
            // CMDB has nothing, so the candidate always names something a human can read.
            String fallbackName = notBlank(inc.configurationItem())
                    ? inc.configurationItem().trim()
                    : signals.app();
            if (notBlank(fallbackName)) {
                byName.put(fallbackName,
                        new CandidateSystem(fallbackName, 0.30, List.of("e-incident")));
            }
        }
        List<CandidateSystem> candidates = byName.values().stream()
                .sorted(Comparator.comparingDouble(CandidateSystem::confidence).reversed())
                .toList();

        // Routing evidence: the CMDB owner plus any similar-incident resolutions — whichever
        // of those actually exist for THIS incident.
        List<String> assignmentRefs = refsThatExist(gathered, java.util.stream.Stream.concat(
                java.util.stream.Stream.of("e-cmdb", "e-kb-" + firstDocId(docs)),
                similar.stream().map(r -> "e-sim-" + r.number())).toList());
        SuggestedAssignment assignment = ownership
                .map(o -> new SuggestedAssignment(o.supportGroup(), Confidence.MEDIUM, assignmentRefs))
                .orElseGet(() -> similar.stream().findFirst()
                        .map(r -> new SuggestedAssignment(r.resolutionGroup(), Confidence.LOW, assignmentRefs))
                        .orElse(new SuggestedAssignment("Unassigned — no ownership or similar-incident signal",
                                Confidence.LOW, List.of("e-incident"))));

        // FND-63: the narrative fields were hardcoded prose about checkout/payment
        // reconciliation. Correct for the demo incident, an outright fabrication for any
        // other — the FND-8 failure class (narrating something that did not happen), which
        // is the worst one in this project. Derived from the ticket and the run instead.
        String reportedSymptom = joinNonBlank(" — ", inc.shortDescription(), inc.description());

        List<String> contradicting = new ArrayList<>();
        final List<LogEvidence> observedLogs = logs;   // effectively final for the lambda below
        ownership.ifPresent(o -> {
            // J14/FRI-3: this claim's PREMISE is "the CMDB owner is absent from the set of
            // systems seen emitting". That set only exists when the lines actually name their
            // emitters. When every logger is blank — or all identical, as they were when the
            // gateway reported the query's _sourcecategory as the logger — there is no set to
            // be absent from, and asserting the conclusion anyway is FND-8 (narrating what did
            // not happen) in the most damaging spot available: the engine contradicting itself
            // on stage, about a system it never actually looked for.
            java.util.Set<String> emitters = observedLogs.stream()
                    .map(LogEvidence::logger).filter(DeterministicDiagnosisEngine::notBlank)
                    .map(DeterministicDiagnosisEngine::prettifySystem)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            boolean cmdbSystemSeenInLogs = emitters.stream()
                    .anyMatch(e -> e.equalsIgnoreCase(o.application()));
            if (!cmdbSystemSeenInLogs && !observedLogs.isEmpty() && emitters.size() >= 2) {
                contradicting.add(("%s is the CMDB owner for this CI, but no %s errors appear in the "
                        + "searched window — the failure looks downstream of it.")
                        .formatted(o.application(), o.application()));
            }
        });

        List<String> missing = new ArrayList<>();
        missing.addAll(knowledgeMisses);   // J25/KQR-2
        missing.addAll(gatewayFailures);   // J14/FRI-5 — a connector that could not answer
        if (orderId == null) missing.add("No transaction/correlation identifier in the ticket text");
        // J14: distinguish "we searched and found nothing" from "we could not search at all".
        // Claiming the former when the latter happened is the FND-8 class — narrating an
        // investigation step that did not occur.
        if (sumoRequest == null) {
            // J24/SFF-3
            missing.add("Logs were not searched: the affected system was inferred from the subject "
                    + "line rather than read from the CMDB, and the resulting scope matches no "
                    + "configured project — searching it would have reported a false 'no logs found'");
        } else if (logs.isEmpty()) {
            missing.add("No ERROR lines for this application in the last 24 hours");
        }
        // J24/SFF-2: the affected system is the pivot every other derivation turns on — the
        // Sumo scope, the candidate systems, the Confluence query. When it was inferred rather
        // than read, that is the single most important caveat on the whole report.
        if (signals.appWasInferred()) {
            missing.add(("The ticket has no configuration item, so the affected system (\"%s\") was "
                    + "inferred from its subject line — treat every system-scoped conclusion below "
                    + "as resting on that inference").formatted(signals.app()));
        }
        // J24/SFF-4: an environment that was defaulted rather than read points the log search at
        // an environment nobody chose. On the live instance this silently searched prod for a
        // ptest system.
        if (env.wasDefaulted()) {
            missing.add(("The ticket does not state a usable environment, so '%s' was assumed; "
                    + "a log search bounded by it may be looking at the wrong environment")
                    .formatted(environment));
        }
        // J13/ECI-2: never truncate the candidate list silently. Systems seen in the searched
        // window but with nothing in this report evidencing them are dropped rather than listed
        // as unbacked suspects — but the reader is told, because "3 systems were active and 1
        // is evidenced" is a different picture from "1 system was active".
        if (unsupportedSystems > 0) {
            missing.add(("%d other system(s) appeared in the searched log window but no evidence "
                    + "in this report supports them as candidates — they are omitted rather than "
                    + "listed uncited").formatted(unsupportedSystems));
        }
        // J29/LLF-2: an unreadable level is "unknown", not a severity. Such a row takes the
        // same 0.45 tier at :420 as a genuine non-ERROR line — deliberately, because promoting
        // it to the 0.70 ERROR tier would be inventing severity to raise our own confidence,
        // the dishonesty J13 exists to prevent. But then the report states a number it cannot
        // justify, so the fact that the number rests on an unread severity is disclosed. On the
        // real Sumo estate the structured level field is absent on every row, so before J29
        // this understated confidence on every live run with nothing in the trace saying why.
        long unreadableLevels = logs.stream().filter(l -> !notBlank(l.level())).count();
        if (unreadableLevels > 0) {
            missing.add(("%d log row(s) in the searched window carry no readable level, so their "
                    + "severity could not be read — they are scored as non-ERROR because the level "
                    + "is unknown, not because the lines were informational").formatted(unreadableLevels));
        }
        if (docs.isEmpty()) missing.add("No runbook or known-error page matched the symptom terms");
        if (inc.environment() == null || inc.environment().isBlank()) missing.add("Environment not set on the ticket");
        if (inc.comments().isEmpty()) missing.add("No caller follow-up comments to narrow scope/timing");

        String nextAction;
        if (!codeHits.isEmpty()) {
            CodeSearchResult h = codeHits.get(0);
            nextAction = ("Review %s:%d, which emits '%s' — the log line correlated to this incident.")
                    .formatted(h.filePath(), h.line(), errorToken);
        } else if (errorToken != null) {
            nextAction = "Trace '%s' to its emitting source; no allowlisted project matched it."
                    .formatted(errorToken);
        } else if (orderId != null) {
            nextAction = ("Widen the log search beyond the last 24 hours, or check the scope — "
                    + "no ERROR line for this application matched, though the ticket cites %s.")
                    .formatted(orderId);
        } else {
            nextAction = "Reproduce the failure and capture a correlation id; the ticket text carries none.";
        }

        // J28 — why it may be happening, and what was done about it last time. Both are
        // CITATIONS of past incidents, never assertions about this one, and both may be null.
        LikelyCause likelyCause = buildLikelyCause(similar, gathered);
        LikelyResolution likelyResolution = buildLikelyResolution(similar, gathered);

        DiagnosisReport report = new DiagnosisReport(
                incidentNumber, OffsetDateTime.now(),
                reportedSymptom,
                function, inc.environment(), ids,
                candidates, assignment, evidence, contacts,
                List.copyOf(contradicting),
                List.copyOf(missing),
                nextAction,
                Confidence.MEDIUM, true,
                likelyCause, likelyResolution);

        // FND-39: J4's validator was previously wired only into the ADK engine — an
        // asymmetric-trust gap (2 independent architecture reviews, 2026-07-30). This
        // engine's report is hand-assembled from a fixed script, not model output, so
        // this is pure defense-in-depth (a future edit to this method breaking the J4
        // contract fails loudly here instead of silently reaching the UI) rather than a
        // response to any real observed failure mode.
        com.company.triage.model.DiagnosisReportValidator.validate(report);

        String traceReportAssembled = "report assembled: %d candidates, %d evidence items, assignment=%s"
                .formatted(candidates.size(), evidence.size(), assignment.group());
        trace.add(traceReportAssembled);
        emitStep(sink, stepSeq, "report assembled:", traceReportAssembled, StepState.DONE);

        return new DiagnosisResult(report, trace);
    }

    /**
     * TASK-008: emit an ACTIVE→DONE {@link TraceStep} pair to {@code sink} for one
     * {@code trace.add(...)} call site, correlated by the synthetic {@code det-<seq>}
     * {@code callId} (LT1 §67). {@code seq} advances once per call regardless of which
     * dotted key is looked up, so a conditionally-skipped step (e.g. {@code
     * gitlab.searchCode} when no {@code errorToken} was found) leaves no gap in the
     * sequence — it tracks emission order, not a fixed line number.
     *
     * <p>This engine has no real async gap to straddle (2-19ms end to end, per F-2), so
     * {@code before}/{@code after} are called back-to-back rather than around real I/O —
     * but {@code durationMs} is still a measured (not hardcoded) elapsed time, so the row
     * shape stays honest for LT3's replay renderer even when that measured value is 0.
     */
    /**
     * FND-89 — the terminal state is a PARAMETER, not a constant.
     *
     * <p>This method used to hardcode {@link StepState#DONE}, and it is the single funnel every
     * deterministic trace row passes through, so no row on this engine could ever resolve
     * {@code FAILED}. {@code grep StepState.FAILED src/main} returned hits only in
     * {@code AdkDiagnosisEngine}. The clearest symptom: the GitLab row whose text read
     * {@code COULD NOT SEARCH (GitLab unreachable)} while its state said the step succeeded.
     *
     * <p>J14/FRI-5 names three signals a degraded call must produce, and is explicit about the
     * third: <i>"the step's TraceStep resolves <b>FAILED</b>, not DONE … using DONE would make
     * the trace assert a step succeeded when it did not — the honesty contract J11 is built
     * on."</i> Signals 1 and 2 shipped; this is signal 3, on the fallback engine FRI-5 was
     * written for — the one that runs on stage when the agent fails. J11/LT5 maps FAILED to a
     * {@code fail} visual, so until now a degraded run rendered identically to a clean one.
     *
     * <p>Deliberately <b>no DONE-defaulting overload</b>: a default is precisely how this gap
     * stayed invisible for so long. Every call site states its own outcome.
     */
    private static void emitStep(TraceSink sink, AtomicInteger stepSeq, String dottedKey,
                                 String resultText, StepState terminalState) {
        StepCatalog.Entry entry = StepCatalog.lookup(dottedKey);
        int seq = stepSeq.getAndIncrement();
        String callId = "det-" + seq;
        long startedAtEpochMs = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        sink.before(new TraceStep(seq, 0, callId, entry.platform(), dottedKey, entry.label(),
                null, StepState.ACTIVE, startedAtEpochMs, null, DiagnosisResult.Engine.DETERMINISTIC));
        long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        sink.after(new TraceStep(seq, 0, callId, entry.platform(), dottedKey, entry.label(),
                resultText, terminalState, startedAtEpochMs, durationMs, DiagnosisResult.Engine.DETERMINISTIC));
        // Every investigation step also goes to the console. This engine is the DEFAULT one,
        // and it used to log nothing at all: a full 11-step run printed only the
        // orchestrator's single "completed" line, so from the console the app looked idle
        // while it was actually working. The trace UI and the log now show the same steps.
        log.info("  step {} · {} · {}", seq + 1, entry.platform(), resultText);
    }

    /**
     * Collect suggested contacts (J9) from the evidence already gathered and merge
     * across sources. Someone who both edited a cited runbook AND recently committed
     * the implicated file is the strongest signal, so their two entries collapse into
     * one {@code confluence+gitlab} contact, ordered first.
     */
    private List<Contact> gatherContacts(IncidentContext inc, List<KnowledgeDoc> docs,
                                         List<CodeSearchResult> codeHits,
                                         java.util.Set<String> knownSystemNames,
                                         TraceSink sink, AtomicInteger stepSeq,
                                         List<String> trace) {
        List<Contact> raw = new ArrayList<>();
        // FND-64: ServiceNow was contributing NO names at all, though the ticket is where a
        // human already wrote down who else is involved — whoever left each comment, and
        // anyone they named in it. Listed first because "already engaged with this incident"
        // outranks "edited the runbook months ago".
        raw.addAll(MentionedPeople.fromIncident(inc.number(), inc.description(),
                inc.shortDescription(), inc.comments(), inc.workNotes(), knownSystemNames,
                inc.caller()));
        // Both loops below make REAL integration calls (Confluence contributors, GitLab
        // committers). They used to be invisible — folded into the one TRIAGEMATE
        // "contacts:" row emitted by the caller — which showed a single internal step
        // where two external systems were actually consulted. Each now emits its own
        // platform row, so the trace reflects every system that was genuinely touched.
        int contributorCount = 0;
        for (KnowledgeDoc d : docs) {
            List<Contact> pageContributors = confluence.contributors(d);  // author / last editor
            contributorCount += pageContributors.size();
            raw.addAll(pageContributors);
            // FND-64: ...and anyone the page NAMES. A runbook's escalation contact is often
            // more relevant than whoever last fixed a typo on it.
            raw.addAll(MentionedPeople.fromPageBody(d.title(), d.url(), d.snippet(), knownSystemNames));
        }
        String traceContributors = "confluence.contributors(%d page(s)) → %d author/editor name(s)"
                .formatted(docs.size(), contributorCount);
        trace.add(traceContributors);
        emitStep(sink, stepSeq, "confluence.contributors", traceContributors, StepState.DONE);

        int committerCount = 0;
        boolean committersFailed = false;   // FND-89
        for (CodeSearchResult h : codeHits) {
            try {
                List<Contact> committers = gitLab.recentCommitters(h.project(), h.filePath());
                committerCount += committers.size();
                raw.addAll(committers);
            } catch (com.company.triage.gateway.GatewayUnavailableException e) {
                // Contacts are display-only (J9). Losing them degrades the report; it must
                // never be the reason a diagnosis fails to appear — but the trace still has to
                // say the call failed rather than showing a clean row with fewer names on it.
                committersFailed = true;
                log.warn("  {} unreachable while gathering committers — continuing", e.system(), e);
            }
        }
        String traceCommitters = codeHits.isEmpty()
                ? "gitlab.recentCommitters → skipped (no code file was located to look up committers for)"
                : "gitlab.recentCommitters(%d file(s)) → %d committer name(s)"
                        .formatted(codeHits.size(), committerCount);
        trace.add(traceCommitters);
        emitStep(sink, stepSeq, "gitlab.recentCommitters", traceCommitters,
                committersFailed ? StepState.FAILED : StepState.DONE);

        // Merge, preserving first-seen order.
        //
        // FND-64: the key used to be handle-else-name, which silently failed the moment the
        // same person arrived from two sources with different identifier completeness — and
        // that is now the NORMAL case, because a name extracted from ticket or page prose has
        // no handle while the same person from the Confluence/GitLab APIs does. The demo showed
        // it immediately: "Priya Nair [confluence+gitlab]" listed separately from "Priya Nair
        // [servicenow]", and Marcus Chen split across two rows. Key on the normalised full name
        // when we have one (that is the identity humans actually match on), falling back to the
        // handle only for handle-only records like a bare username or email.
        Map<String, Contact> merged = new LinkedHashMap<>();
        for (Contact c : raw) {
            String key = mergeKey(c);
            Contact existing = merged.get(key);
            if (existing == null) {
                merged.put(key, c);
            } else {
                String source = existing.source().contains(c.source())
                        ? existing.source() : existing.source() + "+" + c.source();
                // Keep whichever record actually carries a handle/link — a prose mention has
                // neither, so a later API-sourced duplicate is what makes the contact actionable.
                String handle = (existing.handle() == null || existing.handle().isBlank())
                        ? c.handle() : existing.handle();
                String link = (existing.link() == null || existing.link().isBlank())
                        ? c.link() : existing.link();
                // Don't repeat a signal we already carry. One commit that touched three files
                // arrives here three times with an IDENTICAL signal, and concatenating blindly
                // rendered it as the same sentence three times over ("last touched X · last
                // touched X · last touched X") — noise that reads like three separate findings.
                // Distinct signals still concatenate: those genuinely are separate evidence.
                String signal = existing.signal().contains(c.signal())
                        ? existing.signal() : existing.signal() + " · " + c.signal();
                merged.put(key, new Contact(existing.name(), handle, source,
                        existing.reason() + "; " + c.reason(), link, signal));
            }
        }

        // Corroboration is the ranking signal: someone independently surfaced by servicenow
        // AND confluence AND gitlab is a better person to talk to than someone seen once.
        // FND-64: was a boolean "contains a +", which couldn't tell 2 sources from 3 — now
        // that ServiceNow contributes names too, three-way corroboration is reachable and
        // worth ranking above two. Stable, so equal counts keep discovery order.
        List<Contact> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingInt((Contact c) -> -sourceCount(c)));
        return out;
    }

    /**
     * Identity for merging. A full name ("Priya Nair") is what the same human looks like
     * across ServiceNow prose, a Confluence page and a git history, so it wins; a handle-only
     * record (a bare username like {@code m.chen}, or an email) keys on itself.
     */
    private static String mergeKey(Contact c) {
        String name = c.name() == null ? "" : c.name().trim();
        if (name.contains(" ")) {
            return name.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        }
        String handle = c.handle() == null || c.handle().isBlank() ? name : c.handle();
        return handle.toLowerCase(java.util.Locale.ROOT);
    }

    /** How many distinct sources independently surfaced this contact (source is "a+b+c"). */
    private static int sourceCount(Contact c) {
        return c.source() == null || c.source().isBlank() ? 0 : c.source().split("\\+").length;
    }

    /**
     * J29/LLF-3: the term the GitLab code search is issued with, chosen from ONE log line.
     *
     * <p>{@link #ERROR_TOKEN} alone matched {@code GNAF_FRONTAGE} on the real
     * delivery-hazards line — a location-data COLUMN NAME lifted out of the SQL
     * {@code Detail: Failing row contains (…)} tail, which appears there four times. It is
     * not a thing anyone would search for, and it is not in the source. The thrown
     * {@code DataIntegrityViolationException} on the same line is both.
     *
     * <p>So a stack-trace shape OUTRANKS the free-text token — option (a) on the card. Option
     * (b), searching GitLab for both terms and keeping whichever returns a hit, was rejected:
     * it adds a network round trip per candidate to a path already budgeted at 120s, and it
     * can still tie. {@code ERROR_TOKEN} itself is left alone; narrowing it to exclude data
     * values needs domain knowledge this engine does not have (out of scope on the card).
     */
    private static String searchTermFor(String message) {
        String token = firstMatch(ERROR_TOKEN, message);
        boolean carriesAThrownClass = EXCEPTION_FQN.matcher(message == null ? "" : message).find();
        if (!carriesAThrownClass && token != null) return token;
        // Fall back to the token when there is no class name to be had — including the
        // no-FQN case, where a line can still NAME an exception without qualifying it.
        String exceptionClass = firstMatch(EXCEPTION_CLASS, message);
        return exceptionClass != null ? exceptionClass : token;
    }

    private static String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? m.group() : null;
    }

    /**
     * FND-63: keep only refs whose Evidence actually exists in THIS report. The J4 validator
     * rejects a dangling evidenceRef, so filtering here is what lets the candidate/assignment
     * derivation name whatever it likes without risking a validation failure on an incident
     * whose evidence came out differently.
     */
    private static List<String> refsThatExist(java.util.Set<String> gathered, List<String> wanted) {
        return wanted.stream().filter(gathered::contains).distinct().toList();
    }

    /**
     * J28/PGC-1 — the causal narrative, <b>quoted whole from the incidents that carry one</b>.
     *
     * <p>Returns {@code null} when no similar incident recorded a resolution note. That is the
     * expected outcome much of the time and is NOT a degraded state: real {@code close_notes}
     * are frequently "Issue resolved" or blank. Abstaining is the honest answer, and the note
     * renders it explicitly with the denominator.
     *
     * <p>The note is quoted <b>whole and verbatim</b>. {@code close_notes} routinely carries
     * both the cause and the fix in one sentence — "Discount was applied after tax…; reconcile
     * check failed. Fixed order of operations in payment_service." — and splitting it would be
     * inference dressed as extraction. The fix half is expressed separately by
     * {@link #buildLikelyResolution}, using the controlled {@code close_code} rather than prose.
     *
     * <p>Only {@link InferenceBasis#PRIOR_RESOLUTION} is emitted (PGC-5). The other two bases
     * rest on J25 and J13, neither of which is built; enabling them now would quote irrelevant
     * pages faithfully, or cite the wrong system precisely.
     */
    private static LikelyCause buildLikelyCause(List<ResolvedIncident> similar,
                                                java.util.Set<String> gathered) {
        List<ResolvedIncident> withNotes = similar.stream()
                .filter(r -> notBlank(r.resolutionNotes()))
                .toList();
        if (withNotes.isEmpty()) return null;

        ResolvedIncident best = withNotes.get(0);   // already ranked by J26
        List<String> refs = refsThatExist(gathered, List.of("e-sim-" + best.number()));
        if (refs.isEmpty()) return null;            // never cite what we did not gather

        return new LikelyCause(
                best.resolutionNotes().trim(),
                List.of(best.number()),
                InferenceBasis.PRIOR_RESOLUTION,
                refs,
                withNotes.size(),
                similar.size());
    }

    /**
     * J28/PGC-2 + PGC-1a — how similar incidents were resolved, built <b>entirely from closed
     * vocabularies</b>: our {@link ResolutionVerb} and ServiceNow's {@code close_code}.
     *
     * <p>No gathered free text reaches this component. That is the concept's central safety
     * property: text from a ticket, a wiki page or a log line is attacker- and
     * mistake-influenceable, and it may appear in the CAUSE section as an attributed quotation
     * the reader can trace — but never in the section that tells them what to do.
     *
     * <p>Mitigation and permanent fix are split on the close code, because a workaround and a
     * code fix carry very different risk if the reader applies them with the wrong reflex.
     */
    private static LikelyResolution buildLikelyResolution(List<ResolvedIncident> similar,
                                                          java.util.Set<String> gathered) {
        ResolutionStep mitigation = null;
        ResolutionStep permanentFix = null;

        for (ResolvedIncident r : similar) {
            // Both are required. A close CODE alone tells you the ticket was closed, not what
            // was DONE — "Closed - No fault found" is a real close code and reporting it under
            // "how similar incidents were resolved" would be false. Requiring a resolution
            // NOTE means this section only ever describes incidents where a human recorded an
            // action, which is what the heading claims.
            if (!notBlank(r.resolutionCode()) || !notBlank(r.resolutionNotes())) continue;
            List<String> refs = refsThatExist(gathered, List.of("e-sim-" + r.number()));
            if (refs.isEmpty()) continue;

            boolean isPermanent = r.resolutionCode().toLowerCase().contains("code fix");
            ResolutionStep step = new ResolutionStep(
                    // CONSULT_RUNBOOK for a known error (there is documented guidance to read
                    // first); CHECK otherwise. Both are observation-only by construction —
                    // ResolutionVerb has no constant that mutates state.
                    r.resolutionCode().toLowerCase().contains("known error")
                            ? ResolutionVerb.CONSULT_RUNBOOK : ResolutionVerb.CHECK,
                    r.resolutionCode().trim(),
                    r.number(),
                    refs);

            if (isPermanent && permanentFix == null) permanentFix = step;
            else if (!isPermanent && mitigation == null) mitigation = step;
        }

        if (mitigation == null && permanentFix == null) return null;
        return new LikelyResolution(mitigation, permanentFix);
    }

    /**
     * J25/KQR-2 — how well a returned page matches what we were actually looking for, 0..1.
     *
     * <p>Term overlap against the affected system plus the symptom's distinctive keywords,
     * with the <b>title weighted above the body</b>: a runbook that is ABOUT the thing names
     * it in the title, whereas a body mention is as likely to be an aside. On the live
     * instance a Teradata data-model PDF was cited as evidence for a delivery-hazards
     * incident purely on incidental body overlap.
     *
     * <p>Deliberately dumb — no stemming, no TF-IDF. The deterministic engine is the
     * no-LLM fallback and this must stay explainable in one sitting; the failure it prevents
     * is "wildly unrelated page", not "slightly less relevant page".
     */
    private static double knowledgeRelevance(KnowledgeDoc doc, IncidentSignals signals) {
        java.util.Set<String> title = tokens(doc.title());
        java.util.Set<String> body = tokens(doc.snippet());

        // Scored on the SYSTEM and the SYMPTOM separately, then the better of the two — a
        // runbook for the affected system is relevant even when it uses none of the reporter's
        // words, and a page describing this exact symptom is relevant even if it never names
        // the system. Summing them instead would penalise both kinds of genuinely useful page
        // for not also being the other kind, which is how the first cut of this dropped the
        // demo's own "Order Payment Reconciliation" runbook.
        double bySystem = overlap(tokens(signals.app()), title, body);
        java.util.Set<String> symptomTerms = new java.util.LinkedHashSet<>();
        for (String k : signals.keywords()) symptomTerms.addAll(tokens(k));
        double bySymptom = overlap(symptomTerms, title, body);

        if (bySystem < 0 && bySymptom < 0) return 1.0;   // nothing to match on — don't filter blindly
        return Math.max(bySystem, bySymptom);
    }

    /**
     * Share of {@code wanted} present in the page, title counting double a body mention.
     * Returns -1 when there is nothing to look for, so the caller can tell "no signal" apart
     * from "looked and found nothing".
     */
    private static double overlap(java.util.Set<String> wanted,
                                  java.util.Set<String> title, java.util.Set<String> body) {
        if (wanted.isEmpty()) return -1;
        double hits = 0;
        for (String w : wanted) {
            if (matches(w, title)) hits += 1.0;
            else if (matches(w, body)) hits += 0.5;
        }
        return hits / wanted.size();
    }

    /**
     * Prefix match rather than equality, so {@code orders} finds {@code order} and
     * {@code reconciliation} finds {@code reconcile}. Crude stemming on purpose: a real
     * stemmer is a dependency and a behaviour nobody here can predict at 3am, while the
     * failure being prevented is a wildly unrelated page, not a near-miss.
     */
    private static boolean matches(String wanted, java.util.Set<String> haystack) {
        for (String h : haystack) {
            if (h.equals(wanted)) return true;
            int shared = Math.min(h.length(), wanted.length());
            if (shared >= 5 && (h.startsWith(wanted.substring(0, shared))
                    || wanted.startsWith(h.substring(0, shared)))) {
                return true;
            }
        }
        return false;
    }

    /** Lowercased alphanumeric words of 3+ chars — short tokens carry no signal. */
    private static java.util.Set<String> tokens(String s) {
        if (s == null || s.isBlank()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : s.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    /** {@code payment_service} / {@code order-payments/payment-service} → {@code Payment Service}. */
    private static String prettifySystem(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String last = raw.substring(raw.lastIndexOf('/') + 1);
        String[] parts = last.split("[_\\-]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.isEmpty() ? last : sb.toString();
    }

    private static String firstDocId(List<KnowledgeDoc> docs) {
        return docs.isEmpty() ? "\0none" : docs.get(0).id();
    }

    private static String joinNonBlank(String sep, String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(sep));
    }

    private static String text(String s) { return s == null ? "" : s; }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

}
