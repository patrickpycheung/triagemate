package com.company.triage.api;

import com.company.triage.model.DiagnosisReportInvalidException;
import com.company.triage.orchestration.DiagnosisTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * FND-48: there was no error-handling layer at all in {@code api/} — a wrong incident
 * number, a J4-validation failure, or an FND-15 timeout all fell through to Spring's
 * default JSON error body ({@code {"timestamp":...,"status":500,...}}), which has no
 * {@code report} field. {@code index.html}'s {@code render()} immediately dereferences
 * {@code data.report.candidateSystems}, so the failure mode on stage was a raw
 * {@code TypeError: Cannot read properties of undefined} rather than a readable message.
 *
 * <p>Deliberately narrow: maps only the three exception types this app actually throws at
 * the API boundary, each to the status that best describes it. Anything else still falls
 * through to Spring's default handling — this is a demo-quality error contract, not a
 * general-purpose one.
 */
@RestControllerAdvice
class DiagnosisApiExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> incidentNotFound(IllegalStateException e) {
        // RealServiceNowGateway.getIncident throws exactly this shape for "not found".
        // A different IllegalStateException would also land here, which is an accepted
        // over-match for a hackathon-scope handler — see class javadoc.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DiagnosisTimeoutException.class)
    ResponseEntity<Map<String, String>> timedOut(DiagnosisTimeoutException e) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DiagnosisReportInvalidException.class)
    ResponseEntity<Map<String, String>> invalidReport(DiagnosisReportInvalidException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage()));
    }
}
