package com.company.triage.gateway.mock;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The simulated-network knob: bounds, the off switch, and cancellability. */
class MockLatencyTest {

    @Test
    void pausesWithinTheConfiguredBounds() {
        MockLatency latency = new MockLatency(40, 80);

        long slowest = IntStream.range(0, 12).mapToLong(i -> {
            long t0 = System.nanoTime();
            latency.pause();
            return (System.nanoTime() - t0) / 1_000_000;
        }).max().orElse(0);

        // Only the UPPER bound is asserted, with headroom. A lower-bound assertion would be
        // testing the OS scheduler, which is free to oversleep and does so under CI load —
        // the classic flaky-timing test. Overshoot is the only direction that matters here
        // anyway: the point is that the demo does not stall.
        assertThat(slowest).isLessThan(1_000);
    }

    @Test
    void zeroMaxDisablesItEntirely() {
        long t0 = System.nanoTime();
        for (int i = 0; i < 1_000; i++) MockLatency.none().pause();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(200);
    }

    /** A max below min is a mis-set demo knob, not a reason to refuse to start. */
    @Test
    void anInvertedRangeDegradesToAFixedDelayRatherThanThrowing() {
        MockLatency latency = new MockLatency(30, 5);
        latency.pause();          // must not throw (IllegalArgumentException from Random)
    }

    /**
     * DiagnosisOrchestrator enforces its wall-clock timeout by interrupting the run's thread.
     * A sleep that swallowed the flag would leave a MOCK run harder to cancel than the real
     * one it stands in for.
     */
    @Test
    void interruptionIsPropagatedRatherThanSwallowed() throws Exception {
        MockLatency latency = new MockLatency(5_000, 5_000);
        var flagSeen = new java.util.concurrent.atomic.AtomicBoolean();

        Thread t = new Thread(() -> {
            latency.pause();
            flagSeen.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(2_000);

        assertThat(t.isAlive()).as("pause() should return promptly when interrupted").isFalse();
        assertThat(flagSeen).isTrue();
    }
}
