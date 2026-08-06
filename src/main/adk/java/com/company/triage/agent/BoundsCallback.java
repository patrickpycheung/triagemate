package com.company.triage.agent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The leash (J8): the app — not the model — decides <b>which</b> tools may run and
 * <b>how many</b> times. Wired into ADK's {@code beforeToolCallback} so an
 * out-of-allowlist or over-budget call is denied before it executes.
 *
 * <p>Two independent limits, because they fail differently:
 * <ul>
 *   <li><b>Allowlist</b> — a name the app never registered must never execute, however
 *       few calls have been made. This is the "the app controls which tools are permitted"
 *       half of the J8 claim, and it is the half that was previously <i>documented but not
 *       implemented</i>: {@code allow} accepted a {@code toolName} and ignored it, so the
 *       only real bound was the call count. A model that hallucinated a tool name, or an
 *       ADK change that surfaced an unregistered tool, would have passed the leash.</li>
 *   <li><b>Budget</b> — max tool calls per run, so a chatty model can't run forever.
 *       ADK's own {@code RunConfig.setMaxLlmCalls} is a further backstop above this.</li>
 * </ul>
 *
 * <p>Per-tool result caps are applied inside {@link TriageMateTools}, not here.
 * One instance per diagnosis run.
 */
public class BoundsCallback {

    private final int maxToolCalls;
    private final Set<String> allowedTools;
    private final AtomicInteger calls = new AtomicInteger();

    /** FND-78: attempts refused (allowlist or budget) and never executed — counted
     *  separately so the trace summary can report ran-vs-refused instead of conflating them. */
    private final AtomicInteger deniedAttempts = new AtomicInteger();

    /**
     * @param maxToolCalls max successful tool invocations for this run
     * @param allowedTools exact tool names the app registered. Empty means "budget only,
     *                     allowlist not enforced" — kept so existing callers that declare
     *                     no tools behave as before; prefer passing the real set.
     */
    public BoundsCallback(int maxToolCalls, Set<String> allowedTools) {
        this.maxToolCalls = maxToolCalls;
        this.allowedTools = allowedTools == null ? Set.of() : new LinkedHashSet<>(allowedTools);
    }

    /** Budget-only. Retained for callers that don't declare their tool set. */
    public BoundsCallback(int maxToolCalls) {
        this(maxToolCalls, Set.of());
    }

    /**
     * @return true if {@code toolName} is permitted AND the run is still within budget.
     *         A call denied by the allowlist does NOT consume budget — being refused for
     *         the wrong name shouldn't also cost the run one of its allowed calls.
     */
    public boolean allow(String toolName) {
        return deny(toolName).isEmpty();   // J19/ICF-4: one implementation, two shapes
    }

    /**
     * J19/ICF-4 — why a call was refused, as a value rather than something re-derived.
     *
     * <p>{@link #allow} returned a bare boolean and callers recovered the reason by calling
     * {@link #denialReason}, which re-runs the same conditions. Two copies of one decision,
     * and the model-facing message could only ever be generic because the caller did not know
     * which had fired.
     *
     * <p>That genericness has a cost. An allowlist rejection means "not THAT tool — pick
     * another"; budget exhaustion means "no tool, ever again, on this run". Telling a model
     * the first when the second is true invites it to keep trying, and every retry is refused
     * identically until the run ends with no report.
     */
    public enum Cause {
        /** Wrong name. Other tools remain available; switching is the right response. */
        ALLOWLIST,
        /** The run is out of calls. NO tool will succeed again — conclude with what you have. */
        BUDGET
    }

    /** A refusal and its cause (J19/ICF-4). */
    public record Denial(Cause cause, String reason) {}

    /**
     * J19/ICF-4 — the typed form of {@link #allow}. Empty means permitted.
     *
     * <p>Shares one implementation with {@code allow} rather than duplicating the conditions,
     * so the two can never disagree about the same call.
     */
    public java.util.Optional<Denial> deny(String toolName) {
        if (!allowedTools.isEmpty() && (toolName == null || !allowedTools.contains(toolName))) {
            deniedAttempts.incrementAndGet();
            return java.util.Optional.of(new Denial(Cause.ALLOWLIST,
                    "tool '" + toolName + "' is not in the app's allowlist " + allowedTools));
        }
        if (calls.incrementAndGet() > maxToolCalls) {
            deniedAttempts.incrementAndGet();
            return java.util.Optional.of(new Denial(Cause.BUDGET,
                    "max tool calls (" + maxToolCalls + ") exceeded"));
        }
        return java.util.Optional.empty();
    }

    /**
     * J19/ICF-4 — what the MODEL is told, derived from the cause. Only the wording differs;
     * the decision is the same one.
     */
    public static String modelFacingMessage(Denial denial, int maxToolCalls) {
        return denial.cause() == Cause.ALLOWLIST
                ? denial.reason()
                : "the tool-call budget for this run (" + maxToolCalls + ") is exhausted; do not "
                        + "call ANY tool again — produce the JSON report now from what you have.";
    }

    /** True when this instance actually enforces an allowlist (vs budget only). */
    public boolean enforcesAllowlist() {
        return !allowedTools.isEmpty();
    }

    /** Why a call was denied — so the run trace (J8) states the reason instead of guessing. */
    public String denialReason(String toolName) {
        if (!allowedTools.isEmpty() && (toolName == null || !allowedTools.contains(toolName))) {
            return "tool '" + toolName + "' is not in the app's allowlist " + allowedTools;
        }
        return "max tool calls (" + maxToolCalls + ") exceeded";
    }

    public int used() {
        return calls.get();
    }

    /**
     * FND-78: how many tool calls actually RAN — {@link #used()} counts allowlisted
     * <i>attempts</i>, so it keeps climbing past the budget as the model retries and is
     * capped by nothing.
     *
     * <p>The final trace line reported {@code used()} as "N tool call(s) observed". With
     * {@code max-tool-calls=10} and a chatty model attempting 12, it read "12 tool call(s)
     * observed" — an operator or judge reading that against the stated budget of 10 sees the
     * J8 leash apparently violated when it in fact held perfectly: calls 11 and 12 were
     * denied and never executed. The per-attempt DENIED rows were always emitted correctly,
     * so the trace stayed reconcilable; only the summary was wrong.
     */
    public int executed() {
        return Math.min(calls.get(), maxToolCalls);
    }

    /** FND-78: attempts refused — by the allowlist or by the budget — and never executed. */
    public int deniedAttempts() {
        return deniedAttempts.get();
    }
}
