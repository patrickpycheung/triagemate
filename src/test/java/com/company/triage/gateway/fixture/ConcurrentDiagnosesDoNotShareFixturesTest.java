package com.company.triage.gateway.fixture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two diagnoses in flight must not share a fixture latch.
 *
 * <p>{@link FixtureSession} was one volatile field, which held only while a single run was
 * ever in flight. Under two concurrent runs the second one's {@code getIncident} overwrote
 * the first's latch, and the first run's remaining connectors replayed the wrong incident's
 * bundle — a Delivery Hazards ticket citing payment-reconcile code, under the right number.
 * Measured against the running app at 12 failures in 15 interleaved rounds.
 *
 * <p>This drives the session directly rather than through HTTP so it fails deterministically
 * instead of when the timing happens to line up: each thread latches its own incident, waits
 * at a barrier for the other to latch too — which is precisely the interleaving that broke
 * it — and only then reads back.
 */
class ConcurrentDiagnosesDoNotShareFixturesTest {

    @Test
    void eachThreadSeesItsOwnIncidentEvenWhenTheyInterleave() throws Exception {
        FixtureSession session = new FixtureSession();
        int threads = 8;
        CyclicBarrier bothLatched = new CyclicBarrier(threads);

        List<Callable<String>> work = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> (Callable<String>) () -> {
                    String mine = "INC001000" + i;
                    session.set(mine);
                    bothLatched.await();          // let every other thread latch first
                    return mine + "->" + session.current();
                })
                .toList();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var results = pool.invokeAll(work);
            for (var f : results) {
                String r = f.get();
                String[] parts = r.split("->");
                assertThat(parts[1])
                        .as("thread latched %s but read back %s", parts[0], parts[1])
                        .isEqualTo(parts[0].toUpperCase());
            }
        }
    }

    /** A thread that never latched still gets an answer — the ADK fallback path. */
    @Test
    void aThreadThatNeverLatchedFallsBackToTheLastValueSet() throws Exception {
        FixtureSession session = new FixtureSession();
        session.set("INC0010015");

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            assertThat(pool.submit(session::current).get()).isEqualTo("INC0010015");
        }
    }

    @Test
    void clearDropsOnlyTheCallersLatch() {
        FixtureSession session = new FixtureSession();
        session.set("INC0010015");
        session.clear();
        // Falls back rather than returning null — a blank incident would silently miss every
        // fixture, which is the failure this whole class exists to prevent.
        assertThat(session.current()).isEqualTo("INC0010015");
    }
}
