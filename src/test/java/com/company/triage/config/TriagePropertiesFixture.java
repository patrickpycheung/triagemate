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

    /** Mirrors application.yml's Sumo block, so tests exercise the real category pattern. */
    public static TriageProperties.Sumo sumo() {
        return new TriageProperties.Sumo(
                "IDT/ITServices/Tomcat/{project}/{environment}/AppEvt_{project}",
                java.util.Map.of(),
                // Mirrors application.yml: the _index clause is DISABLED (operator
                // instruction, 2026-08-05) and the window ceiling is a day. Verified against
                // the live AU instance — the scoped query returns 105 ERROR rows for
                // delivery-hazards/prod over 24h with no _index clause at all, so the clause
                // is not load-bearing on this estate.
                "",
                List.of("pdev", "ptest", "stest", "vtest", "prod"),
                20, 1440);
    }

    /**
     * J31 — the deterministic engine with a MULTI-PROJECT GitLab allowlist.
     *
     * <p>The default fixture pins exactly one project, which quietly made the allowlist
     * *sweep* untestable: with one entry there is no second candidate to continue to, so
     * "the sweep skipped a failed project and carried on" could not be expressed, let alone
     * asserted. Real config has held two entries since J30/GEB-1.
     */
    public static TriageProperties deterministicWithProjects(String... gitLabProjects) {
        TriageProperties base = deterministic();
        return new TriageProperties(
                base.engine(), base.writeback(), base.orchestrator(), base.agent(),
                base.trigger(), base.servicenow(), base.sumo(),
                new TriageProperties.GitLab(List.of(gitLabProjects)));
    }

    public static TriageProperties withEngine(TriageProperties.Engine engine) {
        return new TriageProperties(
                engine,
                new TriageProperties.Writeback(true),
                new TriageProperties.Orchestrator(90000),
                new TriageProperties.Agent(10),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, false)),
                new TriageProperties.ServiceNow("work_notes", "1,6,7", 0.25, 5, java.util.Map.of()),
                sumo(),
                new TriageProperties.GitLab(List.of("order-payments/payment-service")));
    }
}
