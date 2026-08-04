package com.company.triage.api;

import com.company.triage.gateway.IncidentNotFoundException;
import com.company.triage.model.DiagnosisReportInvalidException;
import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FND-48: previously the three exceptions {@code DiagnosisController} could surface all
 * fell through to Spring's default error body, which has no {@code report} field — the
 * UI's {@code render()} then threw a raw {@code TypeError}. Proves each maps to a
 * status + a JSON body carrying the actual message.
 */
@WebMvcTest(DiagnosisController.class)
class DiagnosisApiExceptionHandlerTest {

    @Autowired private MockMvc mvc;
    @MockBean private DiagnosisOrchestrator orchestrator;

    @Test
    void incidentNotFoundMapsTo404() throws Exception {
        when(orchestrator.run(anyString()))
                .thenThrow(new IncidentNotFoundException("INC9999999"));

        mvc.perform(post("/api/diagnose/INC9999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("incident not found")));
    }

    /**
     * FND-53: a bare {@code IllegalStateException} must NOT be translated. It used to map to
     * 404, which was correct only by accident — the same type is thrown for a missing LLM
     * credential and for a JSON-serialization failure, so a config error could have been
     * served to the client as "incident not found".
     */
    @Test
    void bareIllegalStateExceptionIsNotMappedTo404() {
        when(orchestrator.run(anyString()))
                .thenThrow(new IllegalStateException("Missing required config: LLM_API_KEY"));

        // MockMvc rethrows anything the advice does not handle, so "not translated" shows up
        // as a propagating exception rather than a status. That IS the desired behaviour —
        // the point is that a credential error must never be dressed up as 404 not-found.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mvc.perform(post("/api/diagnose/INC0010005")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_API_KEY");
    }

    /** FND-53: {@code Map.of} NPEs on a null value, and a bare exception message can be null. */
    static class NullMessageTimeout extends DiagnosisTimeoutException {
        NullMessageTimeout() { super("INC0010005", 90000); }
        @Override public String getMessage() { return null; }
    }

    @Test
    void nullExceptionMessageDoesNotBreakTheHandler() throws Exception {
        when(orchestrator.run(anyString())).thenThrow(new NullMessageTimeout());

        mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NullMessageTimeout")));
    }

    @Test
    void timeoutMapsTo504() throws Exception {
        when(orchestrator.run(anyString()))
                .thenThrow(new DiagnosisTimeoutException("INC0010005", 90000));

        mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("did not complete within")));
    }

    @Test
    void invalidReportMapsTo500() throws Exception {
        when(orchestrator.run(anyString()))
                .thenThrow(new DiagnosisReportInvalidException("INC0010005", List.of("empty candidateSystems")));

        mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("empty candidateSystems")));
    }

    /**
     * FND-55: {@code spring.http.client.read-timeout} (20s, FND-34) bounds
     * {@code RealServiceNowGateway}'s calls INSIDE {@code engine.diagnose()} — shorter than
     * the 90s wall-clock timeout above, so it always fires first for a hung real ServiceNow
     * call, surfacing as {@code ResourceAccessException}. That type wasn't mapped, so the
     * documented 504 was reachable only via Confluence/Sumo/GitLab or a genuinely slow ADK
     * run — a real ServiceNow-side timeout, the most likely one on stage, fell through to a
     * bare 500. Now mapped to the same 504 as {@code DiagnosisTimeoutException}: both mean
     * "the app waited too long for an upstream."
     */
    @Test
    void serviceNowConnectionTimeoutMapsTo504NotBare500() throws Exception {
        when(orchestrator.run(anyString()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException(
                        "I/O error on POST request: Read timed out"));

        mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Read timed out")));
    }

    /**
     * FND-58 — and the regression test its first cut was missing.
     *
     * <p>The original fix put {@code @Validated} on {@code DiagnosisController} and mapped
     * {@code HandlerMethodValidationException}. Those two are mutually exclusive: {@code
     * @Validated} selects Spring's AOP-proxy validation path, which throws {@code
     * jakarta.validation.ConstraintViolationException} — a type the advice does not map — so
     * `POST /api/diagnose/banana` returned a bare **500**, not the documented 400. The suite
     * stayed green because no test ever posted an invalid incident number. Caught only by
     * curling a running app.
     *
     * <p>The fix is to NOT annotate the class, letting Boot 3.2+'s built-in handler-method
     * validation produce {@code HandlerMethodValidationException}. This test pins the
     * behaviour that actually matters (the status and body a client sees), so re-adding
     * {@code @Validated} fails here instead of on stage.
     */
    @Test
    void malformedIncidentNumberMapsTo400() throws Exception {
        mvc.perform(post("/api/diagnose/banana"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("invalid incident number")));

        // The orchestrator must never be reached — that's the point of the guard.
        org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never()).run(anyString());
    }

    @Test
    void wellFormedIncidentNumberIsNotRejectedByTheGuard() throws Exception {
        when(orchestrator.run(anyString())).thenThrow(new IncidentNotFoundException("INC0000001"));

        // 404 (from the orchestrator), NOT 400 — proves the pattern accepts a real number.
        mvc.perform(post("/api/diagnose/INC0000001"))
                .andExpect(status().isNotFound());
    }
}
