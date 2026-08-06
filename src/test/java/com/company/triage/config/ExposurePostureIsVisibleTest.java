package com.company.triage.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J21/NEP-3 and NEP-4 — the posture is visible, and the escape hatch is taken loudly.
 *
 * <p>NEP-4 deliberately adds no config key: anyone who genuinely needs to serve another machine
 * passes {@code --server.address=0.0.0.0} at launch, which is visible in shell history. A
 * {@code triage.demo.lan-mode} flag would create a second supported posture that must then be
 * tested, documented and defended, for a scenario the runbook does not contain.
 *
 * <p>The other half of that bargain is enforced here. An escape hatch nobody is told they took
 * is just a quieter default, so anything that is not loopback has to warn — including a value
 * nobody anticipated.
 */
class ExposurePostureIsVisibleTest {

    private static String bound(String address) throws Exception {
        MockEnvironment env = new MockEnvironment();
        if (address != null) env.setProperty("server.address", address);
        StartupBanner banner = new StartupBanner(env, new DemoUiProperties("INC0010005", ""), null);
        var m = StartupBanner.class.getDeclaredMethod("boundDescription");
        m.setAccessible(true);
        return (String) m.invoke(banner);
    }

    @Test
    void loopbackIsReportedAsLoopbackWithoutAWarning() throws Exception {
        assertThat(bound("127.0.0.1")).contains("loopback only").doesNotContain("⚠");
    }

    @Test
    void theEscapeHatchWarnsRatherThanPassingSilently() throws Exception {
        assertThat(bound("0.0.0.0"))
                .as("NEP-4: taken loudly, or it is just a quieter default")
                .contains("⚠")
                .contains("NOT loopback");
    }

    @Test
    void anUnanticipatedAddressAlsoWarnsRatherThanFallingThrough() throws Exception {
        assertThat(bound("192.168.1.50"))
                .as("a value nobody anticipated must not inherit the safe-looking branch")
                .contains("⚠");
    }

    @Test
    void anAbsentBindAddressIsTheLoudestCaseOfAll() throws Exception {
        assertThat(bound(null)).contains("⚠").contains("all interfaces");
    }
}
