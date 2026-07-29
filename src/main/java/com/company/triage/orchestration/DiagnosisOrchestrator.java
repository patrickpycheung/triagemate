package com.company.triage.orchestration;

import com.company.triage.gateway.ServiceNowGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Owns a diagnosis run (J1): delegates to the active engine (J2), then — with no
 * human in the loop — automatically posts the advisory diagnosis back to ServiceNow
 * as TWO comments (J5): the sources first, then the first-pass diagnosis. It only
 * comments; it never reassigns, closes, or re-prioritises the ticket (J8).
 *
 * <p><b>Degrades, never crashes (FND-7).</b> If the active engine is the live ADK
 * agent and it fails to converge — most concretely {@code
 * LlmCallsLimitExceededException} tripping the J8 tool-call/LLM-call backstop, but
 * also any other model/proxy/network failure — this falls back to {@link
 * DeterministicDiagnosisEngine}, which has no LLM/network dependency and cannot fail
 * the same way. The fallback is disclosed in the trace (J8/J7), not hidden. When the
 * active engine already IS the deterministic one, there is nothing to fall back to,
 * so its exceptions propagate normally — a bug there should surface as a bug, not be
 * silently swallowed by "falling back" to itself.
 */
@Service
public class DiagnosisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisOrchestrator.class);

    private final DiagnosisEngine engine;
    private final DiagnosisEngine fallbackEngine;
    private final ServiceNowGateway serviceNow;
    private final boolean writebackEnabled;

    public DiagnosisOrchestrator(DiagnosisEngine engine,
                                 @Qualifier("deterministicDiagnosisEngine") DiagnosisEngine fallbackEngine,
                                 ServiceNowGateway serviceNow,
                                 @Value("${triage.writeback.enabled:true}") boolean writebackEnabled) {
        this.engine = engine;
        this.fallbackEngine = fallbackEngine;
        this.serviceNow = serviceNow;
        this.writebackEnabled = writebackEnabled;
    }

    public DiagnosisResult run(String incidentNumber) {
        long t0 = System.currentTimeMillis();
        DiagnosisResult result = diagnoseWithFallback(incidentNumber);

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

    private DiagnosisResult diagnoseWithFallback(String incidentNumber) {
        if (engine == fallbackEngine) {
            // Deterministic engine IS the active engine — no fallback to fall back to;
            // let a failure here propagate as the real bug it would be.
            return engine.diagnose(incidentNumber);
        }
        try {
            return engine.diagnose(incidentNumber);
        } catch (Exception e) {
            log.warn("primary engine failed for {} ({}: {}) — degrading to the deterministic engine",
                    incidentNumber, e.getClass().getSimpleName(), e.getMessage());
            DiagnosisResult fallback = fallbackEngine.diagnose(incidentNumber);
            fallback.trace().add(0, "⚠ primary engine did not converge (%s: %s) — degraded to the deterministic engine"
                    .formatted(e.getClass().getSimpleName(), e.getMessage()));
            return fallback;
        }
    }
}
