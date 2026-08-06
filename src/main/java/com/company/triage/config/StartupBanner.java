package com.company.triage.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Prints the URL to open, as the LAST thing in the console.
 *
 * <p>The run scripts used to echo it before handing off to Maven, which meant it scrolled
 * away under Spring's own startup output — by the time the app was actually up, the link
 * was hundreds of lines back. {@link ApplicationReadyEvent} fires after everything else
 * (including Spring's own "Started TriageMateApplication"), so this lands at the bottom
 * where a terminal will make it clickable.
 *
 * <p>The port is read from the running web server, not from configuration: those differ
 * whenever a script falls back off port 80, or a test binds port 0. Printing the
 * configured value would risk advertising a URL nothing is listening on — the specific
 * failure this is meant to prevent.
 */
@Component
public class StartupBanner {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    private final Environment env;
    private final DemoUiProperties ui;
    /**
     * J20/STV-1: the banner reports EFFECTIVE state by asking the components that already
     * know, rather than re-reading the config that expressed the intent.
     *
     * <p>The two answers diverge exactly when it matters most. {@code triage.engine=adk} with
     * the app built without {@code -Padk} leaves no ADK bean to wire, so the deterministic
     * engine runs — and the old banner's last, framed, authoritative word still said "adk".
     * That is the FND-49/FND-56 class: config intent presented as actuality, in the one place
     * a presenter trusts without checking.
     *
     * <p>{@code ObjectProvider} because the banner must still print in a slice that has no
     * orchestrator; a missing component degrades one line, never the banner.
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.company.triage.orchestration.DiagnosisOrchestrator> orchestrator;

    public StartupBanner(Environment env, DemoUiProperties ui,
                         org.springframework.beans.factory.ObjectProvider<
                                 com.company.triage.orchestration.DiagnosisOrchestrator> orchestrator) {
        this.orchestrator = orchestrator;
        this.env = env;
        this.ui = ui;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext ctx)
                || ctx.getWebServer() == null) {
            return;   // not a servlet context (e.g. a @SpringBootTest without a web server)
        }
        int port = ctx.getWebServer().getPort();
        // Port 80 is implicit in http:// — printing it back would defeat the point of
        // defaulting to it.
        String url = port == 80 ? "http://localhost" : "http://localhost:" + port;

        // J20/STV-1: what is RUNNING, not what was asked for.
        String configuredEngine = env.getProperty("triage.engine", "deterministic");
        var orch = orchestrator.getIfAvailable();
        String engine;
        if (orch == null) {
            engine = configuredEngine + " (configured; not verified — no orchestrator in this context)";
        } else if (orch.isAdkActuallyActive()) {
            engine = "adk (live agent)";
        } else if ("adk".equalsIgnoreCase(configuredEngine)) {
            // The divergence worth shouting about: asked for the live agent, got the fallback.
            engine = "deterministic — ⚠ triage.engine=adk was requested but NO ADK engine is "
                    + "wired (build with -Padk). This run will NOT use the live agent.";
        } else {
            engine = "deterministic";
        }

        String connectors = orch == null
                ? String.join(", ",
                        "servicenow=" + env.getProperty("triage.connectors.servicenow", "mock"),
                        "confluence=" + env.getProperty("triage.connectors.confluence", "mock"),
                        "sumo=" + env.getProperty("triage.connectors.sumo", "mock"),
                        "gitlab=" + env.getProperty("triage.connectors.gitlab", "mock"))
                : orch.connectorModes().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(", "));

        log.info("");
        log.info("  ==========================================================");
        log.info("   TriageMate is ready   →   {}", url);
        hostnameLine(port).ifPresent(line -> log.info("                          →   {}", line));
        log.info("");
        log.info("   engine:     {}", engine);
        log.info("   connectors: {}", connectors);
        log.info("  ==========================================================");
        log.info("");
    }

    /**
     * The friendly-hostname URL, shown beside the localhost one.
     *
     * <p>Annotated when the name does not currently resolve, rather than hidden. Hiding it
     * would leave someone who expected the nice URL wondering whether the feature exists;
     * printing it bare would advertise a link that goes nowhere. Saying "not set up yet"
     * and naming the script that fixes it is the only version that is both visible and
     * true.
     */
    private java.util.Optional<String> hostnameLine(int port) {
        String host = ui == null ? null : ui.publicHostname();
        if (host == null || host.isBlank()) {
            return java.util.Optional.empty();
        }
        String url = "http://" + host + (port == 80 ? "" : ":" + port);
        return java.util.Optional.of(resolves(host)
                ? url
                : url + "   (not set up yet — run bin/setup-custom-domain.sh)");
    }

    /** Does the OS resolve this name? Cheap: a hosts-file lookup, no network. */
    private static boolean resolves(String host) {
        try {
            java.net.InetAddress.getByName(host);
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
