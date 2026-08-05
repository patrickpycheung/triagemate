package com.company.triage.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    public record ServiceNow(
            @Pattern(regexp = "work_notes|comments") String writeField,
            /**
             * J26: incident {@code state} values that count as "resolved" for the
             * similar-incident search, as an encoded-query {@code IN} list. Out-of-the-box
             * ServiceNow is {@code 6} (Resolved) and {@code 7} (Closed), which is the default
             * — but these are configurable per instance, and this was previously the literal
             * {@code stateIN6,7} baked into the query. On an instance with customised states
             * that hardcoding returns zero rows for every incident, with nothing in the trace
             * to say why.
             */
            String resolvedStates,
            /**
             * J26: minimum similarity ({@code 0..1}) a candidate must score to be reported.
             * The default admits a same-CI match on its own — see
             * {@link com.company.triage.gateway.SimilarIncidentRanker} for the weights.
             */
            @DecimalMin("0.0") @DecimalMax("1.0") double similarityFloor,
            /** J26: how many ranked similar incidents to report at most. */
            @Min(1) int maxSimilar,
            /**
             * Operator-curated "these two tickets are the same problem" links, keyed by the
             * incident being triaged:
             * {@code triage.servicenow.similar-incidents.INC0010010[0]=INC0010012}.
             *
             * <p>Retrieval + {@link com.company.triage.gateway.SimilarIncidentRanker} can only
             * find what the instance's own text search and CMDB support. When a human already
             * KNOWS two tickets are duplicates, saying so beats any heuristic — and it is the
             * difference between the triager seeing "we resolved this last week, close it" and
             * seeing nothing. Pins are additive and rank above every scored hit.
             *
             * <p>Unset means "no pins", never null.
             */
            java.util.Map<String, List<String>> similarIncidents
    ) {
        /**
         * Defaults applied here rather than only in {@code application.yml} so a partial
         * override (or a test fixture constructing this directly) cannot silently produce
         * {@code resolvedStates=null} → a malformed encoded query, or {@code maxSimilar=0} →
         * a search that always reports nothing. Both of those are the FND-57 shape: a config
         * fault that presents as an empty result rather than as an error.
         */
        public ServiceNow {
            if (resolvedStates == null || resolvedStates.isBlank()) resolvedStates = "6,7";
            if (similarityFloor <= 0.0) similarityFloor = 0.25;
            if (maxSimilar <= 0) maxSimilar = 5;
            // Same FND-57 shape as the three above: a null map here would NPE on the first
            // pin lookup rather than simply meaning "no pins configured".
            similarIncidents = similarIncidents == null ? java.util.Map.of() : similarIncidents;
        }

        /** Pinned similar-incident numbers for {@code number}, never null. */
        public List<String> pinsFor(String number) {
            if (number == null) return List.of();
            return similarIncidents.getOrDefault(number, List.of());
        }
    }

    /** {@code triage.sumo.*} — the Sumo Logic bound (FND-20/38, J6/J8). */
    public record Sumo(
            String sourceCategoryPattern,
            java.util.Map<String, String> sourceCategoryOverrides,
            String index,
            List<String> allowedEnvironments,
            @Min(1) int maxResults,
            @Min(1) int maxWindowMinutes
    ) {
        /**
         * Composes the {@code _sourceCategory} for a project + environment. The model never
         * supplies a category directly — it supplies these two fields and the app builds the
         * rest, so an off-convention or wildcard category is unrepresentable rather than
         * merely rejected. A per-project override wins over the default pattern.
         */
        public String sourceCategoryFor(String projectSlug, String environment) {
            String pattern = sourceCategoryOverrides == null
                    ? sourceCategoryPattern
                    : sourceCategoryOverrides.getOrDefault(projectSlug, sourceCategoryPattern);
            return pattern
                    .replace("{project}", projectSlug == null ? "" : projectSlug)
                    .replace("{environment}", environment == null ? "" : environment);
        }
    }

    /** {@code triage.gitlab.*} — the GitLab project allowlist (FND-38, J6/J8). */
    public record GitLab(List<String> allowedProjects) {}
}
