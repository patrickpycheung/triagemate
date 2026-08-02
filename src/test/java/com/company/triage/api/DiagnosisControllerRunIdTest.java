package com.company.triage.api;

import com.company.triage.model.CandidateSystem;
import com.company.triage.model.Confidence;
import com.company.triage.model.Contact;
import com.company.triage.model.DiagnosisReport;
import com.company.triage.model.Evidence;
import com.company.triage.model.Identifiers;
import com.company.triage.model.SuggestedAssignment;
import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-009 (J11/LT4 {@code runId} protocol): {@code POST /api/diagnose/{incidentNumber}}
 * accepts an OPTIONAL {@code X-Triage-Run-Id} header. Proves three things the design doc
 * ({@code docs/design-java/concepts/J11-live-thinking-trace/README.md} §LT4) makes binding:
 *
 * <ol>
 *   <li>The header, when present, reaches {@code DiagnosisOrchestrator} via the new
 *       two-arg {@code run(String, String)} overload.</li>
 *   <li>Absent the header, the controller calls the ORIGINAL single-arg {@code
 *       run(String)} overload — not the two-arg one with a null runId — so K1's identical
 *       call path in {@code IncidentPoller} is provably unaffected by this change.</li>
 *   <li>The POST response body is byte-identical whether or not the header is sent
 *       (streaming must be purely additive — LT4's "Two corrections" section).</li>
 * </ol>
 */
@WebMvcTest(DiagnosisController.class)
class DiagnosisControllerRunIdTest {

    @Autowired private MockMvc mvc;
    @MockBean private DiagnosisOrchestrator orchestrator;

    private static DiagnosisReport sampleReport() {
        return new DiagnosisReport("INC0010005", OffsetDateTime.parse("2026-07-29T12:00:00Z"),
                "Checkout order submission fails", "Order submission", "Production",
                new Identifiers("INC-ORD-4471", null, "INC-ORD-4471"),
                List.of(new CandidateSystem("Payment Service", 0.86, List.of("e-log"))),
                new SuggestedAssignment("Payments Platform Support", Confidence.MEDIUM, List.of("e-cmdb")),
                List.of(new Evidence("e-log", "sumo", "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471", "prod/payment")),
                List.of(new Contact("Priya Nair", "priya.nair@example.com", "confluence+gitlab",
                        "edited the runbook and committed reconcile()", "https://confluence.example.com/x", "recent")),
                List.of(), List.of("user id"), "Check payment_service.reconcile()", Confidence.MEDIUM, true);
    }

    private static DiagnosisResult sampleResult() {
        return new DiagnosisResult(sampleReport(), List.of("diagnose"), DiagnosisResult.Engine.DETERMINISTIC,
                true, List.of());
    }

    @Test
    void headerAbsentCallsSingleArgRunOverload() throws Exception {
        when(orchestrator.run(anyString())).thenReturn(sampleResult());

        mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isOk());

        verify(orchestrator).run("INC0010005");
        verify(orchestrator, never()).run(anyString(), anyString());
    }

    @Test
    void headerPresentCallsTwoArgRunOverloadWithTheRunId() throws Exception {
        when(orchestrator.run(anyString(), anyString())).thenReturn(sampleResult());

        mvc.perform(post("/api/diagnose/INC0010005")
                        .header("X-Triage-Run-Id", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk());

        verify(orchestrator).run("INC0010005", "11111111-1111-1111-1111-111111111111");
        verify(orchestrator, never()).run(anyString());
    }

    @Test
    void blankHeaderIsTreatedAsAbsent() throws Exception {
        when(orchestrator.run(anyString())).thenReturn(sampleResult());

        mvc.perform(post("/api/diagnose/INC0010005").header("X-Triage-Run-Id", "   "))
                .andExpect(status().isOk());

        verify(orchestrator).run("INC0010005");
        verify(orchestrator, never()).run(anyString(), anyString());
    }

    @Test
    void responseBodyIsIdenticalWithAndWithoutTheHeader() throws Exception {
        when(orchestrator.run(eq("INC0010005"))).thenReturn(sampleResult());
        when(orchestrator.run(eq("INC0010005"), anyString())).thenReturn(sampleResult());

        String withoutHeader = mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String withHeader = mvc.perform(post("/api/diagnose/INC0010005")
                        .header("X-Triage-Run-Id", "22222222-2222-2222-2222-222222222222"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(withHeader).isEqualTo(withoutHeader);
    }
}
