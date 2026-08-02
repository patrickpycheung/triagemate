package com.company.triage.orchestration.trace;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TASK-009's stub {@link RunTraceRegistry}: in-memory, <b>unbounded, no TTL, no
 * eviction</b>. That is deliberate and safe for what this task delivers — every entry
 * requires a caller to opt in with a client-minted {@code runId} header, and K1 (the one
 * caller that runs unattended, indefinitely) never sends one (see {@link RunTraceRegistry}'s
 * javadoc) — but it is NOT safe to leave this way once attended, header-sending callers can
 * run for a long session. TASK-010 owns adding the cap (~20 retained runs) and TTL (~5 min)
 * the design doc's "Bound the buffer" paragraph requires, either by replacing this class or
 * by bounding the maps below; {@link RunTraceRegistry}'s contract does not need to change
 * either way.
 */
@Component
public class InMemoryRunTraceRegistry implements RunTraceRegistry {

    /** runId -> the TraceCollector backing it. TraceCollector already gives per-attempt
     *  segmenting, thread-safe concurrent writers, and a steps() snapshot for free (STREAM-001/
     *  STREAM-003), so it doubles as this stub's storage rather than duplicating that logic. */
    private final Map<String, TraceCollector> collectors = new ConcurrentHashMap<>();

    /** incident -> the runId currently registered for it, so a coalesced caller's alias()
     *  call can find the canonical run's collector (FND-31; LT4 "key by runId, not incident"
     *  — this map exists only to resolve that lookup, it is never itself the buffer key). */
    private final Map<String, String> currentRunIdByIncident = new ConcurrentHashMap<>();

    @Override
    public TraceCollector register(String runId, String incidentNumber) {
        TraceCollector collector = new TraceCollector();
        collectors.put(runId, collector);
        currentRunIdByIncident.put(incidentNumber, runId);
        return collector;
    }

    @Override
    public void alias(String runId, String incidentNumber) {
        String canonicalRunId = currentRunIdByIncident.get(incidentNumber);
        if (canonicalRunId == null) {
            return; // nobody registered a runId for this incident's canonical run — nothing to alias to
        }
        TraceCollector canonicalCollector = collectors.get(canonicalRunId);
        if (canonicalCollector != null) {
            collectors.put(runId, canonicalCollector);
        }
    }

    // --- visible for tests -------------------------------------------------------
    TraceCollector peek(String runId) { return collectors.get(runId); }
}
