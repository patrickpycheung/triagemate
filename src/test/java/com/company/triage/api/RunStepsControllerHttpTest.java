package com.company.triage.api;

import com.company.triage.orchestration.trace.RunNotFoundException;
import com.company.triage.orchestration.trace.RunTraceRegistry;
import com.company.triage.orchestration.trace.TraceCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-011: HTTP-layer coverage of {@code GET /api/runs/{runId}/steps} — wiring,
 * status codes, and the {@code DiagnosisApiExceptionHandler} 404 mapping. The
 * response-shape/filtering logic itself is covered directly (no HTTP) by {@link
 * RunStepsControllerTest}.
 */
@WebMvcTest(RunStepsController.class)
class RunStepsControllerHttpTest {

    @Autowired private MockMvc mvc;
    @MockBean private RunTraceRegistry runTraceRegistry;

    @Test
    void unknownRunIdMapsToCleanNotFoundNeverA500OrAHang() throws Exception {
        when(runTraceRegistry.lookup("no-such-run")).thenThrow(new RunNotFoundException("no-such-run"));

        mvc.perform(get("/api/runs/no-such-run/steps"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("run not found")));
    }

    @Test
    void knownRunIdWithNoStepsYetReturnsEmptyAttemptsAndNotDone() throws Exception {
        TraceCollector collector = new TraceCollector();
        when(runTraceRegistry.lookup("run-A")).thenReturn(collector);

        mvc.perform(get("/api/runs/run-A/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").isArray())
                .andExpect(jsonPath("$.attempts").isEmpty())
                .andExpect(jsonPath("$.done").value(false));
    }

    @Test
    void sinceQueryParamDefaultsToEverythingWhenOmitted() throws Exception {
        TraceCollector collector = new TraceCollector();
        collector.forAttempt(0).before(new com.company.triage.orchestration.trace.TraceStep(
                0, 0, "c1", com.company.triage.orchestration.trace.Platform.SERVICENOW,
                "search_incidents", "Searching…", null,
                com.company.triage.orchestration.trace.StepState.ACTIVE, 1_000L, null,
                com.company.triage.orchestration.DiagnosisResult.Engine.ADK));
        when(runTraceRegistry.lookup("run-B")).thenReturn(collector);

        mvc.perform(get("/api/runs/run-B/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts[0].attempt").value(0))
                .andExpect(jsonPath("$.attempts[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.attempts[0].steps[0].callId").value("c1"));
    }
}
