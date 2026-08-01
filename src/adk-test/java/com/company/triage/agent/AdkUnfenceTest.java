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
}
