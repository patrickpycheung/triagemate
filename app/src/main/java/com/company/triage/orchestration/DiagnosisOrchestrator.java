package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owns a diagnosis run (J1): delegates to the active engine (J2), then optionally
 * writes the advisory work note back to ServiceNow (J5) — guarded by config so the
 * demo never mutates a ticket unless explicitly enabled + confirmed (J8).
 */
@Service
public class DiagnosisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisOrchestrator.class);

    private final DiagnosisEngine engine;
    private final ServiceNowGateway serviceNow;

    @Value("${triage.writeback.enabled:false}")
    private boolean writebackEnabled;

    public DiagnosisOrchestrator(DiagnosisEngine engine, ServiceNowGateway serviceNow) {
        this.engine = engine;
        this.serviceNow = serviceNow;
    }

    public DiagnosisResult run(String incidentNumber, boolean confirmWriteback) {
        long t0 = System.currentTimeMillis();
        DiagnosisResult result = engine.diagnose(incidentNumber);

        if (writebackEnabled && confirmWriteback) {
            serviceNow.addWorkNote(incidentNumber, result.report().toWorkNote());
            result.trace().add("servicenow.addWorkNote → posted advisory note (confirmed)");
        } else {
            result.trace().add("writeback skipped (enabled=%s, confirmed=%s) — advisory not posted"
                    .formatted(writebackEnabled, confirmWriteback));
        }

        log.info("diagnosis for {} completed in {} ms ({} tool steps)",
                incidentNumber, System.currentTimeMillis() - t0, result.trace().size());
        return result;
    }
}
