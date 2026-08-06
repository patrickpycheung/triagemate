package com.company.triage.orchestration.trace;

import com.company.triage.config.TriageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bounded {@link RunTraceRegistry}: in-memory, capped at {@link #MAX_RETAINED_RUNS}
 * retained runs with a TTL measured from each run's <b>last write</b> — not its creation
 * time — evicting oldest-first once either bound is exceeded (design doc: {@code
 * docs/design-java/concepts/J11-live-thinking-trace/README.md} §LT4, "Bound the buffer").
 *
 * <p><b>The cap and TTL are the ONLY thing bounding this map (J16/RTR-1).</b> Until J16 they
 * shared that job with LT4 rule 4, "no header ⇒ no buffer": K1 (the unattended poller) never
 * supplied a {@code runId}, so it never registered anything and could not grow the map at
 * all. Rule 4 is retired — every run now registers under a real or server-minted {@code
 * runId} — so an unattended demo DOES write here on every tick, and the eviction below is
 * what keeps it bounded. That is the bound doing its actual job rather than being backstopped
 * by an accident of which trigger fired.
 *
 * <p><b>Keyed by {@code runId}, and only by {@code runId} (J16/RTR-2).</b> {@code
 * DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced} proves sequential
 * runs of the same incident are deliberately separate events, so an incident-keyed buffer
 * would leak one run's steps into the next. There used to be a second map,
 * {@code currentRunIdByIncident}, resolving "which run is current for this incident" so the
 * old {@code alias(runId, incidentNumber)} could find its target — a second source of truth
 * racing {@code DiagnosisOrchestrator}'s own in-flight map. {@link #aliasTo} takes the
 * canonical {@code runId} directly from that in-flight map instead, so the index has no
 * caller and is gone; this class now holds exactly one map.
 *
 * <p><b>"Last write", without touching {@link TraceCollector}.</b> This registry only ever
 * observes a run at {@link #register}/{@link #aliasTo}/{@link #lookup} time — engines write
 * directly into the {@link TraceCollector} via {@link TraceSink}, never back through here,
 * and {@code TraceCollector} is {@code final} with no write-observer hook. Rather than add
 * one, this registry derives "last write" from the wall-clock {@link
 * TraceStep#startedAtEpochMs()} already stamped on every row {@link TraceCollector#steps()}
 * returns, falling back to the registration time while a run has produced no steps yet.
 *
 * <p><b>Eviction is swept lazily</b> on every {@link #register}/{@link #aliasTo}/{@link
 * #lookup} call (no background thread) — sufficient because those are the only activity this
 * registry ever sees, and {@link #lookup} in particular means a poller on an otherwise-idle
 * run still sweeps its own entry.
 *
 * <p>The {@link Clock} is package-private-injectable so tests can drive TTL expiry with a
 * fake clock instead of sleeping for real minutes.
 */
@Component
public class InMemoryRunTraceRegistry implements RunTraceRegistry {

    /** Cap on retained runs (design doc: "~20"). */
    static final int MAX_RETAINED_RUNS = 20;

    /**
     * J16/RTR-4: the TTL floor. The design doc's original "~5 minutes" is kept as a lower
     * bound so short {@code timeout-ms} values can never shrink the buffer below the window
     * a human demo actually needs to watch a finished run.
     */
    static final Duration TTL_FLOOR = Duration.ofMinutes(5);

    /**
     * J16/RTR-4: {@code TTL = max(5 min, 2 × timeout-ms + 60 s)}, so the buffer always
     * outlives the run that writes into it.
     *
     * <p>Both a hardcoded 5 min TTL and {@code triage.orchestrator.timeout-ms} bound the same
     * physical window — how long a run can legitimately go without emitting a step — but
     * nothing linked them. A run that emits no steps can stay silent for a full timeout; if
     * the TTL ever fell below that, {@link #lookup}'s own sweep would evict the buffer it was
     * about to read, the poll would 404 with {@code everSawData=true}, and the UI would treat
     * that as the documented terminal signal and go dark <b>while the run was still in
     * flight</b>. {@code timeout-ms} has already been retuned once (90 s → 120 s, FND-69),
     * which is exactly how two unlinked constants drift into that state.
     *
     * <p>The {@code 2 ×} covers the FND-7 degrade (primary engine times out, deterministic
     * fallback runs a second full timeout) and the {@code + 60 s} covers the writeback tail.
     * At the shipped {@code timeout-ms: 120000} this evaluates to exactly 5 min — identical
     * to the constant it replaces. <b>This is a link, not a retune</b>: shipped behaviour is
     * deliberately unchanged.
     */
    static Duration ttlFor(long timeoutMs) {
        Duration derived = Duration.ofMillis(2 * timeoutMs).plusSeconds(60);
        return derived.compareTo(TTL_FLOOR) > 0 ? derived : TTL_FLOOR;
    }

    private final Clock clock;
    private final Duration ttl;

    /** Guards {@link #collectors} — cap/TTL eviction must be atomic against registration. */
    private final Object lock = new Object();

    /** runId -> its entry, in insertion order (oldest-first) so cap eviction is a
     *  head-of-map removal. */
    private final LinkedHashMap<String, RunEntry> collectors = new LinkedHashMap<>();

    /** The Spring-wired constructor: TTL derives from the run's own wall-clock bound
     *  (J16/RTR-4). Explicitly {@code @Autowired} because the test-only constructors below
     *  are also declared constructors, so Spring cannot pick one unambiguously on its own. */
    @Autowired
    public InMemoryRunTraceRegistry(TriageProperties props) {
        this(ttlFor(props.orchestrator().timeoutMs()), Clock.systemUTC());
    }

    /** Test/back-compat convenience: no {@link TriageProperties} wired in means the TTL
     *  floor, which is also what the shipped {@code timeout-ms: 120000} derives to. */
    public InMemoryRunTraceRegistry() {
        this(TTL_FLOOR, Clock.systemUTC());
    }

    /** Visible for tests: inject a fake clock to drive TTL expiry without sleeping. */
    InMemoryRunTraceRegistry(Clock clock) {
        this(TTL_FLOOR, clock);
    }

    /** Visible for tests: drive both the derived TTL and the clock. */
    InMemoryRunTraceRegistry(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public TraceCollector register(String runId, String incidentNumber) {
        TraceCollector collector = new TraceCollector();
        long now = clock.millis();
        synchronized (lock) {
            collectors.put(runId, new RunEntry(collector, now));
            evictStaleAndOverflow(now);
        }
        return collector;
    }

    @Override
    public boolean aliasTo(String waiterRunId, String canonicalRunId) {
        long now = clock.millis();
        synchronized (lock) {
            // Sweep first: a canonical run whose last write has already aged out must behave
            // as "nothing registered" rather than resurrect a buffer eviction just retired.
            evictStaleAndOverflow(now);

            RunEntry canonical = collectors.get(canonicalRunId);
            if (canonical == null) {
                return false; // unknown or aged out — the waiter gets no live buffer
            }
            // J16/RTR-3: refuse a terminal buffer. A done collector can never emit another
            // step, so a poller bound to it would render "live" over a finished run — the
            // honesty failure J11 exists to prevent. With RTR-2's register->publish->
            // putIfAbsent ordering the owner cannot be done while still in the in-flight
            // map, so this is a guard against a future caller reintroducing the class, not
            // a fix for a live path.
            if (canonical.collector.isDone()) {
                return false;
            }
            collectors.put(waiterRunId, new RunEntry(canonical.collector, canonical.registeredAtEpochMs));
            evictStaleAndOverflow(now); // the alias insertion itself can push the map over the cap
            return true;
        }
    }

    /** Removes every entry whose last write is older than {@link #ttl}, then evicts
     *  oldest-first (insertion order) until the map is at or under {@link
     *  #MAX_RETAINED_RUNS}. Must be called while holding {@link #lock}. */
    private void evictStaleAndOverflow(long nowEpochMs) {
        long ttlMillis = ttl.toMillis();
        Iterator<Map.Entry<String, RunEntry>> it = collectors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RunEntry> entry = it.next();
            if (nowEpochMs - lastWriteEpochMs(entry.getValue()) >= ttlMillis) {
                it.remove();
            }
        }
        while (collectors.size() > MAX_RETAINED_RUNS) {
            Iterator<Map.Entry<String, RunEntry>> oldestFirst = collectors.entrySet().iterator();
            oldestFirst.next();
            oldestFirst.remove();
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
            // register()/aliasTo() call happened to sweep it, which may never happen again
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

    Duration ttl() {
        return ttl;
    }

    private static final class RunEntry {
        final TraceCollector collector;
        final long registeredAtEpochMs;

        RunEntry(TraceCollector collector, long registeredAtEpochMs) {
            this.collector = collector;
            this.registeredAtEpochMs = registeredAtEpochMs;
        }
    }
}
