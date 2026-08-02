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
import com.company.triage.orchestration.trace.RunTraceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-011, Requirement 6 (CRITICAL, binding): {@code POST
 * /api/diagnose/{incidentNumber}}'s response must remain byte-for-byte unchanged by
 * {@code GET /api/runs/{runId}/steps}'s MERE EXISTENCE — LT4's "streaming must be
 * purely additive" binding correction (design doc §"Two corrections").
 *
 * <p>{@code DiagnosisControllerRunIdTest#responseBodyIsIdenticalWithAndWithoutTheHeader}
 * already pins "with vs without the {@code X-Triage-Run-Id} header produce the same
 * body" — but that test's {@code @WebMvcTest(DiagnosisController.class)} slice never
 * loads {@link RunStepsController} at all, so it can't catch a regression caused by the
 * NEW controller/bean simply being present in the same application context (e.g. a
 * shared {@code ObjectMapper} customization, a stray {@code @ControllerAdvice} scoping
 * change, or bean-wiring interference). This test loads BOTH controllers in one slice
 * and pins the POST response against a literal, exact expected JSON string — proving
 * coexistence with the new endpoint changes nothing about the old one's contract.
 */
@WebMvcTest(controllers = {DiagnosisController.class, RunStepsController.class})
class DiagnosisControllerPostResponseShapeRegressionTest {

    @Autowired private MockMvc mvc;
    @MockBean private DiagnosisOrchestrator orchestrator;
    @MockBean private RunTraceRegistry runTraceRegistry;

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

    private static final String EXPECTED_JSON = """
            {
              "report": {
                "incidentNumber": "INC0010005",
                "generatedAt": "2026-07-29T12:00:00Z",
                "reportedSymptom": "Checkout order submission fails",
                "affectedFunction": "Order submission",
                "environment": "Production",
                "identifiers": {
                  "correlationId": "INC-ORD-4471",
                  "errorCode": null,
                  "orderId": "INC-ORD-4471"
                },
                "candidateSystems": [
                  { "name": "Payment Service", "confidence": 0.86, "evidenceRefs": ["e-log"] }
                ],
                "suggestedAssignment": {
                  "group": "Payments Platform Support",
                  "confidence": "MEDIUM",
                  "evidenceRefs": ["e-cmdb"]
                },
                "evidence": [
                  { "id": "e-log", "source": "sumo",
                    "summary": "PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471",
                    "link": "prod/payment" }
                ],
                "suggestedContacts": [
                  { "name": "Priya Nair", "handle": "priya.nair@example.com",
                    "source": "confluence+gitlab",
                    "reason": "edited the runbook and committed reconcile()",
                    "link": "https://confluence.example.com/x", "signal": "recent" }
                ],
                "contradictingEvidence": [],
                "missingInformation": ["user id"],
                "recommendedNextAction": "Check payment_service.reconcile()",
                "confidenceOverall": "MEDIUM",
                "advisory": true
              },
              "trace": ["diagnose"],
              "engine": "DETERMINISTIC",
              "writebackPosted": true,
              "steps": [],
              "connectors": {
                "servicenow": "mock",
                "confluence": "mock",
                "sumo": "mock",
                "gitlab": "mock"
              }
            }
            """;

    @Test
    void postResponseShapeIsUnchangedWithTheNewControllerLoadedInTheSameContext() throws Exception {
        DiagnosisResult sampleResult = new DiagnosisResult(sampleReport(), List.of("diagnose"),
                DiagnosisResult.Engine.DETERMINISTIC, true, List.of());
        when(orchestrator.run(anyString())).thenReturn(sampleResult);

        mvc.perform(post("/api/diagnose/INC0010005"))
                .andExpect(status().isOk())
                .andExpect(content().json(EXPECTED_JSON, true)); // strict: no extra/missing fields
    }
}
