package com.company.triage.api;

import com.company.triage.config.ConnectorModeProvider;
import com.company.triage.config.DemoUiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Cosmetic, demo-only settings the static page can't know at build time (it's a plain
 * {@code index.html}, not server-rendered). Currently just the incident number to
 * pre-fill — see {@link DemoUiProperties}. Never influences a diagnosis; if this endpoint
 * were unreachable the page would simply show its placeholder text instead.
 */
@RestController
public class UiConfigController {

    private final DemoUiProperties props;
    private final ConnectorModeProvider connectorModes;

    public UiConfigController(DemoUiProperties props, ConnectorModeProvider connectorModes) {
        this.props = props;
        this.connectorModes = connectorModes;
    }

    /**
     * J23/LUH-3 — carries the connector modes so the LIVE window can show provenance from the
     * moment it opens, rather than waiting for the POST to return them on {@code DiagnosisResult}.
     *
     * <p>Connector modes are static config: they are known before the run starts, so a live
     * trace card that shows nothing until the run ends is withholding a fact it already has —
     * during the exact window an audience is watching and forming a view about what the run is
     * touching. "Waiting to be sure" is not honesty here; it is silence where a true statement
     * was available.
     *
     * <p>Purely additive: the two existing field names are unchanged, so the page's current
     * {@code cfg.defaultIncidentNumber} read keeps working untouched.
     *
     * @param connectors from {@link ConnectorModeProvider}, the same server-side source of
     *                   truth {@code DiagnosisResult.connectors} uses — one answer, not two
     */
    public record UiConfigResponse(String defaultIncidentNumber, String publicHostname,
                                   Map<String, String> connectors) {}

    @GetMapping("/api/ui-config")
    public UiConfigResponse uiConfig() {
        return new UiConfigResponse(props.defaultIncidentNumber(), props.publicHostname(),
                connectorModes.modes());
    }
}
