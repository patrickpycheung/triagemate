package com.company.triage.orchestration.trace;

/**
 * The "thing that stores {@link TraceStep}s per {@code runId}" for the LT4 poll-a-buffer
 * transport (J11: {@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §LT4,
 * "The {@code runId} protocol").
 *
 * <p><b>TASK-009 (this interface, wired through {@code DiagnosisOrchestrator}) only does
 * the request-side header handling</b>: when a caller supplies a client-minted {@code runId}
 * (the optional {@code X-Triage-Run-Id} header on {@code POST /api/diagnose/{incidentNumber}}),
 * {@link #register} hands back the real {@link TraceCollector} to use for that run, so a
 * future {@code GET /api/runs/{runId}/steps} poll can read the exact same steps this run
 * accumulates. A caller with no {@code runId} — critically, {@code IncidentPoller} (K1),
 * which has no live UI to feed — never calls this interface at all: LT4 rule 4, "no header ⇒
 * no buffer", so K1 can never grow it unattended.
 *
 * <p><b>What this task leaves for TASK-010.</b> This stub is deliberately unbounded, with no
 * TTL and no eviction — see {@link InMemoryRunTraceRegistry}. TASK-010 owns turning it into
 * an actually-bounded buffer (design doc: cap ~20 retained runs, ~5 min TTL, oldest-first
 * eviction — "Bound the buffer", same paragraph as the {@code runId} protocol) and the
 * {@code GET} endpoint that reads it. Neither of those requires this interface, or any of
 * {@code DiagnosisOrchestrator}'s call sites, to change — implement a new {@code
 * RunTraceRegistry} (or make this one bounded internally) and swap the Spring bean.
 */
public interface RunTraceRegistry {

    /**
     * Registers {@code runId} as backing a live run of {@code incidentNumber} and returns
     * the {@link TraceCollector} to accumulate its steps in — the same collector the caller
     * also uses to build the run's {@code DiagnosisResult}, so the live buffer and the final
     * response are always in sync. Called only when a caller supplied a client-minted
     * {@code runId}; never for a header-less caller (LT4 rule 4).
     */
    TraceCollector register(String runId, String incidentNumber);

    /**
     * FND-31: alias a coalesced caller's {@code runId} to whatever {@code runId} is
     * currently registered for {@code incidentNumber} (the canonical, already-running
     * diagnosis), so a client polling on the coalesced {@code runId} watches the canonical
     * run's live steps instead of a buffer that will never receive any — the coalesced
     * caller never runs an engine of its own ({@code DiagnosisOrchestrator#awaitExisting}).
     * A no-op if nothing is currently registered for {@code incidentNumber} (e.g. no caller
     * for this incident supplied a {@code runId}, or — once TASK-010 adds eviction — the
     * canonical run's entry has already aged out).
     */
    void alias(String runId, String incidentNumber);
}
