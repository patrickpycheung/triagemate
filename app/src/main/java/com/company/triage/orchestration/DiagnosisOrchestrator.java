package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owns a diagnosis run (J1): delegates to the active engine (J2), then — with no
 * human in the loop — automatically posts the advisory diagnosis back to ServiceNow
 * as TWO comments (J5): the sources first, then the first-pass diagnosis. It only
 * comments; it never reassigns, closes, or re-prioritises the ticket (J8).
 */
@Service
public class DiagnosisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisOrchestrator.class);

    private final DiagnosisEngine engine;
    private final ServiceNowGateway serviceNow;
    private final boolean writebackEnabled;

    public DiagnosisOrchestrator(DiagnosisEngine engine, ServiceNowGateway serviceNow,
                                 @Value("${triage.writeback.enabled:true}") boolean writebackEnabled) {
        this.engine = engine;
        this.serviceNow = serviceNow;
        this.writebackEnabled = writebackEnabled;
    }

    public DiagnosisResult run(String incidentNumber) {
        long t0 = System.currentTimeMillis();
        DiagnosisResult result = engine.diagnose(incidentNumber);

        if (writebackEnabled) {
            // Automatic, advisory, two comments — sources first so the diagnosis is auditable.
            serviceNow.addWorkNote(incidentNumber, result.report().toSourcesNote());
            result.trace().add("servicenow.addWorkNote → posted 'Sources consulted' comment");
            serviceNow.addWorkNote(incidentNumber, result.report().toDiagnosisNote());
            result.trace().add("servicenow.addWorkNote → posted 'First-pass diagnosis' comment (advisory)");
        } else {
            result.trace().add("writeback disabled (triage.writeback.enabled=false) — comments not posted");
        }

        log.info("diagnosis for {} completed in {} ms ({} steps, writeback={})",
                incidentNumber, System.currentTimeMillis() - t0, result.trace().size(), writebackEnabled);
        return result;
    }
}
