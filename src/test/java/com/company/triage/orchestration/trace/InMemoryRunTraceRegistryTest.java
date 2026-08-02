package com.company.triage.orchestration.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** TASK-009's stub {@link RunTraceRegistry} implementation: unbounded, no TTL (TASK-010's job). */
class InMemoryRunTraceRegistryTest {

    @Test
    void registerReturnsAFreshCollectorPerRunId() {
        var registry = new InMemoryRunTraceRegistry();

        TraceCollector a = registry.register("run-A", "INC0010005");
        TraceCollector b = registry.register("run-B", "INC0010006");

        assertThat(a).isNotSameAs(b);
        assertThat(a.steps()).isEmpty();
    }

    @Test
    void aliasPointsTheCoalescedRunIdAtTheCanonicalIncidentsCollector() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector canonical = registry.register("run-A", "INC0010005");
        canonical.forAttempt(0).before(new TraceStep(0, 0, "c1", Platform.SERVICENOW, "tool",
                "label", null, StepState.ACTIVE, System.currentTimeMillis(), null,
                com.company.triage.orchestration.DiagnosisResult.Engine.DETERMINISTIC));

        registry.alias("run-B", "INC0010005");

        assertThat(registry.peek("run-B")).isSameAs(canonical);
        assertThat(registry.peek("run-B").steps()).hasSize(1);
    }

    @Test
    void aliasWithNoCanonicalRegisteredIsANoOp() {
        var registry = new InMemoryRunTraceRegistry();

        assertThatCode(() -> registry.alias("run-B", "INC9999999")).doesNotThrowAnyException();
    }
}
