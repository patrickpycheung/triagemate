package com.company.triage.orchestration.trace;

/**
 * The "thing that stores {@link TraceStep}s per {@code runId}" for the LT4 poll-a-buffer
 * transport (J11: {@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §LT4,
 * "The {@code runId} protocol"), as amended by J16 ({@code
 * docs/design-java/concepts/J16-run-trace-registry-lifecycle/README.md}).
 *
 * <p><b>J16/RTR-1: every run has a {@code runId} and a registered buffer, whatever the
 * trigger.</b> LT4 rule 4 — "no header ⇒ no buffer" — is <b>retired</b>. It made the
 * existence of a live buffer depend on which trigger started the run, so the FND-31
 * mixed-trigger shape (K1 poller owns the run, a K3 click coalesces onto it) left the
 * attended caller with nothing live to watch: the owner had no buffer to alias to. {@code
 * DiagnosisOrchestrator} now mints {@code "srv-" + UUID.randomUUID()} when the caller
 * supplied no {@code X-Triage-Run-Id} header and ALWAYS calls {@link #register}; a
 * client-minted {@code runId} stays authoritative when present, so the header contract is
 * unchanged.
 *
 * <p>What rule 4 was protecting against — K1 growing this map without bound, unattended,
 * forever — is now handled where it belongs: by the cap and TTL below, not by refusing to
 * register. The cost is ~20 bounded map entries; the benefit is that "is there a live
 * buffer?" no longer depends on how the run was triggered.
 *
 * <p><b>Bounded.</b> {@link InMemoryRunTraceRegistry} caps retained runs (~20) with a TTL
 * measured from each run's <b>last write</b>, evicting oldest-first. J16/RTR-4 derives that
 * TTL from {@code triage.orchestrator.timeout-ms} rather than hardcoding it, so a run can
 * never outlive its own buffer.
 */
public interface RunTraceRegistry {

    /**
     * Registers {@code runId} as backing a live run of {@code incidentNumber} and returns
     * the {@link TraceCollector} to accumulate its steps in — the same collector the caller
     * also uses to build the run's {@code DiagnosisResult}, so the live buffer and the final
     * response are always in sync.
     *
     * <p>J16/RTR-1: called for EVERY run, not only header-carrying ones. A trigger that sent
     * no {@code runId} runs under a server-minted one instead.
     */
    TraceCollector register(String runId, String incidentNumber);

    /**
     * J16/RTR-2 + FND-31: point a coalesced waiter's {@code waiterRunId} at the collector
     * already registered for {@code canonicalRunId} — the run the waiter is about to block
     * on — so a client polling the waiter's id watches the canonical run's live steps
     * instead of a buffer that will never receive any (the waiter never runs an engine of
     * its own; see {@code DiagnosisOrchestrator#awaitExisting}).
     *
     * <p><b>runId → runId, never runId → incident.</b> The predecessor took an incident
     * number and resolved it through a separate {@code currentRunIdByIncident} index. That
     * index was a second source of truth for "which run is current for this incident",
     * racing the orchestrator's own in-flight map: a waiter could resolve to a DIFFERENT run
     * than the one it was about to await. The orchestrator now publishes the canonical
     * {@code runId} alongside its {@code CompletableFuture}, so the waiter passes the exact
     * id it is waiting on and the race has no room to exist.
     *
     * <p><b>J16/RTR-3 (server side): a terminal buffer is never aliasable.</b> Returns
     * {@code false} — aliasing nothing — when {@code canonicalRunId} is unknown, has aged
     * out, or is already {@link TraceCollector#isDone() done}. Binding a live poller to a
     * buffer that can never produce another step would have the UI narrate "live" over a
     * finished run, the exact honesty failure J11 exists to prevent. On {@code false} the
     * waiter simply has no live buffer: its poll 404s and the client renders the final
     * result from the POST response, which is the honest outcome.
     *
     * @return {@code true} if {@code waiterRunId} now resolves to the canonical collector.
     */
    boolean aliasTo(String waiterRunId, String canonicalRunId);

    /**
     * The read side ({@code GET /api/runs/{runId}/steps}): the {@link TraceCollector}
     * currently registered for {@code runId} — the same instance the owning run's engine, or
     * a coalesced waiter aliased via {@link #aliasTo}, is writing into. Throws {@link
     * RunNotFoundException} if {@code runId} is unknown OR has aged out under the TTL/cap
     * eviction; the two cases are indistinguishable to a poller and deliberately treated
     * identically here.
     */
    TraceCollector lookup(String runId);
}
