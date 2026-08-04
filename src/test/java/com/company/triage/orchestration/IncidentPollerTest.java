package com.company.triage.orchestration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    /**
     * Counts diagnoses per incident; optionally runs a hook mid-diagnosis.
     *
     * <p>The {@code engine} tag also drives {@link DiagnosisOrchestrator#isAdkActuallyActive()}
     * (FND-56), which compares the primary/fallback engine beans by <b>identity</b> — real
     * Spring wiring gives ADK a distinct {@code @Primary} bean, or (no {@code -Padk} build)
     * resolves the same sole {@code DeterministicDiagnosisEngine} bean for both. Mirrored here:
     * {@code ADK} passes two distinct lambda instances (primary != fallback →
     * {@code isAdkActuallyActive() == true}); {@code DETERMINISTIC} passes the SAME stub
     * instance for both (primary == fallback → {@code isAdkActuallyActive() == false}).
     */
    static class CountingOrchestrator extends DiagnosisOrchestrator {
        private static final DiagnosisEngine DETERMINISTIC_STUB =
                (i, sink) -> new DiagnosisResult(null, new ArrayList<>(), DiagnosisResult.Engine.DETERMINISTIC);

        final List<String> diagnosed = new ArrayList<>();
        private final DiagnosisResult.Engine engine;
        Runnable duringRun = () -> {};

        CountingOrchestrator(ServiceNowGateway snow, DiagnosisResult.Engine engine) {
            super(engine == DiagnosisResult.Engine.ADK
                        ? ((i, sink) -> new DiagnosisResult(null, new ArrayList<>(), engine))
                        : DETERMINISTIC_STUB,
                  DETERMINISTIC_STUB,
                  snow, orchestratorProps());
            this.engine = engine;
        }

        @Override
        public DiagnosisResult run(String incidentNumber) {
            diagnosed.add(incidentNumber);
            duringRun.run();
            return new DiagnosisResult(null, new ArrayList<>(List.of("fake")), engine);
        }
    }

    private static TriageProperties orchestratorProps() {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), new TriageProperties.Writeback(false), base.orchestrator(),
                base.agent(), base.trigger(), base.servicenow(), base.sumo(), base.gitlab());
    }

    private static TriageProperties pollerProps(TriageProperties.Engine engine, boolean unattendedLlmAck) {
        var base = TriagePropertiesFixture.withEngine(engine);
        return new TriageProperties(base.engine(), base.writeback(), base.orchestrator(), base.agent(),
                new TriageProperties.Trigger(new TriageProperties.Trigger.Poll(false, 30000, 10, 500, unattendedLlmAck)),
                base.servicenow(), base.sumo(), base.gitlab());
    }

    private IncidentPoller poller(FakeSnow snow, CountingOrchestrator orch) {
        return new IncidentPoller(snow, orch, pollerProps(TriageProperties.Engine.DETERMINISTIC, false));
    }

    /**
     * FND-45: K1 (this bean existing at all means poll.enabled=true) combined with
     * triage.engine=adk is exactly the unattended, programmatic LLM use the C6 ToS ruling
     * gates. Previously documented in J10's prose but not enforced or even warned about.
     */
    @Test
    void warnsWhenPollingWithAdkEngineAndNoAck() {
        Logger logger = (Logger) LoggerFactory.getLogger(IncidentPoller.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new IncidentPoller(new FakeSnow(), new CountingOrchestrator(new FakeSnow(), DiagnosisResult.Engine.ADK),
                    pollerProps(TriageProperties.Engine.ADK, false));

            assertThat(appender.list).anyMatch(e ->
                    e.getFormattedMessage().contains("C6") && e.getFormattedMessage().contains("unattended"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void noWarningWhenAckIsSetOrEngineIsDeterministic() {
        Logger logger = (Logger) LoggerFactory.getLogger(IncidentPoller.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new IncidentPoller(new FakeSnow(), new CountingOrchestrator(new FakeSnow(), DiagnosisResult.Engine.ADK),
                    pollerProps(TriageProperties.Engine.ADK, true));   // acked
            new IncidentPoller(new FakeSnow(), new CountingOrchestrator(new FakeSnow(), DiagnosisResult.Engine.DETERMINISTIC),
                    pollerProps(TriageProperties.Engine.DETERMINISTIC, false));   // not adk

            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("C6"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * FND-56: the C6 warning used to check {@code props.engine() == ADK} — the CONFIGURED
     * value — instead of whether an ADK bean is actually active. A non-{@code -Padk} build
     * with {@code triage.engine=adk} configured therefore claimed "unattended, programmatic
     * LLM use" for a run that will never contact a model — directly contradicting FND-49's
     * WARN in {@code DiagnosisOrchestrator}'s own constructor, which fires in exactly that
     * situation and says the opposite (DETERMINISTIC only, no LLM). Both could never
     * simultaneously be true. Pins the fix: {@code pollerProps} configures {@code triage.engine
     * = ADK} (what the old, buggy check looked at), but the {@code CountingOrchestrator} is
     * built with {@code Engine.DETERMINISTIC} — mirroring "config says adk, no ADK bean
     * actually wired" — so the C6 warning must NOT fire.
     */
    @Test
    void noC6WarningWhenConfigSaysAdkButNoAdkBeanIsActuallyActive() {
        Logger logger = (Logger) LoggerFactory.getLogger(IncidentPoller.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new IncidentPoller(new FakeSnow(),
                    new CountingOrchestrator(new FakeSnow(), DiagnosisResult.Engine.DETERMINISTIC),
                    pollerProps(TriageProperties.Engine.ADK, false));   // config says adk, not acked

            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("C6"));
        } finally {
            logger.detachAppender(appender);
        }
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
        snow.add("INC0010005", OffsetDateTime.now().plusSeconds(1));

        p.poll();
        snow.addWorkNote("INC0010005", "advisory note");        // simulate J5's two writes
        snow.addWorkNote("INC0010005", "advisory note 2");
        p.poll();
        p.poll();

        assertThat(snow.written).isTrue();
        assertThat(orch.diagnosed).containsExactly("INC0010005");   // once, not three times
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
        snow.add("INC0010005", OffsetDateTime.now().plusSeconds(1));

        // Re-enter poll() from inside a diagnosis: the guard must refuse the nested tick.
        orch.duringRun = p::poll;
        p.poll();

        assertThat(orch.diagnosed).containsExactly("INC0010005");   // not twice
    }

    /** In-flight is released after a run so a later legitimate retry isn't blocked forever. */
    @Test
    void inFlightIsReleasedAfterRun() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DETERMINISTIC);
        var p = poller(snow, orch);
        snow.add("INC0010005", OffsetDateTime.now().plusSeconds(1));

        p.poll();

        assertThat(p.isInFlight("INC0010005")).isFalse();
        assertThat(p.isCompleted("INC0010005")).isTrue();
    }

    /** FND-8: an unattended run must distinguish a degraded diagnosis from a live one. */
    @Test
    void degradedRunIsDetectableWithoutParsingTheTrace() {
        var snow = new FakeSnow();
        var orch = new CountingOrchestrator(snow, DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC);
        var p = poller(snow, orch);
        snow.add("INC0010005", OffsetDateTime.now().plusSeconds(1));

        p.poll();

        assertThat(orch.diagnosed).containsExactly("INC0010005");
        assertThat(new DiagnosisResult(null, List.of(), DiagnosisResult.Engine.DEGRADED_TO_DETERMINISTIC)
                .degraded()).isTrue();
        assertThat(new DiagnosisResult(null, List.of(), DiagnosisResult.Engine.ADK).degraded()).isFalse();
    }
}
