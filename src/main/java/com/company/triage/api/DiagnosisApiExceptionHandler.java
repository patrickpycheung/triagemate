package com.company.triage.api;

import com.company.triage.gateway.IncidentNotFoundException;
import com.company.triage.model.DiagnosisReportInvalidException;
import com.company.triage.orchestration.DiagnosisTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Objects;

/**
 * FND-48: there was no error-handling layer at all in {@code api/} — an unknown incident, a
 * J4-validation failure, or an FND-15 timeout all fell through to Spring's default error
 * body, which has no {@code report} field. {@code index.html}'s {@code render()}
 * dereferences {@code data.report.candidateSystems}, so the failure mode on stage was a raw
 * {@code TypeError} rather than a readable message.
 *
 * <p>Scoped to {@code com.company.triage.api} deliberately (FND-53). An unscoped
 * {@code @RestControllerAdvice} is application-wide, so any Spring-internal exception of a
 * mapped type would also be translated — and with the original bare-{@code
 * IllegalStateException}→404 mapping that meant an unrelated internal error could be served
 * to the client as "incident not found", with its internal message echoed out.
 *
 * <p>Deliberately narrow: four types, four statuses. Anything else still falls through to
 * Spring's default handling — a demo-quality error contract, not a general-purpose one.
 */
@RestControllerAdvice(basePackages = "com.company.triage.api")
class DiagnosisApiExceptionHandler {

    /** FND-53: {@code Map.of} throws NPE on a null value, and a bare exception message can be null. */
    private static ResponseEntity<Map<String, String>> error(HttpStatusCode status, Exception e) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    ResponseEntity<Map<String, String>> incidentNotFound(IncidentNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(DiagnosisTimeoutException.class)
    ResponseEntity<Map<String, String>> timedOut(DiagnosisTimeoutException e) {
        return error(HttpStatus.GATEWAY_TIMEOUT, e);
    }

    /**
     * FND-53: 500, not 502. The exception names an upstream ("bad gateway") failure, but by
     * the time it reaches this layer it cannot be one: an ADK-produced invalid report is
     * caught by {@code DiagnosisOrchestrator}'s FND-7 fallback and degrades to the
     * deterministic engine (HTTP 200 + degraded banner), so it never surfaces here. The only
     * way this reaches the API is the *deterministic* engine's own validator failing — pure
     * offline code with no upstream involved, i.e. our own bug. 500 is the honest status.
     */
    @ExceptionHandler(DiagnosisReportInvalidException.class)
    ResponseEntity<Map<String, String>> invalidReport(DiagnosisReportInvalidException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    /** FND-58: {@code @Pattern}-rejected {@code incidentNumber} path variable (Spring Boot 3.2+
     * translates a {@code @Validated} controller's constraint violations into this type). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, String>> invalidRequest(HandlerMethodValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid incident number"));
    }
}
