package com.company.triage.gateway.fixture;

/**
 * Which incident the current run's fixtures belong to.
 *
 * <p>Used in both directions — recording files answers under the incident being captured,
 * replay reads the bundle for the incident being diagnosed. In both cases the latch is set
 * by the ServiceNow gateway, which is the only one ever told an incident number: Confluence
 * gets a query, Sumo gets a scope, GitLab gets a project.
 *
 * <p><b>Per-thread, because two diagnoses can be in flight at once.</b> This was a single
 * volatile field, on the reasoning that recording is a one-at-a-time dev action. That was
 * true of RECORDING and false of REPLAY, which serves the demo: with two runs in flight, the
 * second one's {@code getIncident} overwrote the first one's latch, and the first run's
 * remaining connectors then replayed the WRONG incident's bundle. Measured at 12 failures in
 * 15 interleaved rounds — a Delivery Hazards run citing payment-reconcile evidence, under the
 * right ticket number. A presenter clicking Diagnose twice is enough to trigger it.
 *
 * <p>Each diagnosis runs on its own virtual thread (see {@code DiagnosisOrchestrator}'s
 * timeout wrapper) and every gateway call in that run happens on it, so the thread IS the
 * run boundary and a {@link ThreadLocal} isolates exactly the right scope.
 *
 * <p>The volatile is kept as a fallback for the ADK path, where the model's tool calls can
 * land on a different thread than the one that fetched the incident. There it degrades to
 * the old behaviour — last writer wins — which is what it always was, rather than returning
 * nothing at all.
 */
public final class FixtureSession {

    /** The run's own latch. Set and read on the diagnosis thread. */
    private final ThreadLocal<String> perRun = new ThreadLocal<>();

    /** Cross-thread fallback for the ADK path; also what a fresh thread sees. */
    private volatile String lastSet = "UNKNOWN";

    public void set(String incidentNumber) {
        if (incidentNumber != null && !incidentNumber.isBlank()) {
            String v = incidentNumber.trim().toUpperCase();
            perRun.set(v);
            lastSet = v;
        }
    }

    public String current() {
        String mine = perRun.get();
        return mine != null ? mine : lastSet;
    }

    /**
     * Drops this thread's latch. Worth calling when a pooled (non-virtual) thread might run
     * a later, unrelated diagnosis and would otherwise inherit a stale value.
     */
    public void clear() {
        perRun.remove();
    }
}
