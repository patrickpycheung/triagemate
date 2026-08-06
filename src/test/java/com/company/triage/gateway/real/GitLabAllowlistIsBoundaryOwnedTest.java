package com.company.triage.gateway.real;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriageProperties;
import com.company.triage.config.TriagePropertiesFixture;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * J18/GEC-1, GEC-2, GEC-4 — a bound is owned by the boundary it protects.
 *
 * <p>The allowlist used to be checked in {@code TriageMateTools.searchCode} only. That method
 * is not the boundary; it is <em>one caller</em>. {@code find_recent_committers} takes a
 * project id from the model in exactly the same way and went straight through unchecked — not
 * because anyone decided it should, but because a per-caller bound holds only for the callers
 * someone remembered.
 *
 * <p>{@link #everyGatewayMethodTakingAProjectRejectsOneOutsideTheAllowlist()} is the escape
 * test: it enumerates the gateway's methods by reflection rather than listing them, so a method
 * added later is covered on the day it is added, not on the day someone notices.
 */
class GitLabAllowlistIsBoundaryOwnedTest {

    private static final String ALLOWED = "order-payments/payment-service";
    private static final String NOT_ALLOWED = "someone-elses/private-repo";

    private static TriageProperties allowlist() {
        var base = TriagePropertiesFixture.deterministic();
        return new TriageProperties(base.engine(), base.writeback(), base.orchestrator(),
                base.agent(), base.trigger(), base.servicenow(), base.sumo(),
                new TriageProperties.GitLab(List.of(ALLOWED)));
    }

    private static RealGitLabGateway gateway() {
        return new RealGitLabGateway(RestClient.builder(),
                new IntegrationProperties(null, null, null,
                        new IntegrationProperties.Endpoint("http://localhost:1", null, null, "t")),
                allowlist());
    }

    @Test
    void searchCodeRejectsAProjectOutsideTheAllowlist() {
        assertThatThrownBy(() -> gateway().searchCode(NOT_ALLOWED, "anything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    /** The specific bypass this card was written for. */
    @Test
    void recentCommittersRejectsAProjectOutsideTheAllowlist() {
        assertThatThrownBy(() -> gateway().recentCommitters(NOT_ALLOWED, "src/Main.java"))
                .as("this one went unchecked while search_code was guarded — same input, same "
                        + "source (the model), different enforcement purely by oversight")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    /**
     * GEC-4. Enumerating by reflection rather than by hand is the point: a future method that
     * takes a project id is covered the moment it exists. A hand-written list would have to be
     * remembered — which is exactly the failure mode that produced the bypass.
     */
    @Test
    void everyGatewayMethodTakingAProjectRejectsOneOutsideTheAllowlist() {
        RealGitLabGateway gw = gateway();
        List<Method> projectMethods = java.util.Arrays.stream(RealGitLabGateway.class.getDeclaredMethods())
                .filter(m -> m.getParameterCount() >= 1)
                .filter(m -> m.getParameterTypes()[0] == String.class)
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .toList();

        assertThat(projectMethods)
                .as("sanity: the gateway still has project-taking methods to check")
                .isNotEmpty();

        for (Method m : projectMethods) {
            Object[] args = new Object[m.getParameterCount()];
            args[0] = NOT_ALLOWED;
            for (int i = 1; i < args.length; i++) {
                args[i] = m.getParameterTypes()[i] == String.class ? "x" : null;
            }
            assertThatThrownBy(() -> m.invoke(gw, args))
                    .as("%s must enforce the allowlist — it takes a project id, so it is a way in",
                            m.getName())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAllowlistedProjectIsNotRejectedByTheBoundItself() {
        // Reaches the HTTP layer and fails there (nothing is listening on port 1), which is
        // proof the allowlist let it through rather than short-circuiting it.
        assertThatThrownBy(() -> gateway().searchCode(ALLOWED, "term"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }
}
