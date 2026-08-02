package com.company.triage.orchestration.trace;

import com.company.triage.guardrails.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * TASK-005 (J11 LT2): {@link StepCatalog} tests. Deliberately lives in
 * {@code src/test/java/} — not {@code src/test/adk/} — so the {@code registry ⊆ catalog}
 * assertion below runs under a bare {@code mvn test} and not only under {@code -Padk}.
 * That is the whole point of keeping both {@link ToolRegistry} and {@link StepCatalog} in
 * {@code src/main/java/}: the default build is the one that proves the catalog can't drift
 * out from under the permitted-tool set without a test failure.
 */
class StepCatalogTest {

    @Test
    void everyPermittedToolNameIsCoveredByTheCatalog() {
        assertThat(StepCatalog.keys())
                .as("StepCatalog must label every tool ToolRegistry permits")
                .containsAll(ToolRegistry.ALLOWED_TOOLS);
    }

    @Test
    void adkToolNamesResolveToTheirPlatform() {
        assertThat(StepCatalog.lookup("search_confluence").platform()).isEqualTo(Platform.CONFLUENCE);
        assertThat(StepCatalog.lookup("get_incident").platform()).isEqualTo(Platform.SERVICENOW);
        assertThat(StepCatalog.lookup("search_logs").platform()).isEqualTo(Platform.SUMO);
        assertThat(StepCatalog.lookup("search_code").platform()).isEqualTo(Platform.GITLAB);
        assertThat(StepCatalog.lookup("find_recent_committers").platform()).isEqualTo(Platform.GITLAB);
    }

    @Test
    void deterministicDottedKeysResolveToTheirPlatform() {
        assertThat(StepCatalog.lookup("confluence.search"))
                .isEqualTo(new StepCatalog.Entry(Platform.CONFLUENCE, "Searching Confluence for a runbook…"));
        assertThat(StepCatalog.lookup("servicenow.getIncident").platform()).isEqualTo(Platform.SERVICENOW);
        assertThat(StepCatalog.lookup("sumo.search").platform()).isEqualTo(Platform.SUMO);
        assertThat(StepCatalog.lookup("gitlab.searchCode").platform()).isEqualTo(Platform.GITLAB);
    }

    @Test
    void nonPlatformDeterministicLinesMapToTriagemate() {
        assertThat(StepCatalog.lookup("understand:").platform()).isEqualTo(Platform.TRIAGEMATE);
        assertThat(StepCatalog.lookup("contacts:").platform()).isEqualTo(Platform.TRIAGEMATE);
        assertThat(StepCatalog.lookup("report assembled:").platform()).isEqualTo(Platform.TRIAGEMATE);
    }

    @Test
    void unknownToolFallsBackToBlockedTriagemateEntry() {
        StepCatalog.Entry entry = StepCatalog.lookup("delete_production_database");

        assertThat(entry.platform()).isEqualTo(Platform.TRIAGEMATE);
        assertThat(entry.label()).isEqualTo(StepCatalog.BLOCKED_LABEL);
        assertThat(entry).isEqualTo(StepCatalog.blocked());
    }

    @Test
    void nullKeyIsRejectedRatherThanSilentlyMappedToBlocked() {
        assertThatIllegalArgumentException().isThrownBy(() -> StepCatalog.lookup(null));
    }

    @Test
    void modelThinkBypassesTheCatalogEntirely() {
        StepCatalog.Entry entry = StepCatalog.modelThink();

        assertThat(entry.platform()).isEqualTo(Platform.TRIAGEMATE);
        assertThat(entry.label()).isEqualTo(StepCatalog.MODEL_THINK_LABEL);
        assertThat(entry).isNotEqualTo(StepCatalog.blocked());
    }

    @Test
    void entryRejectsNullPlatformAndBlankLabel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepCatalog.Entry(null, "label"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepCatalog.Entry(Platform.SERVICENOW, " "));
    }
}
