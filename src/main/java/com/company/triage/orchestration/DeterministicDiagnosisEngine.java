package com.company.triage.orchestration;

import com.company.triage.gateway.ConfluenceGateway;
import com.company.triage.gateway.GitLabGateway;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.gateway.SumoGateway;
import com.company.triage.model.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, deterministic implementation of the bounded phase flow (J1/J2). It runs
 * the SAME ordered steps the ADK agent runs — fetch → clarify → similar+ownership →
 * knowledge → bounded logs → targeted code → assemble report — but with scripted
 * reasoning instead of an LLM, so the demo works with zero network. The live agentic
 * version lives in src/main/adk (profile {@code adk}).
 *
 * <p>Default engine (active unless {@code triage.engine=adk}).
 */
@Component
@ConditionalOnProperty(name = "triage.engine", havingValue = "deterministic", matchIfMissing = true)
public class DeterministicDiagnosisEngine implements DiagnosisEngine {

    private static final Pattern ORDER_ID = Pattern.compile("\\bINC-ORD-\\d+\\b");
    private static final Pattern ERROR_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");
    private static final List<String> SUMO_SCOPE_ALLOWLIST = List.of("prod/payment", "prod/order-api");

    private final ServiceNowGateway serviceNow;
    private final ConfluenceGateway confluence;
    private final SumoGateway sumo;
    private final GitLabGateway gitLab;

    public DeterministicDiagnosisEngine(ServiceNowGateway serviceNow, ConfluenceGateway confluence,
                                        SumoGateway sumo, GitLabGateway gitLab) {
        this.serviceNow = serviceNow;
        this.confluence = confluence;
        this.sumo = sumo;
        this.gitLab = gitLab;
    }

    @Override
    public DiagnosisResult diagnose(String incidentNumber) {
        List<String> trace = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        // ---- Step 1: fetch incident (ServiceNow) ------------------------------
        IncidentContext inc = serviceNow.getIncident(incidentNumber);
        trace.add("servicenow.getIncident(%s) → CI=%s, env=%s".formatted(
                incidentNumber, inc.configurationItem(), inc.environment()));

        // ---- Step 2: clarify symptom (extract identifiers) --------------------
        String rawText = (inc.shortDescription() + " " + inc.description());
        String orderId = firstMatch(ORDER_ID, rawText);
        Identifiers ids = new Identifiers(orderId, null, orderId);
        trace.add("understand: symptom clarified; orderId=%s".formatted(orderId));

        // ---- Step 3: similar incidents + ownership ----------------------------
        List<ResolvedIncident> similar = serviceNow.findSimilarIncidents(inc);
        for (ResolvedIncident r : similar) {
            evidence.add(new Evidence("e-sim-" + r.number(), "servicenow-incident",
                    "%s (%.0f%% similar) resolved by %s: %s".formatted(
                            r.number(), r.similarity() * 100, r.resolutionGroup(), r.resolutionCode()),
                    r.number()));
        }
        trace.add("servicenow.findSimilarIncidents → %d hits".formatted(similar.size()));

        Optional<ServiceOwnership> ownership = serviceNow.findOwnership(inc.configurationItem());
        ownership.ifPresent(o -> evidence.add(new Evidence("e-cmdb", "servicenow-cmdb",
                "CMDB: %s owned by %s".formatted(o.application(), o.supportGroup()), o.source())));
        trace.add("servicenow.findOwnership(%s) → %s".formatted(
                inc.configurationItem(), ownership.map(ServiceOwnership::supportGroup).orElse("none")));

        // ---- Step 4: knowledge (Confluence) -----------------------------------
        List<KnowledgeDoc> docs = confluence.search("checkout order payment reconcile 500");
        for (KnowledgeDoc d : docs) {
            evidence.add(new Evidence("e-kb-" + d.id(), "confluence",
                    "%s (%s): %s".formatted(d.title(), d.id(), d.snippet()), d.url()));
        }
        trace.add("confluence.search → %d page(s)".formatted(docs.size()));

        // ---- Step 5: bounded logs (Sumo) --------------------------------------
        String scope = SUMO_SCOPE_ALLOWLIST.get(0);   // allowlisted scope only
        LogSearchRequest req = new LogSearchRequest(scope,
                orderId == null ? "error" : orderId,
                inc.openedAt().minusMinutes(10), inc.openedAt().plusMinutes(10), 20);
        List<LogEvidence> logs = sumo.search(req);
        LogEvidence errorLine = logs.stream().filter(l -> "ERROR".equals(l.level())).findFirst().orElse(null);
        String errorToken = errorLine == null ? null : firstMatch(ERROR_TOKEN, errorLine.message());
        if (errorLine != null) {
            evidence.add(new Evidence("e-log", "sumo",
                    "%s log [%s]: %s".formatted(errorLine.logger(), errorLine.level(), errorLine.message()),
                    scope));
        }
        trace.add("sumo.search(scope=%s, window=±10m, max=20) → %d line(s); errorToken=%s"
                .formatted(scope, logs.size(), errorToken));

        // ---- Step 6: targeted code search + log↔code citation (RC3) -----------
        String function = "Order submission (checkout)";
        List<CodeSearchResult> codeHits = List.of();
        if (errorToken != null) {
            codeHits = gitLab.searchCode("order-payments/payment-service", errorToken);
            for (CodeSearchResult h : codeHits) {
                evidence.add(new Evidence("e-code", "gitlab",
                        "log line '%s' is emitted at %s:%d".formatted(errorToken, h.filePath(), h.line()),
                        "%s/%s#L%d".formatted(h.project(), h.filePath(), h.line())));
            }
            trace.add("gitlab.searchCode('%s') → %d hit(s) (log↔code citation)"
                    .formatted(errorToken, codeHits.size()));
        }

        // ---- Step 7: who to talk to (J9) --------------------------------------
        // Derived from the SAME evidence already gathered: authors/editors of the
        // runbooks the triage cited, plus recent committers to the implicated file.
        List<Contact> contacts = gatherContacts(docs, codeHits);
        trace.add("contacts: %d suggested (from %d doc(s) + %d code file(s), merged across sources)"
                .formatted(contacts.size(), docs.size(), codeHits.size()));

        // ---- Step 8: assemble the diagnosis report (J4) -----------------------
        List<CandidateSystem> candidates = List.of(
                new CandidateSystem("Payment Service", 0.86,
                        List.of("e-log", "e-code", "e-kb-KB001234", "e-sim-INC0011902")),
                new CandidateSystem("Order Portal", 0.55, List.of("e-cmdb")));

        SuggestedAssignment assignment = ownership
                .map(o -> new SuggestedAssignment(o.supportGroup(), Confidence.MEDIUM,
                        List.of("e-cmdb", "e-sim-INC0011902", "e-kb-KB001234")))
                .orElse(new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM,
                        List.of("e-sim-INC0011902")));

