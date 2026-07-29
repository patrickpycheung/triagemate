package com.company.triage.api;

import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisResult;
import org.springframework.web.bind.annotation.*;

/**
 * Trigger (J1): POST an incident number → runs the triage and automatically posts the
 * two advisory comments back to ServiceNow. A ServiceNow Business-Rule webhook can call
 * this same endpoint on ticket creation, unchanged — no human in the loop.
 */
@RestController
@RequestMapping("/api/diagnose")
public class DiagnosisController {

    private final DiagnosisOrchestrator orchestrator;

    public DiagnosisController(DiagnosisOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/{incidentNumber}")
    public DiagnosisResult diagnose(@PathVariable String incidentNumber) {
        return orchestrator.run(incidentNumber);
    }
}
