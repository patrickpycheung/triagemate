package com.company.triage.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Everything under {@code triage.*}, bound and validated once at startup (FND-57).
 *
 * <p>Before this, {@code DiagnosisOrchestrator}, {@code IncidentPoller}, and
 * {@code RealServiceNowGateway} each re-read raw config independently via {@code @Value},
 * and each check only ran if its own bean happened to be constructed — so a typo'd
 * {@code triage.servicenow.write-field} booted clean all week in mock and threw for the
 * first time on stage under {@code snow-live}. Worse, {@code triage.engine} was a bare
 * {@code String}, never validated as anything: {@code agent}, {@code llm}, or {@code "Adk "}
 * (trailing space) all silently resolved to the deterministic engine with **no warning at
 * all** — reopening the exact FND-49 failure class ("narrating a live model over a scripted
 * run") that FND-49 was built to close, just via a typo instead of a missing {@code -Padk}
 * build.
 *
 * <p>Binding {@code engine} to an enum fixes that class outright: an unrecognised value now
 * fails application startup with a clear binding error, instead of silently degrading.
 * {@code @Validated} extends the same "fail before serving a single request" guarantee to
 * every other field here — {@code write-field} in particular, which used to be checked only
 * inside {@code RealServiceNowGateway}'s constructor and therefore never validated at all
 * under the default (mock) connector config.
 *
 * <p>This does NOT replace FND-49's own warning (comparing the *configured* engine against
 * which engine bean actually ended up active) — that catches a different bug class entirely
 * ({@code triage.engine=adk} is a perfectly valid value; the bug is building without
 * {@code -Padk}, so no ADK bean exists to select). Enum validation catches typos; FND-49's
 * check catches a valid-config/wrong-build mismatch. Both are needed.
 */
@ConfigurationProperties(prefix = "triage")
@Validated
public record TriageProperties(
        @NotNull Engine engine,
        @Valid @NotNull Writeback writeback,
        @Valid @NotNull Orchestrator orchestrator,
        @Valid @NotNull Agent agent,
        @Valid @NotNull Trigger trigger,
        @Valid @NotNull ServiceNow servicenow,
        @Valid @NotNull Sumo sumo,
        @Valid @NotNull GitLab gitlab
) {
    /** {@code triage.engine}. Relaxed binding is case-insensitive: {@code adk} → {@code ADK}. */
    public enum Engine { DETERMINISTIC, ADK }

    /** {@code triage.writeback.*}. */
    public record Writeback(boolean enabled) {}

    /** {@code triage.orchestrator.*} — FND-15's wall-clock engine-call bound. */
    public record Orchestrator(@Min(1) long timeoutMs) {}

    /** {@code triage.agent.*} — J8's ADK tool-call budget. */
    public record Agent(@Min(0) int maxToolCalls) {}

    /** {@code triage.trigger.poll.*} — K1 (J10). */
    public record Trigger(@Valid @NotNull Poll poll) {
        public record Poll(
                boolean enabled,
                @Min(1) long intervalMs,
                @Min(1) int batchLimit,
                @Min(1) int completedCap,
                boolean unattendedLlmAck
        ) {}
    }

    /**
     * {@code triage.servicenow.*}. {@code writeField} was previously checked at runtime
     * inside {@code RealServiceNowGateway}'s constructor (FND-51) — real, but conditional on
     * that bean existing, i.e. only under {@code connectors.servicenow=real}. The
     * {@code @Pattern} here validates it unconditionally at boot, regardless of which
     * connector mode is active.
     */
    public record ServiceNow(@Pattern(regexp = "work_notes|comments") String writeField) {}

    /** {@code triage.sumo.*} — the Sumo Logic bound (FND-20/38, J6/J8). */
    public record Sumo(
            List<String> allowedScopes,
            @Min(1) int maxResults,
            @Min(1) int maxWindowMinutes
    ) {}

    /** {@code triage.gitlab.*} — the GitLab project allowlist (FND-38, J6/J8). */
    public record GitLab(List<String> allowedProjects) {}
}