        DiagnosisReport report = new DiagnosisReport(
                incidentNumber, OffsetDateTime.now(),
                "Checkout order submission intermittently fails with a server error; "
                        + "logs show a payment reconcile mismatch on a discounted order.",
                function, inc.environment(), ids,
                candidates, assignment, evidence, contacts,
                List.of("Order Portal also appears in the CMDB path but no Order Portal errors "
                        + "were observed in the window — the failure is downstream in payment reconciliation."),
                List.of("Whether all customers are affected or only discounted orders",
                        "Affected user id / session", "Exact app URL / environment build"),
                errorToken != null
                        ? "Confirm the discount-before-tax vs after-tax order of operations in payment_service.reconcile(); "
                          + "compare expected vs charged for a discounted+taxed order (see payment_service.py:44)."
                        : "Reproduce a failing checkout and capture the correlation id.",
                Confidence.MEDIUM, true);

        trace.add("report assembled: %d candidates, %d evidence items, assignment=%s"
                .formatted(candidates.size(), evidence.size(), assignment.group()));

        return new DiagnosisResult(report, trace);
    }

    /**
     * Collect suggested contacts (J9) from the evidence already gathered and merge
     * across sources. Someone who both edited a cited runbook AND recently committed
     * the implicated file is the strongest signal, so their two entries collapse into
     * one {@code confluence+gitlab} contact, ordered first.
     */
    private List<Contact> gatherContacts(List<KnowledgeDoc> docs, List<CodeSearchResult> codeHits) {
        List<Contact> raw = new ArrayList<>();
        for (KnowledgeDoc d : docs) {
            raw.addAll(confluence.contributors(d));
        }
        for (CodeSearchResult h : codeHits) {
            raw.addAll(gitLab.recentCommitters(h.project(), h.filePath()));
        }

        // Merge by handle (fall back to name); preserve first-seen order.
        Map<String, Contact> merged = new LinkedHashMap<>();
        for (Contact c : raw) {
            String key = (c.handle() == null || c.handle().isBlank() ? c.name() : c.handle())
                    .toLowerCase();
            Contact existing = merged.get(key);
            if (existing == null) {
                merged.put(key, c);
            } else {
                String source = existing.source().contains(c.source())
                        ? existing.source() : existing.source() + "+" + c.source();
                merged.put(key, new Contact(existing.name(), existing.handle(), source,
                        existing.reason() + "; " + c.reason(), existing.link(),
                        existing.signal() + " · " + c.signal()));
            }
        }

        // Cross-source contacts (strongest) first, then the rest in discovery order.
        List<Contact> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Boolean.compare(b.source().contains("+"), a.source().contains("+")));
        return out;
    }

    private static String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? m.group() : null;
    }
}
