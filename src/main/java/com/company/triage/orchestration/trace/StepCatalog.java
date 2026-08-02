package com.company.triage.orchestration.trace;

import java.util.Map;
import java.util.Set;

/**
 * J11's labeling catalog (LT2): maps a tool/trace key to the {@link Platform} it talks
 * to and the in-progress verb shown while the row is {@link StepState#ACTIVE} (e.g.
 * "Searching Confluence for a runbook…"). Those in-progress verbs exist nowhere else
 * today — only the result strings recorded after a call resolves do.
 *
 * <p>Keyed by <b>both</b> naming schemes in play across the two engines:
 * <ul>
 *   <li>ADK snake_case tool names ({@code search_confluence}, {@code get_incident}, …) —
 *       the function names {@code AdkDiagnosisEngine}/{@code TriageMateTools} register.</li>
 *   <li>The deterministic engine's dotted trace-line prefixes ({@code confluence.search},
 *       {@code servicenow.getIncident}, …) — the leading token of each
 *       {@code DeterministicDiagnosisEngine} {@code trace.add(...)} line, before its
 *       {@code (args) → result} suffix.</li>
 * </ul>
 *
 * <p><b>This class is a catalog, not a source of truth.</b> Round 2 of the J11 CDS
 * considered folding {@link com.company.triage.guardrails.ToolRegistry}'s permitted-tool
 * set into this catalog; Round 3 review rejected that — it would make an
 * observability/UI component the authority on which tools the model may call, inverting
 * J8's security ownership. A catalog entry must never be able to <em>grant</em>
 * permission. Instead this catalog only <em>covers</em> the registry's permitted set,
 * and {@link com.company.triage.guardrails.ToolRegistryTest} plus the subset assertion in
 * {@code StepCatalogTest} keep the two consistent — {@link com.company.triage.guardrails.ToolRegistry}
 * stays the single definition, checked in {@code src/main/java/}, so a bare {@code mvn
 * test} (no {@code -Padk}) proves every permitted tool has a label.
 *
 * <p><b>Model-think rows bypass this catalog entirely (Round 6 finding).</b> LT4's model
 * callback edges produce rows with no tool at all ({@code tool = null}) — there is no key
 * to look up. Callers of this class must route those rows through {@link #modelThink()}
 * rather than passing {@code null} into {@link #lookup(String)}; a {@code null} key is
 * treated as a caller bug (throws), not silently mapped to a fallback, because a silent
 * null-tolerant lookup would hide the exact case this catalog needs to be explicit about.
 *
 * <p><b>Unknown/hallucinated tool names</b> — the main {@code DENIED} case — are, by
 * construction, absent from this catalog: {@code BoundsCallback} denies names outside
 * the allowlist, so a name that reaches this lookup and isn't a key is either a
 * hallucinated tool or a bug in the allowlist itself. Those fall back to
 * {@link Platform#TRIAGEMATE} with the label {@value #BLOCKED_LABEL}, distinguishable
 * from other {@code TRIAGEMATE} rows only by state ({@link StepState#DENIED}) and the
 * {@code result} text a caller attaches from {@code BoundsCallback.denialReason(...)}.
 */
public final class StepCatalog {

    /** Label used for a tool name this catalog does not recognise (see class doc). */
    public static final String BLOCKED_LABEL = "Blocked an unrecognised tool call";

    /** Fixed label for a model-think row (see {@link #modelThink()}). */
    public static final String MODEL_THINK_LABEL = "Thinking…";

    /** A catalog entry: which system a step talks to, and its in-progress label. */
    public record Entry(Platform platform, String label) {
        public Entry {
            if (platform == null) {
                throw new IllegalArgumentException("platform must not be null");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
        }
    }

    private static final Entry BLOCKED = new Entry(Platform.TRIAGEMATE, BLOCKED_LABEL);
    private static final Entry MODEL_THINK = new Entry(Platform.TRIAGEMATE, MODEL_THINK_LABEL);

    private static final Map<String, Entry> ENTRIES = buildEntries();

