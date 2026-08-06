package com.company.triage.orchestration;

/**
 * FND-90 — both the primary engine and the deterministic fallback failed for one incident.
 *
 * <p>Exists so this case has a <b>name</b>. Previously the fallback's exception propagated raw:
 * whatever the deterministic engine happened to throw — a {@code RestClientException} from an
 * unwrapped ServiceNow call, say — surfaced as an unhandled 500, and the response said nothing
 * about the primary engine having failed first. The reader saw one arbitrary transport error
 * and no indication that two engines had been tried.
 *
 * <p>Carries <b>both</b> causes. The fallback's failure is the one that ended the run, but the
 * primary's is why the fallback was running at all, and diagnosing this afterwards needs both.
 * {@code cause} is the fallback failure (the proximate one); the primary's is
 * {@link #primaryFailure()} and is also attached as a suppressed exception so it survives any
 * generic logger that only prints a stack trace.
 *
 * <p>This is a genuine double failure and still ends the run — there is no third engine. What
 * it buys is that the run ends <em>legibly</em>: the API layer maps it to a response that says
 * both engines were tried and names each failure, rather than leaking an internal error.
 */
public class BothEnginesFailedException extends RuntimeException {

    private final transient Throwable primaryFailure;

    public BothEnginesFailedException(String incidentNumber, Throwable primaryFailure,
                                      Throwable fallbackFailure) {
        super("both engines failed for %s — primary %s: %s; fallback %s: %s".formatted(
                incidentNumber,
                primaryFailure.getClass().getSimpleName(), primaryFailure.getMessage(),
                fallbackFailure.getClass().getSimpleName(), fallbackFailure.getMessage()),
                fallbackFailure);
        this.primaryFailure = primaryFailure;
        addSuppressed(primaryFailure);
    }

    /** Why the fallback was running: the primary engine's failure. */
    public Throwable primaryFailure() { return primaryFailure; }
}
