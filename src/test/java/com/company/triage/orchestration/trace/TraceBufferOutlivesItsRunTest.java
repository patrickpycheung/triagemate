package com.company.triage.orchestration.trace;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J16/RTR-4 — a buffer outlives the run that owns it, by construction.
 *
 * <p>The TTL was a bare five-minute constant tied to nothing, while a run is permitted to take
 * {@code triage.orchestrator.timeout-ms}. Two independently-tuned numbers where only one is a
 * real bound: raise the timeout past five minutes and a buffer could be evicted <b>while its
 * own run was still writing to it</b>. The poller would see that as
 * {@code RunNotFoundException} — indistinguishable from "finished long ago", so the live trace
 * would simply stop, mid-run, with no error anyone could act on.
 */
class TraceBufferOutlivesItsRunTest {

    @Test
    void aShortTimeoutStillKeepsFinishedStepsReadableForAHuman() {
        assertThat(InMemoryRunTraceRegistry.ttlFor(Duration.ofSeconds(30).toMillis()))
                .as("the floor exists for the READER, not the run — a 30s run's steps must "
                        + "still be there a minute later when someone looks")
                .isEqualTo(InMemoryRunTraceRegistry.MIN_TTL);
    }

    @Test
    void aTimeoutLongerThanTheFloorRaisesTheTtlAboveIt() {
        Duration ttl = InMemoryRunTraceRegistry.ttlFor(Duration.ofMinutes(10).toMillis());

        assertThat(ttl)
                .as("a run allowed 10 minutes must not have its buffer evicted at 5")
                .isGreaterThan(Duration.ofMinutes(10));
    }

    @Test
    void theTtlAlwaysExceedsTheRunsOwnBound() {
        // The invariant, across the range rather than at one point (the same discipline
        // J19/ICF-5 applies): whatever the timeout, the buffer outlives the run.
        for (long minutes : new long[]{1, 5, 6, 15, 60}) {
            Duration timeout = Duration.ofMinutes(minutes);
            assertThat(InMemoryRunTraceRegistry.ttlFor(timeout.toMillis()))
                    .as("timeout=%s", timeout)
                    .isGreaterThan(timeout);
        }
    }
}
