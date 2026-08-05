package com.company.triage.gateway.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code LogEvidence.level} mapping against a CAPTURED REAL Sumo response (J29/LLF-4).
 *
 * <p>Regression guard for a real bug: this estate does not populate the structured
 * {@code loglevel} field — verified live 2026-08-05, the key is <em>absent</em> from every row
 * of a 105-message response, not blank. The gateway read it straight
 * ({@code f.path("loglevel").asText("")}), so every row arrived at {@code ""}, the engine's
 * {@code "ERROR".equals(l.level())} filter matched nothing, and both GitLab steps skipped.
 *
 * <p>{@code MockSumoGateway} populates {@code level} correctly, which is why the suite never saw
 * it — J22 built offline contract tests for REQUEST shapes only. This is the response-side pin.
 *
 * <p>The WARN and INFO rows are load-bearing: Spring Boot right-aligns the level to five chars
 * (%5p), so four-character levels carry TWO spaces before them. A single-literal-space pattern
 * (what the field report proposed) passes on ERROR and silently misses these.
 */
class RealSumoGatewayLevelParseTest {

    /** Levels mapped from the captured fixture, in row order. */
    private static List<String> levelsFromCapturedResponse() throws Exception {
        try (InputStream in = RealSumoGatewayLevelParseTest.class
                .getResourceAsStream("/fixtures/sumo-messages-real.json")) {
            assertThat(in).as("captured Sumo fixture").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            List<String> levels = new ArrayList<>();
            for (JsonNode m : root.get("messages")) {
                JsonNode f = m.path("map");
                // Exactly how the gateway sources the two inputs.
                levels.add(RealSumoGateway.parseLevel(
                        f.path("loglevel").asText(""), f.path("_raw").asText("")));
            }
            return levels;
        }
    }

    @Test
    void levelsAreRecoveredFromRawWhenTheStructuredFieldIsAbsent() throws Exception {
        assertThat(levelsFromCapturedResponse()).containsExactly(
                "ERROR",   // row 0 — no loglevel key, five-char level
                "ERROR",   // row 1 — no loglevel key, five-char level
                "WARN",    // row 2 — no loglevel key, FOUR-char level → two spaces (%5p padding)
                "INFO",    // row 3 — no loglevel key, four-char level
                "ERROR",   // row 4 — loglevel IS populated and must win over the _raw scan (INFO)
                "");       // row 5 — no known layout → unknown, never a guess
    }

    @Test
    void aPopulatedStructuredFieldWinsOverTheRawScan() {
        // An estate that configures a field-extraction rule must not be regressed to our regex.
        assertThat(RealSumoGateway.parseLevel("WARN",
                "2026-08-05 14:26:46.173 ERROR 1 --- [x] c.C : boom")).isEqualTo("WARN");
    }

    @Test
    void anUnreadableLevelIsEmptyNeverNull() {
        assertThat(RealSumoGateway.parseLevel("", "not a spring boot line at all")).isEmpty();
        assertThat(RealSumoGateway.parseLevel(null, null)).isEmpty();
        assertThat(RealSumoGateway.parseLevel("   ", "still not a log line")).isEmpty();
    }

    @Test
    void everySpringBootLevelIsRecognisedIncludingThePaddedFourCharOnes() {
        for (String level : List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE")) {
            String padded = " ".repeat(Math.max(0, 5 - level.length())) + level;
            String raw = "2026-08-05 14:26:46.173 " + padded + " 1 --- [http-nio-8080-exec-1] c.C : msg";
            assertThat(RealSumoGateway.parseLevel("", raw)).as(level).isEqualTo(level);
        }
    }
}
