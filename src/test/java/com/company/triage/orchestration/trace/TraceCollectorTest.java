package com.company.triage.orchestration.trace;

import com.company.triage.orchestration.DiagnosisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-003 (J11/LT1): direct unit coverage of {@link TraceCollector}'s segment-per-attempt
 * degrade semantics and thread-safety, independent of {@code DiagnosisOrchestratorTest}'s
 * end-to-end coverage of the same invariants.
 */
class TraceCollectorTest {

    private static TraceStep step(int seq, int attempt, String callId, StepState state) {
        return new TraceStep(seq, attempt, callId, Platform.SERVICENOW, "search_incidents",
                "Searching…", state == StepState.DONE ? "done" : null, state,
                1_000L, state == StepState.DONE ? 5L : null, DiagnosisResult.Engine.ADK);
    }

    @Test
    void beforeAfterReplaceByCallIdWithinASegment() {
        TraceCollector collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);

        sink.before(step(0, 0, "c1", StepState.ACTIVE));
        sink.after(step(0, 0, "c1", StepState.DONE));

        assertThat(collector.steps()).hasSize(1);
        assertThat(collector.steps().get(0).state()).isEqualTo(StepState.DONE);
    }

    @Test
    void forAttemptStampsTheAttemptRegardlessOfWhatTheStepCarried() {
        TraceCollector collector = new TraceCollector();
        TraceSink attempt1Sink = collector.forAttempt(1);

        // The engine itself declares attempt=0 (engines are retry-agnostic); the sink
        // view must override it to the attempt it was scoped to.
        attempt1Sink.before(step(0, 0, "c1", StepState.ACTIVE));

        assertThat(collector.steps()).singleElement()
                .extracting(TraceStep::attempt).isEqualTo(1);
    }

    @Test
    void abandonFreezesTheAttemptAndAddsAFallbackStartedBoundary() {
        TraceCollector collector = new TraceCollector();
        TraceSink primary = collector.forAttempt(0);
        primary.before(step(0, 0, "c1", StepState.ACTIVE));

        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "timed out");

        List<TraceStep> steps = collector.steps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).attempt()).isZero();
        assertThat(steps.get(0).state()).isEqualTo(StepState.ABANDONED);
        assertThat(steps.get(1).tool()).isEqualTo(TraceCollector.FALLBACK_STARTED_TOOL);
        assertThat(steps.get(1).attempt()).isEqualTo(1);
        assertThat(steps.get(1).result()).isEqualTo("timed out");
    }

    @Test
    void lateWritesToAnAbandonedAttemptAreDroppedNotMergedIntoTheNextAttempt() {
        TraceCollector collector = new TraceCollector();
        TraceSink primary = collector.forAttempt(0);
        TraceSink fallback = collector.forAttempt(1);

        primary.before(step(0, 0, "c1", StepState.ACTIVE));
        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "boom");
        fallback.before(step(0, 1, "c2", StepState.ACTIVE));

        // Orphaned primary thread (FND-15: cancellation is best-effort) keeps writing
        // after the fallback has already started.
        primary.after(step(0, 0, "c1", StepState.DONE));
        primary.before(step(1, 0, "c3", StepState.ACTIVE));

        List<TraceStep> attempt0Steps = collector.steps().stream()
                .filter(s -> s.attempt() == 0).toList();
        List<TraceStep> attempt1Steps = collector.steps().stream()
                .filter(s -> s.attempt() == 1 && !TraceCollector.FALLBACK_STARTED_TOOL.equals(s.tool())).toList();

        // The late "after" for c1 must NOT have resurrected/overwritten the frozen row —
        // it stays ABANDONED, not DONE — and the late c3 "before" must not appear at all.
        assertThat(attempt0Steps).hasSize(1);
        assertThat(attempt0Steps.get(0).state()).isEqualTo(StepState.ABANDONED);
        assertThat(attempt1Steps).hasSize(1);
        assertThat(attempt1Steps.get(0).callId()).isEqualTo("c2");
    }

    @Test
    void abandonIsIdempotentPerAttempt() {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(step(0, 0, "c1", StepState.ACTIVE));

        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "first");
        collector.abandonAndStartFallback(0, 1, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC, "second");

        long boundaries = collector.steps().stream()
                .filter(s -> TraceCollector.FALLBACK_STARTED_TOOL.equals(s.tool())).count();
        assertThat(boundaries).as("a duplicate degrade call must not double-boundary").isEqualTo(1);
    }

    @Test
    void concurrentWritesFromManyThreadsAreAllCaptured() throws Exception {
        TraceCollector collector = new TraceCollector();
        TraceSink sink = collector.forAttempt(0);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(2, TimeUnit.SECONDS);
                        sink.before(step(idx, 0, "callback-" + idx, StepState.ACTIVE));
                        sink.after(step(idx, 0, "callback-" + idx, StepState.DONE));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors.get()).isZero();
        assertThat(collector.steps()).hasSize(threads);
        assertThat(collector.steps()).allMatch(s -> s.state() == StepState.DONE);
    }
}
