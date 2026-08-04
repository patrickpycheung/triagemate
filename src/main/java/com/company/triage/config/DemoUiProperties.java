package com.company.triage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code triage.ui.*} — cosmetic, demo-convenience settings only. Kept deliberately
 * separate from {@link TriageProperties} (which is {@code @Validated} with every field
 * {@code @NotNull}, and constructed positionally by ~10 test files): a field here can stay
 * optional and be added without touching any of them.
 *
 * @param defaultIncidentNumber pre-fills the incident-number field in the browser UI, so
 *                              whoever is running the demo doesn't have to remember or
 *                              retype the ticket each time. Purely a convenience — never
 *                              read by any diagnosis path. Null/blank leaves the field
 *                              empty (the placeholder text still shows).
 * @param publicHostname        the friendly hostname the startup banner offers alongside
 *                              localhost, set up by {@code bin/setup-custom-domain.sh}.
 *                              Only a display string — the app never binds to it; it
 *                              resolves to 127.0.0.1 through the machine's hosts file.
 */
@ConfigurationProperties(prefix = "triage.ui")
public record DemoUiProperties(String defaultIncidentNumber, String publicHostname) {
}
