package com.company.triage.orchestration.trace;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TASK-010's bounded {@link RunTraceRegistry}: in-memory, capped at {@link
 * #MAX_RETAINED_RUNS} retained runs with a {@link #MIN_TTL a derived TTL} measured from each run's
 * <b>last write</b> — not its creation time — evicting oldest-first once either bound is
 * exceeded (design doc: {@code docs/design-java/concepts/J11-live-thinking-trace/README.md}
 * §LT4, "Bound the buffer"). Without this, K1 running unattended with no client ever
 * polling would grow this map forever; TASK-009's stub deliberately shipped unbounded and
 * left this task to bound it.
 *
 * <p><b>Keyed by {@code runId}, never by incident (LT4 binding correction).</b> {@code
 * DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced} proves sequential
 * runs of the same incident are deliberately separate events — an incident-keyed buffer would
 * leak one run's steps into the next run of the same incident. {@link
 * #currentRunIdByIncident} exists ONLY to resolve {@link #alias} for FND-31 concurrent
 * coalescing; it is never itself the buffer's storage key.
 *
 * <p><b>"Last write", without touching {@link TraceCollector}.</b> This registry only ever
 * observes a run at {@link #register} time (and possibly {@link #alias} time) — after that,
 * engines write directly into the {@link TraceCollector} via {@link TraceSink}, never back
 * through this registry, and {@code TraceCollector} is {@code final} with no write-observer
 * hook. Rather than add one (out of scope for this task — it would ripple into
 * STREAM-001/003's already-covered class), this registry derives "last write" from the
 * wall-clock {@link TraceStep#startedAtEpochMs()} already stamped on every row {@link
 * TraceCollector#steps()} returns, falling back to the registration time while a run has
 * produced no steps yet.
 *
 * <p><b>Eviction is swept lazily</b> on every {@link #register}/{@link #alias} call (no
 * background thread) — sufficient because, absent this task's not-yet-built {@code GET
 * /api/runs/{runId}/steps} poll endpoint, those two calls are the only activity this
 * registry ever sees; a long-running unattended demo still bounds the map because K1 (which
 * never supplies a {@code runId}) never calls either method, and every attended, header-
 * carrying caller's own {@code register} call sweeps stale entries left by earlier ones.
 *
 * <p>The {@link Clock} is package-private-injectable so tests can drive TTL expiry with a
 * fake clock instead of sleeping for real minutes.
 */
@Component
public class InMemoryRunTraceRegistry implements RunTraceRegistry {

    /** Cap on retained runs (design doc: "~20"). */
    static final int MAX_RETAINED_RUNS = 20;

    /**
     * J16/RTR-4 — the FLOOR under the TTL, not the TTL itself.
     *
     * <p>The TTL used to be exactly this constant, tied to nothing. A run is permitted to
     * take {@code triage.orchestrator.timeout-ms}; if that ever exceeds five minutes, a
     * buffer could be evicted <b>while its own run was still writing to it</b>, and a poller
     * would see the eviction as {@code RunNotFoundException} — indistinguishable from
     * "finished long ago". Two independently-tuned numbers where only one is a real bound.
     *
     * <p>So the TTL is now derived: {@code max(this floor, timeout × 2)}. The floor keeps a
     * FINISHED run's steps readable long enough for a human to look; the derivation guarantees
     * a run can never outlive its own buffer. Doubling leaves room for the settle-render after
     * the run ends, which is when a poller that started late does its only fetch.
     */
    static final Duration MIN_TTL = Duration.ofMinutes(5);

    /** Effective TTL for this instance — see {@link #MIN_TTL}. */
    private final Duration ttl;

    private final Clock clock;

    /** Guards both maps below — cap/TTL eviction and the incident lookup must stay
     *  mutually consistent, and both maps are cheap enough (≤ {@link #MAX_RETAINED_RUNS}
     *  live entries by construction) that a single lock scoped to register/alias is
     *  simpler and just as correct as a lock-free structure here. */
    private final Object lock = new Object();

    /** runId -> its entry, in insertion order (oldest-first) so cap eviction is a
     *  head-of-map removal. */
    private final LinkedHashMap<String, RunEntry> collectors = new LinkedHashMap<>();

    /** incident -> the runId currently registered for it, so alias() can find the
     *  canonical run's collector (FND-31). Never the buffer's storage key — see class
     *  javadoc. Kept in lockstep with `collectors`: an incident's entry is removed
     *  whenever the runId it points at is evicted (unless a newer registration already
     *  replaced it), so this map never outlives the collectors it can resolve to. */
    private final Map<String, String> currentRunIdByIncident = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryRunTraceRegistry(com.company.triage.config.TriageProperties props) {
        this(Clock.systemUTC(), ttlFor(props.orchestrator().timeoutMs()));
    }

    /**
     * Visible for tests: the {@link #MIN_TTL} floor, which is what every pre-J16 test was
     * already asserting against. Production always goes through the {@code TriageProperties}
     * constructor so the TTL is derived (RTR-4); this exists so a test that does not care
     * about TTL derivation does not have to construct config to say so.
     */
    public InMemoryRunTraceRegistry() {
        this(Clock.systemUTC(), MIN_TTL);
    }

    /** Visible for tests: inject a fake clock to drive TTL expiry without sleeping. */
    InMemoryRunTraceRegistry(Clock clock) {
        this(clock, MIN_TTL);
    }

    InMemoryRunTraceRegistry(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /** J16/RTR-4: a buffer outlives the run that owns it, by construction. */
    static Duration ttlFor(long orchestratorTimeoutMs) {
        Duration derived = Duration.ofMillis(orchestratorTimeoutMs).multipliedBy(2);
        return derived.compareTo(MIN_TTL) > 0 ? derived : MIN_TTL;
    }

    @Override
    public TraceCollector register(String runId, String incidentNumber) {
        TraceCollector collector = new TraceCollector();
        long now = clock.millis();
        synchronized (lock) {
            collectors.put(runId, new RunEntry(incidentNumber, collector, now));
            currentRunIdByIncident.put(incidentNumber, runId);
            evictStaleAndOverflow(now);
        }
        return collector;
    }

    @Override
    public void alias(String runId, String incidentNumber) {
        long now = clock.millis();
        synchronized (lock) {
            // Sweep first: a canonical run whose last write has already aged out must
            // behave as "nothing registered", exactly like the no-canonical-registered
            // no-op path below — this is TASK-009's "once TASK-010 adds eviction" case.
            evictStaleAndOverflow(now);

            String canonicalRunId = currentRunIdByIncident.get(incidentNumber);
            if (canonicalRunId == null) {
                return; // nobody registered a runId for this incident's canonical run — nothing to alias to
            }
            RunEntry canonical = collectors.get(canonicalRunId);
            if (canonical == null) {
                return; // canonical runId aged out between the sweep above and this lookup — no-op
            }
            collectors.put(runId, new RunEntry(incidentNumber, canonical.collector, canonical.registeredAtEpochMs));
            evictStaleAndOverflow(now); // the alias insertion itself can push the map over the cap
        }
    }

    /** Removes every entry whose last write is older than {@link #TTL}, then evicts
     *  oldest-first (insertion order) until the map is at or under {@link
     *  #MAX_RETAINED_RUNS}. Must be called while holding {@link #lock}. */
    private void evictStaleAndOverflow(long nowEpochMs) {
        long ttlMillis = ttl.toMillis();   // J16/RTR-4: derived, not a bare constant
        Iterator<Map.Entry<String, RunEntry>> it = collectors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RunEntry> entry = it.next();
            if (nowEpochMs - lastWriteEpochMs(entry.getValue()) >= ttlMillis) {
                it.remove();
                currentRunIdByIncident.remove(entry.getValue().incidentNumber, entry.getKey());
            }
        }
        while (collectors.size() > MAX_RETAINED_RUNS) {
            Iterator<Map.Entry<String, RunEntry>> oldestFirst = collectors.entrySet().iterator();
            Map.Entry<String, RunEntry> oldest = oldestFirst.next();
            oldestFirst.remove();
            currentRunIdByIncident.remove(oldest.getValue().incidentNumber, oldest.getKey());
        }
    }

    private static long lastWriteEpochMs(RunEntry entry) {
        long last = entry.registeredAtEpochMs;
        for (TraceStep step : entry.collector.steps()) {
            if (step.startedAtEpochMs() > last) {
                last = step.startedAtEpochMs();
            }
        }
        return last;
    }

    @Override
    public TraceCollector lookup(String runId) {
        long now = clock.millis();
        synchronized (lock) {
            // Sweep first: a poller (GET /api/runs/{runId}/steps) can be the ONLY activity
            // on a run whose owner has stopped writing — without sweeping here, a runId
            // whose TTL elapsed would keep reading as "found" until some unrelated
            // register()/alias() call happened to sweep it, which may never happen again
            // for a demo that's already moved on to a different incident.
            evictStaleAndOverflow(now);
            RunEntry entry = collectors.get(runId);
            if (entry == null) {
                throw new RunNotFoundException(runId);
            }
            return entry.collector;
        }
    }

    // --- visible for tests -------------------------------------------------------
    TraceCollector peek(String runId) {
        synchronized (lock) {
            RunEntry entry = collectors.get(runId);
            return entry == null ? null : entry.collector;
        }
    }

    int size() {
        synchronized (lock) {
            return collectors.size();
        }
    }

    private static final class RunEntry {
        final String incidentNumber;
        final TraceCollector collector;
        final long registeredAtEpochMs;

        RunEntry(String incidentNumber, TraceCollector collector, long registeredAtEpochMs) {
            this.incidentNumber = incidentNumber;
            this.collector = collector;
            this.registeredAtEpochMs = registeredAtEpochMs;
        }
    }
}
