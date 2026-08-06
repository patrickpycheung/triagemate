package com.company.triage.gateway.fixture;

/**
 * Which incident the current recording belongs to.
 *
 * <p>Used in both directions — recording files answers under the incident being captured,
 * replay reads the bundle for the incident being diagnosed. In both cases the latch is set
 * by the ServiceNow gateway, which is the only one that sees an incident number.
 *
 * <p>Fixtures are filed per incident, but only the ServiceNow gateway is ever told an
 * incident number — Confluence gets a query, Sumo gets a scope, GitLab gets a project.
 * So the ServiceNow recorder latches the number on {@code getIncident} and the other three
 * read it from here.
 *
 * <p>Deliberately a single volatile field rather than a ThreadLocal: recording is a
 * dev-time, one-incident-at-a-time action driven by {@code bin/record-fixtures.sh}, and a
 * ThreadLocal would in fact be WRONG for the ADK path, where tool calls run on different
 * threads than the one that fetched the incident. Concurrent recording of two incidents is
 * not supported and would interleave — don't do it; the script drives one at a time.
 */
public final class FixtureSession {

    private volatile String incident = "UNKNOWN";

    public void set(String incidentNumber) {
        if (incidentNumber != null && !incidentNumber.isBlank()) {
            this.incident = incidentNumber.trim().toUpperCase();
        }
    }

    public String current() {
        return incident;
    }
}
