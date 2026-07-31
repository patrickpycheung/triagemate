package com.company.triage.api;

import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisResult;
import org.springframework.web.bind.annotation.*;

/**
 * K3 — the manual trigger (J1): POST an incident number → runs the triage and
 * automatically posts the two advisory comments back to ServiceNow. No human in the loop.
 *
 * <p>This is NOT reachable from an inbound ServiceNow webhook, and none is planned
 * (FND-24): ServiceNow (cloud) cannot reach this app on a corp-network laptop — no
 * public tunnel, no MID Server — which is exactly why {@link
 * com.company.triage.orchestration.IncidentPoller} (K1, J10) exists: it polls
 * outbound instead. Both triggers call {@code DiagnosisOrchestrator.run(...)}
 * directly and meet there, not here.
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
        // FND-37/FND-50: normalization now lives in DiagnosisOrchestrator.run() itself,
        // so every trigger (K1 and K3) normalizes identically for FND-31's coalescing map.
        return orchestrator.run(incidentNumber);
    }
}
