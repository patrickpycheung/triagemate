package com.company.triage.api;

import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisResult;
import org.springframework.web.bind.annotation.*;

/**
 * Manual trigger (J1): POST an incident number, get the structured diagnosis + trace.
 * A ServiceNow Business-Rule webhook can later call this same endpoint unchanged.
 */
@RestController
@RequestMapping("/api/diagnose")
public class DiagnosisController {

    private final DiagnosisOrchestrator orchestrator;

    public DiagnosisController(DiagnosisOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/{incidentNumber}")
    public DiagnosisResult diagnose(
            @PathVariable String incidentNumber,
            @RequestParam(name = "confirmWriteback", defaultValue = "false") boolean confirmWriteback) {
        return orchestrator.run(incidentNumber, confirmWriteback);
    }
}
