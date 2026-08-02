package com.company.triage.orchestration.trace;

/**
 * The emission interface engines call into as they make tool calls, so a caller can render
 * a live thinking trace (J11/LT1). Engines depend only on this interface, never on how (or
 * whether) steps get buffered/displayed.
 *
 * <p>Contract: {@code before} emits a row in {@link StepState#ACTIVE}; the matching
 * {@code after} or {@code onError} — carrying a {@link TraceStep} with the same
 * {@code callId} — is a <b>replacement</b> for that row, not a new one. Implementations key
 * on {@code callId} and mutate/overwrite in place.
 *
 * <p>Must be safe to implement as thread-safe: ADK/RxJava callback threads will invoke these
 * methods concurrently (STREAM-003), so implementations should not assume a single writer.
 */
public interface TraceSink {

    /** Called when a tool call starts. {@code step.state()} is {@link StepState#ACTIVE}. */
    void before(TraceStep step);

    /**
     * Called when a tool call completes successfully. {@code step} carries the same
     * {@code callId} as the matching {@link #before} call and replaces that row.
     */
    void after(TraceStep step);

    /**
     * Called when a tool call throws. {@code step} carries the same {@code callId} as the
     * matching {@link #before} call and replaces that row.
     */
    void onError(TraceStep step);

    /** No-op sink, allocates nothing. What any caller not tracking steps gets by default. */
    TraceSink NOOP = new TraceSink() {
        @Override
        public void before(TraceStep step) {
        }

        @Override
        public void after(TraceStep step) {
        }

        @Override
        public void onError(TraceStep step) {
        }
    };
}
