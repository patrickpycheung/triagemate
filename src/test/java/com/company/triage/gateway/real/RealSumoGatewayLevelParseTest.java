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
                // Exactly how the gateway sources the two inputs — INCLUDING THE KEY NAME.
                // That detail is the point of this helper: FND-85 was a one-character typo
                // (`loglevel` for `_loglevel`), and a test that takes the level as a ready-made
                // String cannot see it. Keep this reading the map, not a parameter.
                levels.add(RealSumoGateway.parseLevel(
                        f.path("_loglevel").asText(""), f.path("_raw").asText("")));
            }
            return levels;
        }
    }

    @Test
    void everyRowOfTheCapturedRealResponseResolvesToItsTrueLevel() throws Exception {
        assertThat(levelsFromCapturedResponse()).containsExactly(
                "ERROR",   // row 0 — _loglevel set, five-char level
                "ERROR",   // row 1 — _loglevel set
                "WARN",    // row 2 — _loglevel set; _raw would also work (two-space %5p padding)
                "INFO",    // row 3 — _loglevel set
                "WARN",    // row 4 — NO _loglevel (no extraction rule) → recovered from _raw
                "ERROR",   // row 5 — THE DISCRIMINATOR: _loglevel set, _raw unparseable
                "");       // row 6 — neither → unknown, never a guess
    }

    /**
     * The regression guard for FND-85, stated as its own test because the defect was a single
     * character in a key name and every other assertion here would survive it.
     *
     * <p>Row 5 carries {@code _loglevel = ERROR} and a {@code _raw} in no recognised layout.
     * Read the correct key and it resolves; read {@code loglevel} (no underscore) and the
     * structured value is missed, the {@code _raw} fallback finds nothing, and the row comes
     * back {@code ""} — which is exactly what shipped, and exactly what a merge later
     * reinstated over the fix. Nothing else in this suite fails when that happens.
     */
    @Test
    void theLevelIsReadFromUnderscoreLoglevelNotLoglevel() throws Exception {
        assertThat(levelsFromCapturedResponse().get(5))
                .as("row 5 is resolvable ONLY via the `_loglevel` key — a \"\" here means the "
                        + "gateway is reading `loglevel` without the leading underscore again")
                .isEqualTo("ERROR");
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

    /**
     * Ported from {@code RealSumoGatewayLevelTest}, which tested the superseded
     * {@code level(JsonNode, String)} helper and was removed when this worktree's
     * {@code parseLevel} won the merge (2026-08-06).
     *
     * <p>Kept because it is the only assertion that ties the parse to its CONSUMER. The
     * original defect was not that parsing was wrong in isolation — it was that the level
     * never equalled {@code "ERROR"}, so {@code DeterministicDiagnosisEngine}'s error-line
     * selector matched nothing, {@code errorToken} stayed null, and the GitLab code search
     * silently never ran on real data. A parser test that passes while that linkage is broken
     * would miss the whole bug again.
     */
    @Test
    void theEnginesErrorLineSelectorMatchesARealErrorRow() throws Exception {
        assertThat(levelsFromCapturedResponse())
                .as("DeterministicDiagnosisEngine filters on exactly \"ERROR\".equals(level)")
                .contains("ERROR");
    }
}
