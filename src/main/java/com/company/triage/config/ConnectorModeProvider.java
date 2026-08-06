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

    /**
     * FND-87 — <b>{@code @Autowired} is load-bearing, not decoration.</b>
     *
     * <p>This class has two public constructors: this one, and a no-arg convenience for unit
     * tests that hardcodes every connector to {@code "mock"}. Spring's constructor-resolution
     * rule is that a component with several constructors and <b>no</b> {@code @Autowired}
     * marker falls back to the <b>no-arg</b> one. So Spring built the all-mock instance on
     * every run and this constructor was dead code from the moment the no-arg one was added.
     *
     * <p>What that cost: the startup banner's {@code connectors:} line and the UI's LT7
     * provenance chips read from here, so a run with {@code --spring.profiles.active=real} —
     * with {@code RealConfluenceGateway} and {@code RealServiceNowGateway} genuinely wired and
     * genuinely calling live systems — announced {@code connectors: servicenow=mock,
     * confluence=mock, sumo=mock, gitlab=mock}. Verified 2026-08-06 against the live estate:
     * the condition report said {@code RealConfluenceGateway matched}, the trace said
     * {@code confluence.search(query="Delivery Hazards") → 5 page(s)}, and the banner said
     * mock.
     *
     * <p>That is FND-74's own failure mode — "the UI asserting a run used mocks while it was
     * writing to a real ticket" — reopened one layer up. FND-74 fixed the VALUE this provider
     * stores; nothing checked that Spring ever called the constructor that reads one.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ConnectorModeProvider(Environment environment) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("servicenow", normalize(environment.getProperty("triage.connectors.servicenow", DEFAULT_MODE)));
        m.put("confluence", normalize(environment.getProperty("triage.connectors.confluence", DEFAULT_MODE)));
        m.put("sumo", normalize(environment.getProperty("triage.connectors.sumo", DEFAULT_MODE)));
        m.put("gitlab", normalize(environment.getProperty("triage.connectors.gitlab", DEFAULT_MODE)));
        this.modes = Map.copyOf(m);
    }

    /**
     * FND-74: trim + lower-case, so what the UI is told matches which bean Spring actually
     * built.
     *
     * <p>{@code @ConditionalOnProperty(havingValue = "real")} matches
     * <b>case-insensitively</b>, so {@code triage.connectors.servicenow=Real} constructs
     * {@code RealServiceNowGateway} and posts two advisory comments to a live,
     * customer-visible ticket. This provider stored the raw string, and the LT7 provenance
     * chip compares it strictly — so that same run rendered as {@code fixtures}. The UI
     * asserting a run used mocks while it was writing to a real ticket is the FND-8
     * honesty-contract class in its most consequential direction.
     *
     * <p>An unrecognised value is left as-is rather than coerced to {@code mock}: silently
     * rewriting a typo to the safe-looking value is how {@code snow-live} (FND-73) went
     * unnoticed. The chip shows the odd value verbatim, which is visible; and because the
     * bean wiring only ever matches {@code real}, an unrecognised value is genuinely mock —
     * so this under-claims rather than over-claims. Fail-fast validation of the key belongs
     * with the rest of the startup work in J20.
     */
    private static String normalize(String raw) {
        return raw == null ? DEFAULT_MODE : raw.trim().toLowerCase(java.util.Locale.ROOT);
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
