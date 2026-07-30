package com.company.triage.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J8's leash. Both halves are asserted here because only one of them used to be real:
 * {@code allow(String toolName)} accepted the name and ignored it, so the documented
 * "the app controls which tools are permitted" claim was enforced by nothing.
 */
class BoundsCallbackTest {

    private static final Set<String> ALLOWED =
            Set.of("get_incident", "search_logs", "search_code");

    @Test
    void permitsAllowlistedToolsWithinBudget() {
        var bounds = new BoundsCallback(10, ALLOWED);

        assertThat(bounds.allow("get_incident")).isTrue();
        assertThat(bounds.allow("search_logs")).isTrue();
        assertThat(bounds.used()).isEqualTo(2);
    }

    /** The half that was previously documented but not implemented. */
    @Test
    void deniesToolNotInTheAllowlist() {
        var bounds = new BoundsCallback(10, ALLOWED);

        assertThat(bounds.allow("delete_incident")).isFalse();      // never registered
        assertThat(bounds.allow("unregistered_tool")).isFalse();    // hallucinated name
        assertThat(bounds.allow(null)).isFalse();                   // defensive
        assertThat(bounds.denialReason("delete_incident"))
                .contains("not in the app's allowlist");
    }

    /** A rejected name must not burn budget — otherwise a bad name degrades a good run. */
    @Test
    void allowlistDenialDoesNotConsumeBudget() {
        var bounds = new BoundsCallback(2, ALLOWED);

        assertThat(bounds.allow("nope")).isFalse();
        assertThat(bounds.allow("nope")).isFalse();
        assertThat(bounds.used()).isZero();

        assertThat(bounds.allow("get_incident")).isTrue();          // full budget intact
        assertThat(bounds.allow("search_logs")).isTrue();
        assertThat(bounds.allow("search_code")).isFalse();          // now over budget
    }

    @Test
    void enforcesBudgetForAllowlistedTools() {
        var bounds = new BoundsCallback(2, ALLOWED);

        assertThat(bounds.allow("get_incident")).isTrue();
        assertThat(bounds.allow("get_incident")).isTrue();
        assertThat(bounds.allow("get_incident")).isFalse();
        assertThat(bounds.denialReason("get_incident")).contains("max tool calls (2)");
    }

    /** The legacy single-arg constructor keeps its old budget-only behaviour. */
    @Test
    void budgetOnlyConstructorDoesNotEnforceAnAllowlist() {
        var bounds = new BoundsCallback(5);

        assertThat(bounds.enforcesAllowlist()).isFalse();
        assertThat(bounds.allow("anything_at_all")).isTrue();
        assertThat(new BoundsCallback(5, ALLOWED).enforcesAllowlist()).isTrue();
    }

    /** The allowlist must match the names ADK actually reports (@Schema, snake_case). */
    @Test
    void allowlistUsesAdkSchemaNamesNotJavaMethodNames() {
        var bounds = new BoundsCallback(10, ALLOWED);

        assertThat(bounds.allow("get_incident")).isTrue();   // @Schema name
        assertThat(bounds.allow("getIncident")).isFalse();   // Java method name — not what ADK reports
    }
}
