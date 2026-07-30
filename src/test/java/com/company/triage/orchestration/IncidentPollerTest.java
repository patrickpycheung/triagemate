package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K1 poller behaviour. The two properties that matter most are "never diagnose the same
 * incident twice" (FND-1) and "never skip an incident" — both are asserted directly here
 * against a fake gateway, so the actual query and cursor movement are observable.
 */
class IncidentPollerTest {

    /** Fake gateway: records how it was queried, serves incidents by creation time. */
    static class FakeSnow implements ServiceNowGateway {
        final List<OffsetDateTime> queriedSince = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<NewIncident> created = new ArrayList<>();
        boolean written = false;

        void add(String number, OffsetDateTime createdAt) {
            created.add(new NewIncident(number, createdAt));
        }

        @Override
        public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime since, int limit) {
            queriedSince.add(since);
            return created.stream()
                    .filter(i -> i.createdAt().isAfter(since))
                    .sorted(java.util.Comparator.comparing(NewIncident::createdAt))
                    .limit(limit)
                    .toList();
        }

        public IncidentContext getIncident(String n) { return null; }
        public List<ResolvedIncident> findSimilarIncidents(IncidentContext c) { return List.of(); }
        public Optional<ServiceOwnership> findOwnership(String a) { return Optional.empty(); }
        public void addWorkNote(String number, String note) { notes.add(note); written = true; }
    }

    /** Counts diagnoses per incident; optionally runs a hook mid-diagnosis. */
    static class CountingOrchestrator extends DiagnosisOrchestrator {
        final List<String> diagnosed = new ArrayList<>();
        private final DiagnosisResult.Engine engine;
        Runnable duringRun = () -> {};

        CountingOrchestrator(ServiceNowGateway snow, DiagnosisResult.Engine engine) {
            super(i -> new DiagnosisResult(null, new ArrayList<>(), engine),
                  i -> new DiagnosisResult(null, new ArrayList<>(), engine),
                  snow, false, 5000);
            this.engine = engine;
        }

        @Override
        public DiagnosisResult run(String incidentNumber) {
            diagnosed.add(incidentNumber);
            duringRun.run();
            return new DiagnosisResult(null, new ArrayList<>(List.of("fake")), engine);
        }
    }

    private IncidentPoller poller(FakeSnow snow, CountingOrchestrator orch) {
        return new IncidentPoller(snow, orch, 10, 500);
    }

    /**
     * FND-1: the app's own work-note writes must never cause a re-diagnosis. The query is by
     * CREATION time, so a write (which only bumps update time) is invisible to the poller.
     */
    @Test
    void ownWorkNoteWritesNeverRetriggerDiagnosis() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);
        snow.add("INC0012345", OffsetDateTime.now().plusSeconds(1));

        p.poll();
        snow.addWorkNote("INC0012345", "advisory note");        // simulate J5's two writes
        snow.addWorkNote("INC0012345", "advisory note 2");
        p.poll();
        p.poll();

        assertThat(snow.written).isTrue();
        assertThat(orch.diagnosed).containsExactly("INC0012345");   // once, not three times
    }

    /**
     * The bug this test exists for: advancing the cursor to "now" after a batch silently
     * drops anything created WHILE the batch was processing. Here a second incident appears
     * mid-diagnosis of the first; it must still be picked up on the next tick.
     */
    @Test
    void incidentCreatedDuringProcessingIsNotSkipped() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);

        OffsetDateTime t0 = OffsetDateTime.now().plusSeconds(1);
        snow.add("INC0000001", t0);
        // While INC0000001 is being diagnosed, a new incident lands a moment later.
        orch.duringRun = () -> {
            if (!snow.created.stream().anyMatch(i -> i.number().equals("INC0000002"))) {
                snow.add("INC0000002", t0.plusNanos(500_000_000L));
            }
        };

        p.poll();     // handles INC0000001; INC0000002 arrives during it
        orch.duringRun = () -> {};
        p.poll();     // must see INC0000002

        assertThat(orch.diagnosed).containsExactly("INC0000001", "INC0000002");
    }

    /** Cursor is a high-water mark of what was HANDLED, not wall-clock now. */
    @Test
    void cursorAdvancesToNewestHandledCreationTimeNotNow() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);

        OffsetDateTime newest = OffsetDateTime.now().plusSeconds(2);
        snow.add("INC0000001", OffsetDateTime.now().plusSeconds(1));
        snow.add("INC0000002", newest);

        p.poll();

        assertThat(p.cursor()).isEqualTo(newest);   // exactly the handled max, not now()
    }

    /** A batch that handles nothing must leave the cursor alone so the window is retried. */
    @Test
    void cursorDoesNotMoveWhenNothingIsHandled() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);
        OffsetDateTime before = p.cursor();

        p.poll();   // nothing available

        assertThat(p.cursor()).isEqualTo(before);
        assertThat(orch.diagnosed).isEmpty();
    }

    /** A transient query failure must not kill the scheduler thread or move the cursor. */
    @Test
    void transientQueryFailureIsSurvivedAndCursorUnchanged() {
        var snow = new FakeSnow() {
            @Override public List<NewIncident> findIncidentsCreatedSince(OffsetDateTime s, int l) {
                throw new RuntimeException("connection reset");
            }
        };
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);
        OffsetDateTime before = p.cursor();

        p.poll();

        assertThat(p.cursor()).isEqualTo(before);
        assertThat(orch.diagnosed).isEmpty();
    }

    /** A failed diagnosis must not advance the cursor past it — it has to be retried. */
    @Test
    void failedIncidentIsRetriedAndDoesNotStopTheBatch() {
        var snow = new FakeSnow();
        var failFirstTime = new java.util.concurrent.atomic.AtomicBoolean(true);
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC) {
            @Override public DiagnosisResult run(String number) {
                diagnosed.add(number);
                if (number.equals("INC0000001") && failFirstTime.getAndSet(false)) {
                    throw new RuntimeException("boom");
                }
                return new DiagnosisResult(null, new ArrayList<>(List.of("ok")),
                        DiagnosisResult.Engine.DETERMINISTIC);
            }
        };
        var p = poller(snow, orch);
        OffsetDateTime t = OffsetDateTime.now().plusSeconds(1);
        snow.add("INC0000001", t);
        snow.add("INC0000002", t.plusNanos(100_000_000L));

        p.poll();   // 1 fails, 2 succeeds — batch continues
        assertThat(orch.diagnosed).containsExactly("INC0000001", "INC0000002");
        assertThat(p.isCompleted("INC0000001")).isFalse();
        assertThat(p.isCompleted("INC0000002")).isTrue();

        p.poll();   // 1 is retried (cursor never passed it); 2 is not re-run
        assertThat(orch.diagnosed).containsExactly("INC0000001", "INC0000002", "INC0000001");
    }

    /** Overlapping ticks are refused rather than allowed to double-process. */
    @Test
    void overlappingTickIsSkipped() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);
        snow.add("INC0012345", OffsetDateTime.now().plusSeconds(1));

        // Re-enter poll() from inside a diagnosis: the guard must refuse the nested tick.
        orch.duringRun = p::poll;
        p.poll();

        assertThat(orch.diagnosed).containsExactly("INC0012345");   // not twice
    }

    /** In-flight is released after a run so a later legitimate retry isn't blocked forever. */
    @Test
    void inFlightIsReleasedAfterRun() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);
        snow.add("INC0012345", OffsetDateTime.now().plusSeconds(1));

        p.poll();

        assertThat(p.isInFlight("INC0012345")).isFalse();
        assertThat(p.isCompleted("INC0012345")).isTrue();
    }

    /** FND-8: an unattended run must distinguish a degraded diagnosis from a live one. */
    @Test
    void degradedRunIsDetectableWithoutParsingTheTrace() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC);
        var p = poller(snow, orch);
        snow.add("INC0012345", OffsetDateTime.now().plusSeconds(1));

        p.poll();

        assertThat(orch.diagnosed).containsExactly("INC0012345");
        assertThat(new DiagnosisResult(null, List.of(), DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC)
                .degraded()).isTrue();
        assertThat(new DiagnosisResult(null, List.of(), DiagnosisResult.Engine.ADK).degraded()).isFalse();
    }
}
