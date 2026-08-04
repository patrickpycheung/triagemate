package com.company.triage.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TASK-016 (J11 §LT7 — provenance chips). {@code triage.connectors.*} selects which
 * gateway bean wins per connector (each {@code Real*Gateway}/{@code Mock*Gateway} pair
 * is wired via {@code @ConditionalOnProperty(name = "triage.connectors.<name>",
 * havingValue = ...)}, {@code matchIfMissing = true} on the mock side) — but that
 * selection was never itself exposed anywhere a caller could read it back. LT7 needs to
 * show it truthfully, per-connector (FND-10: real and mock are mixable in the same run,
 * e.g. {@code servicenow=real, confluence=mock}), so a caller can't infer it from a
 * single "engine" or "writebackPosted" style flag the way the rest of {@link
 * com.company.triage.orchestration.DiagnosisResult} does.
 *
 * <p>Reads the same four {@code triage.connectors.*} keys the gateway
 * {@code @ConditionalOnProperty} annotations already key off, via {@link Environment}
 * directly rather than adding them to {@link TriageProperties} — they select which BEAN
 * gets constructed (a boot-time decision with no runtime validation target), not a
 * value any bean consumes, so they don't fit that record's "validated config values"
 * shape. Defaults every connector to {@code "mock"}, mirroring {@code
 * matchIfMissing = true} on every {@code Mock*Gateway}.
 */
@Component
public class ConnectorModeProvider {

    private static final String DEFAULT_MODE = "mock";

    private final Map<String, String> modes;

    public ConnectorModeProvider(Environment environment) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("servicenow", environment.getProperty("triage.connectors.servicenow", DEFAULT_MODE));
        m.put("confluence", environment.getProperty("triage.connectors.confluence", DEFAULT_MODE));
        m.put("sumo", environment.getProperty("triage.connectors.sumo", DEFAULT_MODE));
        m.put("gitlab", environment.getProperty("triage.connectors.gitlab", DEFAULT_MODE));
        this.modes = Map.copyOf(m);
    }

    /**
     * No-arg convenience for callers that construct {@link
     * com.company.triage.orchestration.DiagnosisOrchestrator} outside a Spring context
     * (unit tests) without wiring a real {@link Environment} — defaults every connector
     * to {@code "mock"}, the same default {@code matchIfMissing = true} already implies
     * when no {@code triage.connectors.*} key is set at all.
     */
    public ConnectorModeProvider() {
        this.modes = Map.of("servicenow", DEFAULT_MODE, "confluence", DEFAULT_MODE,
                "sumo", DEFAULT_MODE, "gitlab", DEFAULT_MODE);
    }

    /** Per-connector mode for this run: {@code "real"} or {@code "mock"}, keyed by connector name. */
    public Map<String, String> modes() {
        return modes;
    }
}
