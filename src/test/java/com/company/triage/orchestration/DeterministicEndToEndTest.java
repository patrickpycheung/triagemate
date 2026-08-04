package com.company.triage.orchestration;

import com.company.triage.orchestration.trace.StepState;
import com.company.triage.orchestration.trace.TraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A genuine, full-Spring-context, real-HTTP round trip through the deterministic engine —
 * the thing every other {@code Diagnosis*ControllerTest} in this suite deliberately does
 * NOT cover, because they all {@code @MockBean DiagnosisOrchestrator} (or narrower) to stay
 * fast. Every connector defaults to {@code mock} (see {@code application.yml}), so this is
 * still fully offline and deterministic — no live network, no flakiness, safe to run in any
 * CI or on any laptop with zero setup. That combination (real wiring, zero external
 * dependencies) is exactly why the deterministic path has NO excuse not to have an
 * end-to-end test: everything it touches is already a fixture.
 *
 * <p>This exists because a live demo surfaced two J11 UI symptoms (missing step-by-step
 * animation, a dev-only demo section visible on load) that turned out to be front-end-only —
 * but the investigation exposed that nothing before this test actually proved the real
 * {@link DeterministicDiagnosisEngine} → {@link DiagnosisOrchestrator} → {@code
 * DiagnosisController} chain produces a well-formed {@code steps} array over real HTTP. Every
 * existing controller test only proves the controller correctly relays whatever a mocked
 * orchestrator hands it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeterministicEndToEndTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    /** Seeded mock-connector incident used throughout the demo/docs (DEMO-RUNBOOK.md). */
    private static final String SEEDED_INCIDENT = "INC0010005";

    @Test
    void deterministicEngineProducesAWellFormedStepsArrayOverRealHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Triage-Run-Id", UUID.randomUUID().toString());
        ResponseEntity<DiagnosisResult> response = rest.postForEntity(
                "http://localhost:" + port + "/api/diagnose/" + SEEDED_INCIDENT,
                new HttpEntity<>(headers),
                DiagnosisResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DiagnosisResult result = response.getBody();
        assertThat(result).isNotNull();

        // The engine that actually ran, not a mock's opinion of it.
        assertThat(result.engine()).isEqualTo(DiagnosisResult.Engine.DETERMINISTIC);
        assertThat(result.degraded()).isFalse();

        // The report itself — proves the real DeterministicDiagnosisEngine ran the full
        // pipeline (mock ServiceNow/Confluence/Sumo/GitLab gateways included), not a stub.
        assertThat(result.report()).isNotNull();
        assertThat(result.report().incidentNumber()).isEqualTo(SEEDED_INCIDENT);
        assertThat(result.report().candidateSystems()).isNotEmpty();

        // steps (J11/LT1): the engine's own tool calls (TASK-008) — this is the exact
        // array LT3's replay renderer paces through. Assert it is real, ordered, and
        // internally consistent. `trace` is a strict superset: it also carries the two
        // write-back confirmation lines DiagnosisOrchestrator appends AFTER the engine
        // returns (writeback is not part of the engine's diagnose() call), so the two
        // lists are deliberately NOT the same size — only `trace.size() >= steps.size()`.
        assertThat(result.steps()).isNotEmpty();
        assertThat(result.trace().size()).isGreaterThanOrEqualTo(result.steps().size());

        int expectedSeq = 0;
        for (TraceStep step : result.steps()) {
            assertThat(step.seq()).as("seq must be strictly increasing within attempt 0")
                    .isEqualTo(expectedSeq++);
            assertThat(step.attempt()).as("a clean deterministic run never touches attempt 1")
                    .isZero();
            assertThat(step.callId()).isNotBlank();
            assertThat(step.platform()).isNotNull();
            assertThat(step.label()).isNotBlank();
            // A resolved step (this is a synchronous engine — nothing is left ACTIVE/PENDING
            // by the time the HTTP response is built) always carries a result and a real,
            // non-negative measured duration — never null, which is what would let LT3's
            // renderer silently fall back to its "unresolved" branch.
            assertThat(step.state()).isEqualTo(StepState.DONE);
            assertThat(step.result()).isNotBlank();
            assertThat(step.durationMs()).isNotNull();
            assertThat(step.durationMs()).isGreaterThanOrEqualTo(0);
            assertThat(step.engine()).isEqualTo(DiagnosisResult.Engine.DETERMINISTIC);
        }

        // connectors (J11/LT7): default profile is mock end to end — this is what the
        // provenance chip renders as "connectors: all fixtures".
        assertThat(result.connectors())
                .containsEntry("servicenow", "mock")
                .containsEntry("confluence", "mock")
                .containsEntry("sumo", "mock")
                .containsEntry("gitlab", "mock");
    }

    @Test
    void sameEndpointWithNoRunIdHeaderStillReturnsTheSameStepsShape() {
        // TASK-009's binding guarantee: the runId header is purely additive. A caller that
        // never sends it (like K1's IncidentPoller) must get an identical, fully-populated
        // response — not a degraded one missing `steps`.
        ResponseEntity<DiagnosisResult> response = rest.postForEntity(
                "http://localhost:" + port + "/api/diagnose/" + SEEDED_INCIDENT,
                null,
                DiagnosisResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DiagnosisResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.engine()).isEqualTo(DiagnosisResult.Engine.DETERMINISTIC);
        assertThat(result.steps()).isNotEmpty();
    }
}
