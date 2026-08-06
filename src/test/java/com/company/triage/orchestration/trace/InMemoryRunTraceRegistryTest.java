package com.company.triage.orchestration.trace;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bounded {@link RunTraceRegistry} implementation: cap ~20 retained runs (oldest-first
 * eviction) and a TTL measured from each run's LAST WRITE, not its creation time (design
 * doc: {@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §LT4 "Bound the
 * buffer"), as amended by {@code J16-run-trace-registry-lifecycle} — RTR-2 ({@code aliasTo}
 * binds runId → runId, the incident index is gone), RTR-3 (a done buffer is never aliasable)
 * and RTR-4 (TTL derives from {@code timeout-ms}). This is the one part of the whole J11
 * design with no prior spike, so coverage here is deliberately thorough.
 */
class InMemoryRunTraceRegistryTest {

    /** A {@link Clock} test double whose {@link #instant()} can be advanced on demand, so
     *  TTL tests don't have to sleep for real minutes. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private static TraceStep activeStep(long startedAtEpochMs) {
        return new TraceStep(0, 0, "c1", Platform.SERVICENOW, "tool", "label", null,
                StepState.ACTIVE, startedAtEpochMs, null,
                com.company.triage.orchestration.DiagnosisResult.Engine.DETERMINISTIC);
    }

    // --- pre-existing register()/alias() contract (TASK-009) -----------------------

    @Test
    void registerReturnsAFreshCollectorPerRunId() {
        var registry = new InMemoryRunTraceRegistry();

        TraceCollector a = registry.register("run-A", "INC0010005");
        TraceCollector b = registry.register("run-B", "INC0010006");

        assertThat(a).isNotSameAs(b);
        assertThat(a.steps()).isEmpty();
    }

    @Test
    void aliasToPointsTheCoalescedRunIdAtTheCanonicalRunsCollector() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector canonical = registry.register("run-A", "INC0010005");
        canonical.forAttempt(0).before(activeStep(System.currentTimeMillis()));

        assertThat(registry.aliasTo("run-B", "run-A")).isTrue();

        assertThat(registry.peek("run-B")).isSameAs(canonical);
        assertThat(registry.peek("run-B").steps()).hasSize(1);
    }

    /**
     * Re-pointed, not deleted: this used to pass an INCIDENT with nothing registered for it.
     * Under RTR-2 the argument is a canonical {@code runId}, so the same "nothing to alias
     * to" case is now an unknown runId — and the no-op is observable as a {@code false}
     * return rather than only as "didn't throw", which is what lets {@code
     * DiagnosisOrchestrator} tell a bound waiter from an unbound one.
     */
    @Test
    void aliasToAnUnregisteredCanonicalRunIdIsARefusedNoOp() {
        var registry = new InMemoryRunTraceRegistry();

        assertThatCode(() -> registry.aliasTo("run-B", "run-nope")).doesNotThrowAnyException();
        assertThat(registry.aliasTo("run-B", "run-nope")).isFalse();
        assertThat(registry.peek("run-B")).isNull();
    }

    /**
     * J16/RTR-3 (server side). A {@code done} collector can never emit another step, so
     * binding a live poller to it would have the UI narrate "live" over a finished run —
     * the honesty failure J11 exists to prevent. Refuse instead; the waiter's poll then
     * 404s and the client renders the settled result from the POST response.
     */
    @Test
    void aliasToARunThatIsAlreadyDoneIsRefused() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector canonical = registry.register("run-A", "INC0010005");
        canonical.markDone();

