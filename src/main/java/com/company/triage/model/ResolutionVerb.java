package com.company.triage.model;

/**
 * What a {@link ResolutionStep} invites the reader to do (J28/PGC-3) — a <b>closed</b>
 * vocabulary of <b>observation-only</b> actions.
 *
 * <p><b>Why an enum and not a string.</b> The app is advisory: it comments, it never acts.
 * That posture is architecturally guaranteed for the <em>machine</em> — {@code
 * ServiceNowGateway} exposes no reassign/close/priority method, so there is nothing for a
 * compromised model to call. A resolution section breaks that guarantee's second leg,
 * because its whole purpose is that a <em>human</em> reads it and acts. The blast radius
 * stops being bounded by machine capability and becomes bounded by human compliance.
 *
 * <p>So the bound moves into the type. Jackson rejects any constant not listed here, which
 * makes a mutating instruction <b>unreachable in both engines</b> rather than merely
 * discouraged. A deny-list of dangerous verbs would not do: "cycle it", "give it a kick" and
 * "let it re-sync" all mean restart, and paraphrase always wins that game.
 *
 * <p><b>There is deliberately no {@code OTHER}.</b> An escape hatch is the whole vocabulary.
 *
 * <p>The boundary drawn here is <b>"go look at X" vs "go change X"</b>. Verification is
 * self-limiting — a wrong one costs minutes. Remediation changes state, and a wrong one
 * during an outage deepens the outage. J4 already drew this line implicitly: {@code
 * recommendedNextAction}'s own example is "Confirm the user has the ORDER_SUBMITTER
 * entitlement".
 */
public enum ResolutionVerb {

    /** Inspect something and report what you find. */
    CHECK,

    /** Diff two things — environments, releases, configurations. */
    COMPARE,

    /** Reproduce the failure somewhere that is not production. */
    REPRODUCE_NON_PROD,

    /** Talk to a named person or team (see J9's suggested contacts). */
    CONTACT,

    /** Read the cited runbook or known-error article before acting. */
    CONSULT_RUNBOOK,

    /** Collect more evidence — logs, traces, identifiers. */
    GATHER;

    // Deliberately absent, and each for a reason:
    //
    //   RESTART / ROLLBACK / CLEAR_CACHE / RERUN_JOB — state mutations. This is the whole
    //   point of the enum; adding one re-opens the risk the type exists to close.
    //
    //   ENABLE_DEBUG_LOGGING — looks like observation, is not. It fills disks and changes
    //   behaviour under load: a state mutation wearing an observer's coat.
}
