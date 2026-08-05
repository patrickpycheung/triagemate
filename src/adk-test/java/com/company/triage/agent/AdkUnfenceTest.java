package com.company.triage.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-66: the first real Copilot-served agent run died on
 * {@code JsonParseException: Unexpected character (backtick)} — the model wrapped its JSON
 * report in a markdown code fence. The instruction said "no prose" and the repair prompt
 * said "no markdown code fences"; it fenced anyway, then the FND-42 repair retry burned a
 * second Copilot call (~8s) and failed for a different reason, so the whole run degraded to
 * the deterministic engine and the latency spike measured nothing.
 *
 * <p>A prompt is a request. This is the enforcement.
 */
class AdkUnfenceTest {

    @Test
    void stripsALanguageTaggedFence() {
        assertThat(AdkDiagnosisEngine.unfence("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsABareFence() {
        assertThat(AdkDiagnosisEngine.unfence("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void toleratesSurroundingWhitespaceAndAMissingCloser() {
        assertThat(AdkDiagnosisEngine.unfence("  \n```json\n{\"a\":1}\n```  \n")).isEqualTo("{\"a\":1}");
        // Truncated response — take what's there rather than failing on the missing closer.
        assertThat(AdkDiagnosisEngine.unfence("```json\n{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    void leavesUnfencedJsonExactlyAsItIs() {
        assertThat(AdkDiagnosisEngine.unfence("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(AdkDiagnosisEngine.unfence("  {\"a\":1}  ")).isEqualTo("{\"a\":1}");
    }

    /** A backtick INSIDE the JSON (e.g. in a log-line summary) must not confuse the strip. */
    @Test
    void doesNotTouchBackticksInsideTheJsonBody() {
        String json = "{\"summary\":\"run `mvn test` first\"}";
        assertThat(AdkDiagnosisEngine.unfence(json)).isEqualTo(json);
        assertThat(AdkDiagnosisEngine.unfence("```json\n" + json + "\n```")).isEqualTo(json);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(AdkDiagnosisEngine.unfence(null)).isEmpty();
        assertThat(AdkDiagnosisEngine.unfence("   ")).isEmpty();
    }

    // --- FND-79: shapes the strict fence-strip missed ---------------------------------

    /**
     * The classic instruction-following miss: a lead-in sentence before the fence. The
     * response does not START with a backtick, so the strict strip returned it untouched and
     * the parse failed — burning the one FND-42 repair retry (~8s of stage time and a Copilot
     * call) on a response whose JSON was perfectly good.
     */
    @Test
    void stripsAFenceThatFollowsALeadInSentence() {
        String json = "{\"incidentNumber\":\"INC0010005\"}";
        assertThat(AdkDiagnosisEngine.unfence("Here is the JSON report:\n```json\n" + json + "\n```"))
                .isEqualTo(json);
    }

    /** A fence with no newline after the language tag. */
    @Test
    void stripsAFenceWithNoNewlineAfterTheOpeningTag() {
        String json = "{\"incidentNumber\":\"INC0010005\"}";
        assertThat(AdkDiagnosisEngine.unfence("```json" + json + "```")).isEqualTo(json);
    }

    /** Trailing commentary after the closing fence. */
    @Test
    void stripsTrailingProseAfterTheClosingFence() {
        String json = "{\"incidentNumber\":\"INC0010005\"}";
        assertThat(AdkDiagnosisEngine.unfence("```json\n" + json + "\n```\nLet me know if you need more."))
                .isEqualTo(json);
    }

    /** Bare prose around an unfenced object. */
    @Test
    void recoversAnUnfencedObjectSurroundedByProse() {
        String json = "{\"incidentNumber\":\"INC0010005\"}";
        assertThat(AdkDiagnosisEngine.unfence("Sure! " + json + " Hope that helps.")).isEqualTo(json);
    }

    /**
     * A response with no object at all must come back UNCHANGED, so the resulting parse error
     * is about the real problem rather than a mangled substring.
     */
    @Test
    void aResponseWithNoObjectIsLeftAlone() {
        assertThat(AdkDiagnosisEngine.unfence("I could not complete the investigation."))
                .isEqualTo("I could not complete the investigation.");
    }
}