        assertThat(registry.aliasTo("run-B", "run-A")).isFalse();
        assertThat(registry.peek("run-B"))
                .as("a terminal buffer is never aliasable").isNull();
        assertThat(registry.peek("run-A"))
                .as("refusing the alias must not disturb the canonical entry").isSameAs(canonical);
    }

    // --- cap: retain ~20, evict oldest-first (Requirement 1 / 4) -------------------

    @Test
    void capEvictsOldestFirstWhenExceeded() {
        var registry = new InMemoryRunTraceRegistry();

        for (int i = 0; i < 25; i++) {
            registry.register("run-" + i, "INC-" + i);
        }

        assertThat(registry.size()).as("buffer must stay bounded at the cap").isEqualTo(20);
        for (int i = 0; i < 5; i++) {
            assertThat(registry.peek("run-" + i)).as("run-%d should have aged out oldest-first", i).isNull();
        }
        for (int i = 5; i < 25; i++) {
            assertThat(registry.peek("run-" + i)).as("run-%d should still be retained", i).isNotNull();
        }
    }

    @Test
    void capIsEnforcedUnderConcurrentSequentialRuns() throws Exception {
        var registry = new InMemoryRunTraceRegistry();
        int totalRuns = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch done = new CountDownLatch(totalRuns);
            AtomicInteger failures = new AtomicInteger();
            for (int i = 0; i < totalRuns; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        registry.register("run-" + idx, "INC-" + idx);
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).as("all registrations completed").isTrue();

            assertThat(failures.get()).isZero();
            assertThat(registry.size())
                    .as("buffer must never exceed the cap, even under concurrent registration")
                    .isLessThanOrEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- TTL: ~5 min after LAST WRITE, not creation (Requirement 2 / 5) ------------

    @Test
    void entriesOlderThanTtlAreEvictedOnNextSweep() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        registry.register("run-A", "INC0010005");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        registry.register("run-B", "INC0010006"); // triggers the sweep

        assertThat(registry.peek("run-A")).as("run-A should have aged out past the TTL").isNull();
        assertThat(registry.peek("run-B")).isNotNull();
    }

    @Test
    void entriesWithinTtlSurvive() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        registry.register("run-A", "INC0010005");
        clock.advance(Duration.ofMinutes(4));
        registry.register("run-B", "INC0010006");

        assertThat(registry.peek("run-A")).as("run-A is still within its TTL").isNotNull();
    }

    @Test
    void ttlIsMeasuredFromLastWriteNotCreation() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        TraceCollector collector = registry.register("run-A", "INC0010005");

        // A write lands 4 minutes after creation.
        clock.advance(Duration.ofMinutes(4));
        collector.forAttempt(0).before(activeStep(clock.millis()));

        // 4 more minutes pass (8 total since creation, past a creation-based TTL, but
        // only 4 since the last write — still within the write-based TTL).
        clock.advance(Duration.ofMinutes(4));
        registry.register("run-B", "INC0010006"); // sweep #1

        assertThat(registry.peek("run-A"))
                .as("must survive: only 4 minutes have passed since the LAST WRITE")
                .isNotNull();

        // Now push past 5 minutes since that last write.
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        registry.register("run-C", "INC0010007"); // sweep #2

        assertThat(registry.peek("run-A"))
                .as("must be evicted once 5 minutes have passed since the LAST WRITE")
                .isNull();
    }

    @Test
    void aliasToSweepsBeforeResolvingSoAStaleCanonicalRunIsARefusedNoOp() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        registry.register("run-A", "INC0010005");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        // Nothing has re-registered yet, so run-A is still physically present but stale;
        // aliasTo() itself must sweep before resolving, or it would resurrect a buffer the
        // very next sweep retires.
        assertThat(registry.aliasTo("run-B", "run-A")).isFalse();

        assertThat(registry.peek("run-A")).isNull();
        assertThat(registry.peek("run-B")).isNull();
    }

    // --- re-registration after eviction ---------------------------------------------

    @Test
    void reRegisteringAnIncidentAfterEvictionWorksNormally() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        registry.register("run-A", "INC0010005");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        registry.register("run-Z", "INC-filler"); // sweeps run-A out

        TraceCollector fresh = registry.register("run-A2", "INC0010005");
        assertThat(registry.aliasTo("run-B2", "run-A2")).isTrue();

        assertThat(registry.peek("run-B2")).isSameAs(fresh);
    }

    @Test
    void aliasedRunIdOccupiesItsOwnCapSlotWhileSharingTheCollector() {
        var registry = new InMemoryRunTraceRegistry();
        registry.register("run-A", "INC0010005");
        registry.aliasTo("run-B", "run-A");

        assertThat(registry.size()).isEqualTo(2); // run-A and run-B both resolvable, sharing one collector
        assertThat(registry.peek("run-A")).isSameAs(registry.peek("run-B"));
    }

    // --- RTR-4: TTL derives from triage.orchestrator.timeout-ms ---------------------

    /**
     * The whole point of RTR-4: the buffer must outlive the run that writes into it. A run
     * that emits no steps can legitimately stay silent for a full {@code timeout-ms}, and
     * with an FND-7 degrade for two of them plus the writeback tail — so if the TTL ever
     * fell below that, {@code lookup()}'s own sweep would evict the buffer it was about to
     * read and the UI would go dark mid-run.
     */
    @Test
    void ttlAlwaysOutlivesTheWorstCaseSilentWindowOfTheConfiguredTimeout() {
        for (long timeoutMs : new long[] {1_000, 60_000, 120_000, 250_000, 600_000}) {
            Duration ttl = InMemoryRunTraceRegistry.ttlFor(timeoutMs);
            assertThat(ttl.toMillis())
                    .as("TTL must exceed 2 x timeout-ms (FND-7 degrade) for timeout-ms=%d", timeoutMs)
                    .isGreaterThan(2 * timeoutMs);
        }
    }

    /**
     * RTR-4 is a LINK, not a retune. At the shipped {@code timeout-ms: 120000} the derived
     * TTL is exactly the 5 minutes the hardcoded constant used to be, so shipped behaviour
     * is unchanged — this test is what would fail if someone "fixed" the formula into a
     * behaviour change.
     */
    @Test
    void atTheShippedTimeoutTheDerivedTtlIsExactlyTheFiveMinutesItReplaces() {
        assertThat(InMemoryRunTraceRegistry.ttlFor(120_000)).isEqualTo(Duration.ofMinutes(5));
    }

    /** The floor holds for short timeouts: a fast timeout must not shrink the window a
     *  human needs to read a finished run. */
    @Test
    void shortTimeoutsFallBackToTheFiveMinuteFloor() {
        assertThat(InMemoryRunTraceRegistry.ttlFor(1_000)).isEqualTo(Duration.ofMinutes(5));
        assertThat(InMemoryRunTraceRegistry.ttlFor(119_000)).isEqualTo(Duration.ofMinutes(5));
    }

    /** A longer timeout genuinely stretches the TTL — the link is live, not decorative. */
    @Test
    void aLongerTimeoutStretchesTheTtlAndTheRegistryHonoursIt() {
        assertThat(InMemoryRunTraceRegistry.ttlFor(300_000))
                .isEqualTo(Duration.ofMinutes(10).plusSeconds(60));

        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(InMemoryRunTraceRegistry.ttlFor(300_000), clock);
        registry.register("run-A", "INC0010005");

        // Past the old hardcoded 5 min, but well inside a timeout-ms=300000 run's own bound:
        // under the pre-RTR-4 constant this entry was evicted while its run was still live.
        clock.advance(Duration.ofMinutes(9));
        registry.register("run-B", "INC0010006"); // sweeps

        assertThat(registry.peek("run-A"))
                .as("a run whose timeout allows 9 minutes of silence must still have its buffer")
                .isNotNull();
    }

    /** The Spring-wired constructor is the one that actually reads config — pin that it
     *  derives, rather than silently taking the floor. */
    @Test
    void springWiredConstructorDerivesItsTtlFromTriageProperties() {
        var base = com.company.triage.config.TriagePropertiesFixture.deterministic();
        var props = new com.company.triage.config.TriageProperties(base.engine(), base.writeback(),
                new com.company.triage.config.TriageProperties.Orchestrator(300_000), base.agent(),
                base.trigger(), base.servicenow(), base.sumo(), base.gitlab());

        assertThat(new InMemoryRunTraceRegistry(props).ttl())
                .isEqualTo(Duration.ofMinutes(10).plusSeconds(60));
    }

    // --- lookup() (TASK-011: the read side GET /api/runs/{runId}/steps needs) ------

    @Test
    void lookupReturnsTheRegisteredCollector() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector collector = registry.register("run-A", "INC0010005");

        assertThat(registry.lookup("run-A")).isSameAs(collector);
    }

    @Test
    void lookupOfAnUnknownRunIdThrowsRunNotFoundException() {
        var registry = new InMemoryRunTraceRegistry();

        assertThatThrownBy(() -> registry.lookup("never-registered"))
                .isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void lookupOfATtlExpiredRunIdThrowsRunNotFoundExceptionEvenWithoutAPriorSweepingCall() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);
        registry.register("run-A", "INC0010005");

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        // No register()/alias() call happened since — lookup() itself must sweep, not
        // rely on some other call to have done it, since a poller can be the only
        // activity on an otherwise-idle run.
        assertThatThrownBy(() -> registry.lookup("run-A"))
                .isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void lookupSeesTheAliasedCollectorToo() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector canonical = registry.register("run-A", "INC0010005");
        registry.aliasTo("run-B", "run-A");

        assertThat(registry.lookup("run-B")).isSameAs(canonical);
    }

    @Test
    void unrelatedIncidentsDoNotInterfereKeyingIsByRunId() {
        // Regression pin for the binding correction: DiagnosisOrchestratorTest proves
        // sequential runs of the SAME incident are not coalesced, so the buffer must never
        // be keyed by incident. Registering two runIds for the same incident must produce
        // two independent, non-aliased collectors unless aliasTo() is explicitly called.
        var registry = new InMemoryRunTraceRegistry();

        TraceCollector first = registry.register("run-A", "INC0010005");
        TraceCollector second = registry.register("run-A2", "INC0010005");

        assertThat(first).isNotSameAs(second);
        assertThat(registry.peek("run-A")).isSameAs(first);
        assertThat(registry.peek("run-A2")).isSameAs(second);
    }
}
