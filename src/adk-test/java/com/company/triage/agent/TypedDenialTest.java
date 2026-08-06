package com.company.triage.agent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J19/ICF-4 — a denial carries its cause, and only the model-facing wording differs.
 *
 * <p>The two refusals mean opposite things to a model. An allowlist rejection is "not THAT
 * tool — pick another", and other tools remain available. Budget exhaustion is "no tool, ever
 * again, on this run". Telling a model the first when the second is true invites it to keep
 * trying; every retry is refused identically, and the run can end with no report at all.
 *
 * <p>Two constraints these pin. {@code denialReason} keeps its exact text, because J11/LT2
 * carries it into the DENIED row's {@code result} and the two causes must stay
 * distinguishable in the TRACE. And {@code allow} keeps working, delegating to {@code deny},
 * so the boolean and typed forms can never disagree about the same call.
 */
class TypedDenialTest {

    private static BoundsCallback bounds(int budget) {
        return new BoundsCallback(budget, Set.of("get_incident", "search_logs"));
    }

    @Test
    void anUnknownToolIsDeniedByTheAllowlistAndDoesNotSpendBudget() {
        BoundsCallback b = bounds(2);

        var denial = b.deny("rm_minus_rf");
        assertThat(denial).isPresent();
        assertThat(denial.get().cause()).isEqualTo(BoundsCallback.Cause.ALLOWLIST);

        assertThat(b.deny("get_incident")).as("budget was untouched by the refusal").isEmpty();
        assertThat(b.deny("get_incident")).isEmpty();
    }

    @Test
    void anExhaustedBudgetIsTypedAsBudgetNotAsAnAllowlistMiss() {
        BoundsCallback b = bounds(1);
        assertThat(b.deny("get_incident")).isEmpty();

        var denial = b.deny("get_incident");
        assertThat(denial).isPresent();
        assertThat(denial.get().cause())
                .as("the tool is perfectly legal — the RUN is out of calls")
                .isEqualTo(BoundsCallback.Cause.BUDGET);
    }

    @Test
    void theModelIsToldToStopEntirelyWhenTheBudgetIsGone() {
        var denial = new BoundsCallback.Denial(BoundsCallback.Cause.BUDGET, "max tool calls (8) exceeded");

        assertThat(BoundsCallback.modelFacingMessage(denial, 8))
                .contains("do not call ANY tool again")
                .contains("produce the JSON report now");
    }

    @Test
    void theModelIsToldToSwitchToolsOnAnAllowlistMiss() {
        var denial = new BoundsCallback.Denial(BoundsCallback.Cause.ALLOWLIST, "tool 'x' is not in the app's allowlist [a]");

        assertThat(BoundsCallback.modelFacingMessage(denial, 8))
                .as("other tools remain available — telling it to stop entirely would waste the run")
                .isEqualTo(denial.reason())
                .doesNotContain("do not call ANY tool");
    }

    @Test
    void denialReasonKeepsItsExactTextForTheTrace() {
        BoundsCallback b = bounds(5);
        assertThat(b.denialReason("nope"))
                .as("J11/LT2 carries this into the DENIED row's result — the two causes must "
                        + "stay distinguishable in the trace, not just to the model")
                .contains("is not in the app's allowlist");
    }

    @Test
    void theBooleanAndTypedFormsCannotDisagree() {
        BoundsCallback b = bounds(1);
        assertThat(b.allow("get_incident")).isTrue();
        assertThat(b.deny("get_incident")).as("budget of 1 is now spent").isPresent();
    }
}
