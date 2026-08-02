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

/**
 * TASK-010's bounded {@link RunTraceRegistry} implementation: cap ~20 retained runs
 * (oldest-first eviction) and ~5 min TTL measured from each run's LAST WRITE, not its
 * creation time (design doc: {@code docs/design-java/concepts/J11-live-thinking-trace/
 * README.md} §LT4 "Bound the buffer"). This is the one part of the whole J11 design with
 * no prior spike, so coverage here is deliberately thorough rather than a smoke test.
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
    void aliasPointsTheCoalescedRunIdAtTheCanonicalIncidentsCollector() {
        var registry = new InMemoryRunTraceRegistry();
        TraceCollector canonical = registry.register("run-A", "INC0010005");
        canonical.forAttempt(0).before(activeStep(System.currentTimeMillis()));

        registry.alias("run-B", "INC0010005");

        assertThat(registry.peek("run-B")).isSameAs(canonical);
        assertThat(registry.peek("run-B").steps()).hasSize(1);
    }

    @Test
    void aliasWithNoCanonicalRegisteredIsANoOp() {
        var registry = new InMemoryRunTraceRegistry();

        assertThatCode(() -> registry.alias("run-B", "INC9999999")).doesNotThrowAnyException();
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
    void aliasSweepsBeforeResolvingSoAStaleCanonicalRunIsANoOp() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        registry.register("run-A", "INC0010005");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        // Nothing has re-registered yet, so run-A is still physically present but stale;
        // alias() itself must sweep before resolving, per TASK-009's carve-out for "once
        // TASK-010 adds eviction — the canonical run's entry has already aged out".
        registry.alias("run-B", "INC0010005");

        assertThat(registry.peek("run-A")).isNull();
        assertThat(registry.peek("run-B")).isNull();
    }

    // --- incident->runId map stays in lockstep with eviction ------------------------

    @Test
    void reRegisteringAnIncidentAfterEvictionWorksNormally() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InMemoryRunTraceRegistry(clock);

        registry.register("run-A", "INC0010005");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        registry.register("run-Z", "INC-filler"); // sweeps run-A out

        TraceCollector fresh = registry.register("run-A2", "INC0010005");
        registry.alias("run-B2", "INC0010005");

        assertThat(registry.peek("run-B2")).isSameAs(fresh);
    }

    @Test
    void aliasedRunIdOccupiesItsOwnCapSlotWhileSharingTheCollector() {
        var registry = new InMemoryRunTraceRegistry();
        registry.register("run-A", "INC0010005");
        registry.alias("run-B", "INC0010005");

        assertThat(registry.size()).isEqualTo(2); // run-A and run-B both resolvable, sharing one collector
        assertThat(registry.peek("run-A")).isSameAs(registry.peek("run-B"));
    }

    @Test
    void unrelatedIncidentsDoNotInterfereKeyingIsByRunId() {
        // Regression pin for the binding correction: DiagnosisOrchestratorTest proves
        // sequential runs of the SAME incident are not coalesced, so the buffer must never
        // be keyed by incident. Registering two runIds for the same incident must produce
        // two independent, non-aliased collectors unless alias() is explicitly called.
        var registry = new InMemoryRunTraceRegistry();

        TraceCollector first = registry.register("run-A", "INC0010005");
        TraceCollector second = registry.register("run-A2", "INC0010005");

        assertThat(first).isNotSameAs(second);
        assertThat(registry.peek("run-A")).isSameAs(first);
        assertThat(registry.peek("run-A2")).isSameAs(second);
    }
}