    private static Map<String, Entry> buildEntries() {
        Map<String, Entry> entries = new java.util.LinkedHashMap<>();

        // ---- ADK snake_case tool names (must cover ToolRegistry.ALLOWED_TOOLS) -----
        entries.put("get_incident",
                new Entry(Platform.SERVICENOW, "Fetching the incident from ServiceNow…"));
        entries.put("find_similar_incidents",
                new Entry(Platform.SERVICENOW, "Searching ServiceNow for similar incidents…"));
        entries.put("find_ownership",
                new Entry(Platform.SERVICENOW, "Looking up ownership in the CMDB…"));
        entries.put("search_confluence",
                new Entry(Platform.CONFLUENCE, "Searching Confluence for a runbook…"));
        entries.put("search_logs",
                new Entry(Platform.SUMO, "Searching Sumo Logic for related log lines…"));
        entries.put("search_code",
                new Entry(Platform.GITLAB, "Searching GitLab for the code path…"));
        entries.put("find_page_contributors",
                new Entry(Platform.CONFLUENCE, "Looking up who wrote the runbook…"));
        entries.put("find_recent_committers",
                new Entry(Platform.GITLAB, "Looking up recent committers…"));

        // ---- Deterministic engine's dotted trace-line prefixes ---------------------
        // (DeterministicDiagnosisEngine's trace.add("<prefix>(...) → ...") calls.)
        entries.put("servicenow.getIncident",
                new Entry(Platform.SERVICENOW, "Fetching the incident from ServiceNow…"));
        entries.put("servicenow.findSimilarIncidents",
                new Entry(Platform.SERVICENOW, "Searching ServiceNow for similar incidents…"));
        entries.put("servicenow.findOwnership",
                new Entry(Platform.SERVICENOW, "Looking up ownership in the CMDB…"));
        entries.put("confluence.search",
                new Entry(Platform.CONFLUENCE, "Searching Confluence for a runbook…"));
        entries.put("sumo.search",
                new Entry(Platform.SUMO, "Searching Sumo Logic for related log lines…"));
        entries.put("gitlab.searchCode",
                new Entry(Platform.GITLAB, "Searching GitLab for the code path…"));

        // ---- Non-platform deterministic lines: internal to this app, not a real ----
        // ---- integration call, so they map to the TRIAGEMATE pseudo-platform. ------
        entries.put("understand:",
                new Entry(Platform.TRIAGEMATE, "Understanding the incident…"));
        entries.put("contacts:",
                new Entry(Platform.TRIAGEMATE, "Finding who to talk to…"));
        entries.put("report assembled:",
                new Entry(Platform.TRIAGEMATE, "Assembling the diagnosis report…"));

        return Map.copyOf(entries);
    }

    private StepCatalog() {
    }

    /**
     * Look up the {@link Platform}/label for a tool name or deterministic trace-line
     * prefix. Unrecognised keys (a hallucinated tool name that slipped past
     * {@code BoundsCallback}, or any other unmapped key) fall back to
     * {@link Platform#TRIAGEMATE} with {@link #BLOCKED_LABEL} — this method never throws
     * for an unknown-but-non-null key.
     *
     * @param key a tool name (ADK) or trace-line prefix (deterministic engine); must not
     *            be {@code null} — use {@link #modelThink()} for the no-tool case instead.
     * @throws IllegalArgumentException if {@code key} is {@code null}
     */
    public static Entry lookup(String key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "StepCatalog.lookup() key must not be null — model-think rows have no "
                    + "tool to look up; call StepCatalog.modelThink() for those instead");
        }
        return ENTRIES.getOrDefault(key, BLOCKED);
    }

    /**
     * The fixed entry for a model-think row (LT4's model callback edges: {@code tool =
     * null}). Bypasses the catalog map entirely — see the Round 6 finding in the class
     * doc — rather than being reachable via {@link #lookup(String)} with a null key.
     */
    public static Entry modelThink() {
        return MODEL_THINK;
    }

    /** The fixed entry used for a tool/prefix this catalog does not recognise. */
    public static Entry blocked() {
        return BLOCKED;
    }

    /** All keys this catalog covers (tool names + deterministic trace-line prefixes). */
    public static Set<String> keys() {
        return ENTRIES.keySet();
    }
}
