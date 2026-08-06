package com.company.triage.gateway.fixture;

import com.company.triage.gateway.IncidentNotFoundException;
import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The offline demo accepts ANY incident number, by replaying a recorded incident's evidence
 * under whatever was typed — and the FND-54 guard it relaxes is still reachable.
 *
 * <p>FND-54 exists because a typo on stage once produced a confident diagnosis of a bug headed
 * with an incident that does not exist. The relaxation keeps that from recurring by being
 * loud (a WARN naming both numbers) and switchable rather than by refusing, so both halves are
 * pinned here: the stand-in works when configured, and blanking the property restores the 404.
 */
class MockStandInIncidentTest {

    @Nested
    @SpringBootTest(properties = {
            "triage.connectors.servicenow=mock",
            "triage.connectors.mock-stand-in=INC0010015",
            "server.port=0"})
    class WithAStandInConfigured {

        @Autowired
        ServiceNowGateway serviceNow;

        @Test
        void anUnrecordedNumberIsDiagnosedWithTheStandInsEvidence() {
            IncidentContext inc = serviceNow.getIncident("INC0009999");

            // The number the operator typed is what the report is headed with — the run is
            // about THEIR ticket, not a silent redirect to a different one.
            assertThat(inc.number()).isEqualTo("INC0009999");
            // ...carrying the recorded incident's evidence.
            assertThat(inc.configurationItem()).isEqualTo("Delivery Hazards");
        }

        /**
         * The stand-in re-latches the fixture session, so the OTHER connectors replay the same
         * bundle. Without it they would look up the typed number, find nothing, and assemble a
         * diagnosis out of two unrelated incidents — Delivery Hazards evidence next to the J7
         * payment-reconcile precedents.
         */
        @Test
        void theRestOfTheRunFollowsTheStandInRatherThanTheTypedNumber() {
            serviceNow.getIncident("INC0009999");

            var similar = serviceNow.findSimilarIncidents(
                    new IncidentContext("INC0009999", "s", "d", "c", "cat", "sub",
                            null, null, "grp", java.util.List.of(), java.util.List.of(),
                            "Delivery Hazards", java.util.List.of()));

            assertThat(similar).isNotEmpty();
            assertThat(similar).noneMatch(s -> s.number().equals("INC0011902"));  // J7 payment precedent
        }

        /** A recorded number still resolves directly — the stand-in is a fallback, not a hijack. */
        @Test
        void aRecordedNumberIsUnaffected() {
            assertThat(serviceNow.getIncident("INC0010015").number()).isEqualTo("INC0010015");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "triage.connectors.servicenow=mock",
            "triage.connectors.mock-stand-in=",
            "server.port=0"})
    class WithNoStandIn {

        @Autowired
        ServiceNowGateway serviceNow;

        /** FND-54, intact: blank means an unknown number is still refused rather than invented. */
        @Test
        void anUnrecordedNumberIsStillRejected() {
            assertThatThrownBy(() -> serviceNow.getIncident("INC0009999"))
                    .isInstanceOf(IncidentNotFoundException.class);
        }
    }
}
