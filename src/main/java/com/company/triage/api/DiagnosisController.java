package com.company.triage.api;

import com.company.triage.orchestration.DiagnosisOrchestrator;
import com.company.triage.orchestration.DiagnosisResult;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
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
@Validated
public class DiagnosisController {

    private final DiagnosisOrchestrator orchestrator;

    public DiagnosisController(DiagnosisOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    // FND-58: previously unconstrained — any string reached the gateway, becoming part of
    // a raw ServiceNow query under real connectors. The mock's FND-54 fix makes this safe
    // in the demo config (any non-INC0012345 number is a clean 404), but the real-connector
    // contract gap was real. Anchors the K3 shape (INC + a flexible digit count — every
    // incident number in code/tests/docs is INC followed by 6-10 digits).
    @PostMapping("/{incidentNumber}")
    public DiagnosisResult diagnose(
            @PathVariable @Pattern(regexp = "INC\\d{6,10}") String incidentNumber) {
        // FND-37/FND-50: normalization now lives in DiagnosisOrchestrator.run() itself,
        // so every trigger (K1 and K3) normalizes identically for FND-31's coalescing map.
        return orchestrator.run(incidentNumber);
    }
}
