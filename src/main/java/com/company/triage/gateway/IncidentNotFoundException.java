package com.company.triage.gateway;

/**
 * The requested incident does not exist in the system of record.
 *
 * <p>FND-53: this used to be a bare {@code IllegalStateException}, which
 * {@code DiagnosisApiExceptionHandler} mapped to 404. That was correct only by accident —
 * the same type is thrown from at least three unrelated places (a missing LLM credential in
 * {@code AdkModelFactory}, an unparseable agent response in {@code AdkDiagnosisEngine}, a
 * JSON-serialization failure in {@code RealServiceNowGateway.jsonString}), and each was
 * shielded from the advice only by an *unrelated* broad catch elsewhere. Narrow either of
 * those catches and a credential misconfiguration would have been served to the client as
 * "404 incident not found". A dedicated type makes the mapping mean what it says.
 */
public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(String incidentNumber) {
        super("incident not found: " + incidentNumber);
    }
}
