package com.company.triage.gateway.mock;

import com.company.triage.config.IntegrationProperties;
import com.company.triage.config.TriageProperties;
import com.company.triage.gateway.real.RealServiceNowGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts the two advisory work notes to the REAL ServiceNow ticket even when the run is
 * otherwise mocked.
 *
 * <p>The mocked demo reads its evidence from recorded fixtures, which is the point — no
 * network, no variability. But the write-back is the half of the story a viewer cannot take
 * on trust from a screenshot: "and it comments on the ticket" is worth much more when the
 * ticket is then opened in ServiceNow and the comments are there. So reads stay offline and
 * the WRITE goes live.
 *
 * <p>Delegates to {@link RealServiceNowGateway} rather than reimplementing the call, so the
 * demo write is the same code path as a production write — including its sys_id lookup and
 * its already-posted comparison, which is what stops a re-run stacking duplicate comments on
 * the same ticket.
 *
 * <p><b>Every failure is swallowed.</b> A demo must never break because a ticket was renamed,
 * the instance was asleep, or the laptop was offline: the diagnosis is the deliverable and
 * the comment is a bonus. Failures are logged, never rethrown — and note this is a
 * SECOND line of defence, because {@code DiagnosisOrchestrator} already contains write
 * failures and reports {@code writebackPosted=false} rather than failing the run (FND-36).
 *
 * <p>Absent or partial credentials mean this bean does not exist at all, so the offline
 * demo on a machine with no {@code secrets.properties} behaves exactly as before.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "triage.writeback.post-to-live-servicenow", havingValue = "true", matchIfMissing = true)
public class LiveWorkNotePoster {

    private static final Logger log = LoggerFactory.getLogger(LiveWorkNotePoster.class);

    private final RealServiceNowGateway live;
    /** Used only to answer "does this ticket exist?" — see {@link #tryPost}. */
    private final RestClient http;

    public LiveWorkNotePoster(RestClient.Builder builder, IntegrationProperties integrationProps,
                              TriageProperties props) {
        var sn = integrationProps.servicenow();
        boolean configured = notBlank(sn.baseUrl()) && notBlank(sn.user()) && notBlank(sn.secret());
        // Built eagerly when configured so a bad base URL surfaces at startup rather than
        // mid-demo; left null when it is not, which is the ordinary offline case.
        this.live = configured ? new RealServiceNowGateway(builder, integrationProps, props) : null;
        this.http = configured ? builder.build() : null;
        if (configured) {
            log.info("mock mode will still post advisory work notes to the live ServiceNow at {}",
                    sn.baseUrl());
        } else {
            log.info("no ServiceNow credentials — mocked work notes stay in the log "
                    + "(set triage.integrations.servicenow.* to post them for real)");
        }
    }

    /** True when a live post is even possible — lets the caller keep its own log honest. */
    public boolean isConfigured() {
        return live != null;
    }

    /**
     * Best-effort post. Returns true only if the note actually reached the instance, so the
     * caller can say which of the two things happened rather than claiming the stronger one.
     */
    public boolean tryPost(String incidentNumber, String workNote) {
        if (live == null) return false;
        try {
            // Checked FIRST, because addWorkNote returns normally for an incident that does
            // not exist — it logs and gives up. Delegating blind therefore reported success
            // for a ticket nothing was written to, and the caller announced "posted to the
            // LIVE ticket". The stand-in feature makes that the COMMON case, not a rare one:
            // any invented number a presenter types reaches here.
            if (!exists(incidentNumber)) {
                log.info("live work-note post skipped — {} does not exist on the instance",
                        incidentNumber);
                return false;
            }
            live.addWorkNote(incidentNumber, workNote);
            return true;
        } catch (RuntimeException e) {
            // Includes the case the demo will actually hit: a stand-in number
            // (triage.connectors.mock-stand-in) that exists nowhere in ServiceNow.
            log.warn("live work-note post to {} failed — continuing ({}: {})",
                    incidentNumber, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /** One cheap lookup: does the instance hold this incident number at all? */
    private boolean exists(String incidentNumber) {
        var body = http.get()
                .uri(uri -> uri.path("/api/now/table/incident")
                        .queryParam("sysparm_query", "number=" + incidentNumber)
                        .queryParam("sysparm_fields", "sys_id")
                        .queryParam("sysparm_limit", 1)
                        .build())
                .retrieve()
                .body(com.fasterxml.jackson.databind.JsonNode.class);
        return body != null && body.path("result").isArray() && !body.path("result").isEmpty();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
