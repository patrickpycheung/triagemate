package com.company.triage.guardrails;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J8's canonical permitted-tool set. This registry lives in {@code src/main/java/} so
 * it — and any test asserting other components stay consistent with it, such as a
 * future {@code registry ⊆ catalog} check against J11's {@code StepCatalog} — compiles
 * and runs under a bare {@code mvn test}, not only under {@code -Padk}.
 */
class ToolRegistryTest {

    @Test
    void containsTheEightPermittedToolNames() {
        assertThat(ToolRegistry.ALLOWED_TOOLS).containsExactlyInAnyOrder(
                "get_incident",
                "find_similar_incidents",
                "find_ownership",
                "search_confluence",
                "search_logs",
                "search_code",
                "find_page_contributors",
                "find_recent_committers");
    }

    @Test
    void isImmutable() {
        assertThat(ToolRegistry.ALLOWED_TOOLS)
                .as("the registry must not be mutable by callers")
                .isUnmodifiable();
    }
}
