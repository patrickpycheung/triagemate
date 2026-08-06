package com.company.triage.gateway.fixture;

import com.company.triage.gateway.ServiceNowGateway;
import com.company.triage.model.IncidentContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock gateways must be wired to the REAL {@link FixtureStore}, not to
 * {@link FixtureStore#none()}.
 *
 * <p>This exists because the unit tests could not have caught the bug it locks down. Each
 * mock has two constructors — a no-arg one for tests and a fixture-taking one for Spring —
 * and with neither annotated, Spring quietly chose the no-arg one. Every fixture lookup then
 * missed, and the app answered a fully recorded incident with "incident not found". The unit
 * tests all passed, because they call the constructors directly and never exercise the
 * container's choice.
 *
 * <p>So this asserts through the CONTEXT: resolve the bean Spring actually built and check it
 * can see a fixture on the classpath. Any future change that reintroduces constructor
 * ambiguity — another constructor, a dropped {@code @Autowired} — fails here.
 */
@SpringBootTest(properties = {
        "triage.connectors.servicenow=mock",
        "server.port=0"
})
class MockGatewaysAreWiredToTheLiveFixtureStoreTest {

    /** Recorded from the live instance; committed under src/main/resources/fixtures. */
    private static final String RECORDED_INCIDENT = "INC0010015";

    @Autowired
    private ServiceNowGateway serviceNow;

    @Test
    void theContainerBuiltMockResolvesARecordedIncident() {
        IncidentContext inc = serviceNow.getIncident(RECORDED_INCIDENT);

        assertThat(inc.number()).isEqualTo(RECORDED_INCIDENT);
        // From the real ticket — proves this came from the recording and not from the
        // hand-written J7 dataset, whose CI is "Order Portal".
        assertThat(inc.configurationItem()).isEqualTo("Delivery Hazards");
    }
}
