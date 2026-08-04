package com.company.triage.api;

import com.company.triage.config.DemoUiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cosmetic, demo-only settings the static page can't know at build time (it's a plain
 * {@code index.html}, not server-rendered). Currently just the incident number to
 * pre-fill — see {@link DemoUiProperties}. Never influences a diagnosis; if this endpoint
 * were unreachable the page would simply show its placeholder text instead.
 */
@RestController
public class UiConfigController {

    private final DemoUiProperties props;

    public UiConfigController(DemoUiProperties props) {
        this.props = props;
    }

    @GetMapping("/api/ui-config")
    public DemoUiProperties uiConfig() {
        return props;
    }
}
