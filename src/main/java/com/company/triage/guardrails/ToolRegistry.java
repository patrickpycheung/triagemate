package com.company.triage.guardrails;

import java.util.Set;

/**
 * J8's canonical, security-owned set of tool names the live agent (J2/ADK) is
 * permitted to call.
 *
 * <p>This is the single source of truth for "what tools may the model invoke" — it
 * lives in {@code src/main/java/} (not {@code src/main/adk/}) and outside the
 * observability layer ({@code orchestration.trace}, J11) deliberately.
 *
 * <p>Round 2 of the J11 CDS considered moving this set into {@code StepCatalog}
 * (J11's labeling/observability catalog). Round 3 review rejected that: making an
 * observability/UI component the source of truth for which tools the model may call
 * would invert J8's security ownership. A catalog entry must never be able to
 * <em>grant</em> permission — {@code StepCatalog} may only reference (and be checked
 * against) this registry, never define it.
 *
 * <p>{@link com.company.triage.agent.AdkDiagnosisEngine} reads this registry to build
 * the {@code BoundsCallback} allowlist (enforcement); {@code StepCatalog} (J11,
 * TASK-005) reads it to verify its own labels cover every permitted tool. Because this
 * class lives in {@code src/main/java/}, a bare {@code mvn test} (no {@code -Padk})
 * compiles and runs against it — unlike the previous {@code AdkDiagnosisEngine}-private
 * constant, which lived in {@code src/main/adk/} and was invisible to the default
 * build.
 */
public final class ToolRegistry {

    /** The canonical, security-owned set of permitted tool names. */
    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "get_incident",
            "find_similar_incidents",
            "find_ownership",
            "search_confluence",
            "search_logs",
            "search_code",
            "find_page_contributors",
            "find_recent_committers");

    private ToolRegistry() {
    }
}
