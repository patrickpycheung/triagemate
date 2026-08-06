package com.company.triage.gateway.mock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A small random pause before each mock connector answers, so an offline run has the
 * TEXTURE of a real one.
 *
 * <p>Why bother: the mocks answer in microseconds, which is not what any of the four systems
 * does. That mattered in two directions. On stage the whole run completed before the eye
 * could follow it, so the live trace — the thing the demo is actually showing — arrived as a
 * finished block rather than as work happening. And in testing, instant connectors hide every
 * ordering and concurrency assumption; the fixture-latch race (two runs sharing one session)
 * was found precisely because real HTTP timing left a window that microsecond mocks never did.
 *
 * <p>Bounds come from {@code triage.connectors.mock-latency.{min-ms,max-ms}}. Set max to 0 to
 * turn it off entirely — which is what a test wanting speed should do, and what the no-arg
 * mock constructors get by default.
 *
 * <p>Interruption is honoured rather than swallowed: {@code DiagnosisOrchestrator} enforces
 * its wall-clock timeout by interrupting the run's thread, so a sleep that ate the interrupt
 * flag would make a mock run harder to cancel than the real one it stands in for.
 */
@Component
public class MockLatency {

    private final int minMs;
    private final int maxMs;

    public MockLatency(@Value("${triage.connectors.mock-latency.min-ms:120}") int minMs,
                       @Value("${triage.connectors.mock-latency.max-ms:400}") int maxMs) {
        this.minMs = Math.max(0, minMs);
        // A max below min would make the bound meaningless; treat min as the floor rather
        // than throwing, since a mis-set demo knob should not stop the app from starting.
        this.maxMs = Math.max(this.minMs, maxMs);
    }

    /** No delay — for the mocks' no-arg constructors and any test that wants full speed. */
    public static MockLatency none() {
        return new MockLatency(0, 0);
    }

    public void pause() {
        if (maxMs <= 0) return;
        // Bound is exclusive, so +1 keeps max-ms attainable and min==max valid (fixed delay).
        int ms = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
