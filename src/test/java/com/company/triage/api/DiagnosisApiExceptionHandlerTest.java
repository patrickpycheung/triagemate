package com.company.triage.api;

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
                .thenThrow(new IllegalStateException("incident not found: INC9999999"));

        mvc.perform(post("/api/diagnose/INC9999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("incident not found")));
    }

    @Test
    void timeoutMapsTo504() throws Exception {
        when(orchestrator.run(anyString()))
                .thenThrow(new DiagnosisTimeoutException("INC0012345", 90000));

        mvc.perform(post("/api/diagnose/INC0012345"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("did not complete within")));
    }

    @Test
    void invalidReportMapsTo502() throws Exception {
        when(orchestrator.run(anyString()))
                .thenThrow(new DiagnosisReportInvalidException("INC0012345", List.of("empty candidateSystems")));

        mvc.perform(post("/api/diagnose/INC0012345"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("empty candidateSystems")));
    }
}
