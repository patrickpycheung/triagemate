package com.company.triage.orchestration.trace;

import com.company.triage.orchestration.DiagnosisResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-run, thread-safe buffer for the live thinking trace (J11/LT1), segmented by
 * engine attempt — {@code 0} for the primary engine call, {@code 1} for an FND-7
 * fallback retry. Implements the "Three invariants" block of
 * {@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §LT1:
 *
 * <ol>
 *   <li>One sink per engine call, segment per attempt — {@link #forAttempt(int)} hands
 *       each engine call its own {@link TraceSink} view, tagging every step it emits
 *       with that attempt regardless of what the engine itself put there (engines have
 *       no notion of retry state; only the orchestrator does).</li>
 *   <li>Callers (currently {@code DiagnosisOrchestrator}) are responsible for carrying
 *       {@link #steps()} forward into every {@code DiagnosisResult} reconstruction.</li>
 *   <li>Thread-safe — the timed-out primary's virtual thread is not killed (FND-15
 *       cancellation is best-effort) and may keep calling its sink after a fallback has
 *       already started; {@link #record} and {@link #abandonAndStartFallback} are safe
 *       to invoke concurrently, including from ADK/RxJava callback threads later
 *       (STREAM-003).</li>
 * </ol>
 *
 * <p><b>Degrade semantics.</b> On an FND-7 degrade, {@link #abandonAndStartFallback}
 * freezes the failed attempt's segment — every row already captured is kept (never
 * deleted; deleting emitted data breaches the honesty contract this whole card is built
 * around) but re-tagged {@link StepState#ABANDONED} — and records a synthetic
 * {@code FALLBACK_STARTED} boundary row ahead of the attempt about to begin. Any write
 * that still lands for the abandoned attempt afterward (the orphaned thread) is silently
 * dropped by {@link #record}, so it can never corrupt the new attempt's sequence.
 */
public final class TraceCollector {

    /** Sentinel {@code tool} value identifying the synthetic degrade-boundary row. */
    public static final String FALLBACK_STARTED_TOOL = "__fallback_started__";

    /** attempt -> (callId -> current row). A nested concurrent map gives both the
     *  "replace by callId" mutable-row semantic and safe concurrent writers. */
    private final ConcurrentMap<Integer, ConcurrentMap<String, TraceStep>> segments = new ConcurrentHashMap<>();

    /** Attempts whose segment has been frozen — writes to these are dropped, not stored. */
    private final Set<Integer> abandonedAttempts = ConcurrentHashMap.newKeySet();

    /** Synthetic boundary rows, keyed by the attempt they introduce. */
    private final List<TraceStep> boundaries = new CopyOnWriteArrayList<>();

    /**
     * Guards the "mark attempt abandoned + freeze its existing rows" sequence in {@link
     * #abandonAndStartFallback} against the "check abandoned + insert" sequence in {@link
     * #record}. Both are check-then-act over the same two data structures
     * ({@link #abandonedAttempts} and {@link #segments}); without a shared lock a writer can
     * observe "not abandoned" microseconds before the freeze pass runs and then complete its
     * {@code put} microseconds after the freeze pass finishes, landing an un-frozen row in a
     * segment that's supposed to be dead (the exact FND-15 orphaned-virtual-thread scenario
     * this class's javadoc calls out). {@link #segments} and {@link #abandonedAttempts} stay
     * {@code ConcurrentHashMap}-backed for {@link #steps()}'s lock-free reads and for the
     * per-callId replace semantics within a live segment; only the abandon/record boundary
     * needs mutual exclusion, so the lock is scoped to these two methods, not the whole class.
     */
    private final Object abandonLock = new Object();

    /**
     * A {@link TraceSink} view over this collector that stamps every step it receives
     * with {@code attempt}, overriding whatever attempt the step itself carried.
     */
    public TraceSink forAttempt(int attempt) {
        return new AttemptSink(attempt);
    }

    /**
     * Freeze {@code abandonedAttempt}'s segment (rows kept, re-tagged {@link
     * StepState#ABANDONED}) and append a {@code FALLBACK_STARTED} boundary row
     * announcing {@code nextAttempt}. Idempotent per {@code abandonedAttempt}: calling
     * this twice for the same attempt only freezes it once (later writes are already
     * being dropped by {@link #record} from the first call onward).
     *
     * @param abandonedAttempt the attempt (e.g. {@code 0}, the primary) that failed
     * @param nextAttempt      the attempt (e.g. {@code 1}) about to start
     * @param degradedEngine   the {@link DiagnosisResult.Engine} label the run will
     *                         carry once the fallback completes — attached to the
     *                         boundary row for downstream engine/attempt identity
     * @param reason           short human-readable failure reason, carried as the
     *                         boundary row's {@code result}
     */
    public void abandonAndStartFallback(int abandonedAttempt, int nextAttempt,
                                        DiagnosisResult.Engine degradedEngine, String reason) {
        // Mark-abandoned + freeze must be atomic with respect to record()'s check-then-insert
        // below — otherwise a writer that checked "not abandoned" just before add() runs can
        // still land its put() after the freeze loop finishes, leaving an un-frozen row in a
        // segment that's supposed to be dead. See the abandonLock javadoc above.
        synchronized (abandonLock) {
            if (!abandonedAttempts.add(abandonedAttempt)) {
                return; // already frozen — a duplicate degrade call must not double-freeze/double-boundary
            }
            ConcurrentMap<String, TraceStep> segment = segments.get(abandonedAttempt);
            if (segment != null) {
                for (Map.Entry<String, TraceStep> entry : segment.entrySet()) {
                    TraceStep s = entry.getValue();
                    entry.setValue(new TraceStep(s.seq(), s.attempt(), s.callId(), s.platform(), s.tool(),
                            s.label(), s.result(), StepState.ABANDONED, s.startedAtEpochMs(), s.durationMs(),
                            s.engine()));
                }
            }
        }
        boundaries.add(new TraceStep(0, nextAttempt, "fallback-started-" + nextAttempt,
                Platform.TRIAGEMATE, FALLBACK_STARTED_TOOL, "Falling back to the deterministic engine",
                reason, StepState.DONE, System.currentTimeMillis(), 0L, degradedEngine));
    }

    /** Called by every {@link AttemptSink} callback. Late writes to an abandoned attempt
     *  are dropped so an un-killable orphaned thread can never corrupt a later attempt. */
    private void record(TraceStep step) {
        // Must be atomic with abandonAndStartFallback's mark+freeze sequence — see abandonLock
        // javadoc above. Without this lock, a check here can pass (not abandoned yet) and the
        // put() below can still land after the freeze pass has already swept the segment.
        synchronized (abandonLock) {
            if (abandonedAttempts.contains(step.attempt())) {
                return;
            }
            segments.computeIfAbsent(step.attempt(), k -> new ConcurrentHashMap<>()).put(step.callId(), step);
        }
    }

    /**
     * Flattened, immutable view for {@code DiagnosisResult.steps()}: attempts in
     * ascending order, each segment's rows ordered by {@code seq}, with any
     * {@code FALLBACK_STARTED} boundary placed immediately before the attempt segment
     * it introduces.
     */
    public List<TraceStep> steps() {
        Set<Integer> attempts = new TreeSet<>(segments.keySet());
        for (TraceStep boundary : boundaries) {
            attempts.add(boundary.attempt());
        }
        List<TraceStep> out = new ArrayList<>();
        for (int attempt : attempts) {
            for (TraceStep boundary : boundaries) {
                if (boundary.attempt() == attempt) {
                    out.add(boundary);
                }
            }
            ConcurrentMap<String, TraceStep> segment = segments.get(attempt);
            if (segment != null) {
                List<TraceStep> rows = new ArrayList<>(segment.values());
                rows.sort(Comparator.comparingInt(TraceStep::seq));
                out.addAll(rows);
            }
        }
        return List.copyOf(out);
    }

    private final class AttemptSink implements TraceSink {
        private final int attempt;

        AttemptSink(int attempt) {
            this.attempt = attempt;
        }

        private TraceStep stamp(TraceStep step) {
            return step.attempt() == attempt ? step : new TraceStep(step.seq(), attempt, step.callId(),
                    step.platform(), step.tool(), step.label(), step.result(), step.state(),
                    step.startedAtEpochMs(), step.durationMs(), step.engine());
        }

        @Override
        public void before(TraceStep step) {
            record(stamp(step));
        }

        @Override
        public void after(TraceStep step) {
            record(stamp(step));
        }

        @Override
        public void onError(TraceStep step) {
            record(stamp(step));
        }
    }
}
