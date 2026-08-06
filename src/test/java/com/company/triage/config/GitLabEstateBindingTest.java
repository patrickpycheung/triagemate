package com.company.triage.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J30/GEB-1 and GEB-4 — the allowlist names the real estate, and says which entry is which.
 *
 * <p>The list held only {@code order-payments/payment-service}, which exists in the offline
 * demo fixture and nowhere in the real GitLab, so every real-mode code search 404'd. The real
 * path could not be obtained from a dev machine — the corp perimeter 403s identically with and
 * without a token, on the plain web root too — so this waited on someone whose network could
 * reach GitLab.
 *
 * <p>The warning's predicate is the subtle part. The demo project being PRESENT is fine and
 * deliberate: the offline fixture cites it, and removing it would break the mock walkthrough
 * that must work on stage with no network. What matters is whether a real-mode run has anything
 * real to search. A warning that fires on the shipped, correct config stops being read.
 */
class GitLabEstateBindingTest {

    private static String note(String projects, String mode) throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("triage.gitlab.allowed-projects", projects);
        env.setProperty("triage.connectors.gitlab", mode);
        var banner = new StartupBanner(env, new DemoUiProperties("INC0010005", ""), null);
        var m = StartupBanner.class.getDeclaredMethod("gitLabAllowlistNote");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var opt = (java.util.Optional<String>) m.invoke(banner);
        return opt.orElse("");
    }

    @Test
    void onlyTheDemoProjectInRealModeWarns() throws Exception {
        assertThat(note("order-payments/payment-service", "real"))
                .as("this is the shipped-before-GEB-1 state: every real code search 404s")
                .contains("ONLY the offline demo project");
    }

    @Test
    void theDemoProjectAlongsideARealOneDoesNotWarn() throws Exception {
        assertThat(note("enterprise/parcel-systems/applications/delivery-hazards,order-payments/payment-service", "real"))
                .as("keeping the demo entry is deliberate — the offline fixture cites it, and a "
                        + "warning that fires on a correct config stops being read")
                .isEmpty();
    }

    @Test
    void mockModeNeverWarnsBecauseNothingRealIsBeingSearched() throws Exception {
        assertThat(note("order-payments/payment-service", "mock")).isEmpty();
    }
}
