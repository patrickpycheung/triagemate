package com.company.triage.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * J23/LUH-2 and LUH-4 — the honesty contract belongs to the UI STATE, not to a renderer.
 *
 * <p>These read the shipped {@code index.html} because that file IS the UI: there is no build
 * step and no component test harness. Asserting on its source is the only automated way to keep
 * a claim from drifting back in, and drift is exactly what happened — every renderer that
 * existed when a rule was written obeys it; the states added later did not.
 */
class UiHonestyContractTest {

    private static String ui() throws Exception {
        return Files.readString(Path.of("src/main/resources/static/index.html"));
    }

    @Test
    void aFinishedLiveRunStopsClaimingStepsAreStillArriving() throws Exception {
        String ui = ui();
        assertThat(ui)
                .as("LUH-2: renderLt4Final draws an ALREADY-SETTLED run, and the poll loop's "
                        + "stop() ends the live window — both must retire the present tense")
                .contains("LT4_FINISHED_TEXT");
        assertThat(ui).contains("settleTraceCaption");
    }

    @Test
    void theDegradedRunIsNotDescribedAsOffline() throws Exception {
        String ui = ui();
        int idx = ui.indexOf("function engineChipText");
        assertThat(idx).isPositive();
        String fn = ui.substring(idx, idx + 900);

        assertThat(fn)
                .as("LUH-4: a degraded run REACHED the proxy and the call failed — that is why "
                        + "it degraded. Calling it offline describes a run that never happened, "
                        + "on the one screen someone checks to find out what did.")
                .contains("DEGRADED_TO_DETERMINISTIC")
                .contains("after the live agent failed");
    }

    @Test
    void theEngineChipHasNoDefaultToOffline() throws Exception {
        int idx = ui().indexOf("function engineChipText");
        String fn = ui().substring(idx, idx + 900);

        assertThat(fn)
                .as("a claim reached by falling through an else is not a claim anyone made — an "
                        + "unrecognised engine must name itself, not inherit a network posture")
                .doesNotContain("engine === 'ADK' ? 'ADK · via Copilot proxy' : 'deterministic · offline'");
        assertThat(fn).contains("default:");
    }

    @Test
    void theWriteBackPreviewRendersTheCauseAndResolutionSections() throws Exception {
        // J28/PGC-6: index.html hand-mirrors toDiagnosisNote() under a heading that says
        // "Posted to ServiceNow". If the Java gains a section and this does not, the UI
        // asserts a note body that differs from what was actually written to the ticket.
        assertThat(ui())
                .contains("Why this may be happening")
                .contains("How similar incidents were resolved");
    }
}
