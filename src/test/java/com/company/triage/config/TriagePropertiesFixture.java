package com.company.triage.config;

import java.util.List;

/** A default, always-valid {@link TriageProperties} for tests — mirrors application.yml's defaults. */
public final class TriagePropertiesFixture {

    private TriagePropertiesFixture() {}

    public static TriageProperties deterministic() {
        return withEngine(TriageProperties.Engine.DETERMINISTIC);
    }

    public static TriageProperties adk() {
        return withEngine(TriageProperties.Engine.ADK);
    }

    public static TriageProperties withEngine(TriageProperties.Engine engine) {
        return new TriageProperties(
                engine,
                new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(90000),
                new TriageProperties.Agent(10),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                new TriageProperties.ServiceNow("work_notes"),
                new TriageProperties.Sumo(List.of("prod/payment", "prod/order-api"), 20, 30),
                new TriageProperties.GitLab(List.of("order-payments/payment-service")));
    }
}
