package com.company.triage.api;

import com.company.triage.config.ConnectorModeProvider;
import com.company.triage.config.DemoUiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J23/LUH-3 — the live window can show provenance the moment it opens.
 *
 * <p>Connector modes are static config, known before any run starts. A live trace card that
 * shows nothing until the run ends withholds a fact it already has, during the exact window an
 * audience is watching and forming a view about what the run is touching. Waiting to be sure is
 * not honesty here; it is silence where a true statement was available.
 *
 * <p>The ENGINE chip is deliberately still absent from the live window: which engine actually
 * ran is NOT known until the first step arrives, and guessing it would be LUH-4's failure in a
 * different costume.
 */
@WebMvcTest(UiConfigController.class)
class UiConfigCarriesProvenanceTest {

    @Autowired private MockMvc mvc;
    @MockBean private DemoUiProperties props;
    @MockBean private ConnectorModeProvider connectorModes;

    @Test
    void connectorModesAreServedAlongsideTheExistingFields() throws Exception {
        when(props.defaultIncidentNumber()).thenReturn("INC0010005");
        when(props.publicHostname()).thenReturn("triagemate.auspost.com.au");
        when(connectorModes.modes()).thenReturn(Map.of("servicenow", "real", "sumo", "mock"));

        mvc.perform(get("/api/ui-config"))
                .andExpect(status().isOk())
                // Purely additive: the page's existing cfg.defaultIncidentNumber read must
                // keep working untouched, which is the whole reason this is a new DTO rather
                // than a change to DemoUiProperties.
                .andExpect(jsonPath("$.defaultIncidentNumber").value("INC0010005"))
                .andExpect(jsonPath("$.publicHostname").value("triagemate.auspost.com.au"))
                .andExpect(jsonPath("$.connectors.servicenow").value("real"))
                .andExpect(jsonPath("$.connectors.sumo").value("mock"));
    }

    @Test
    void theModesComeFromTheSameSourceTheReportUses() throws Exception {
        // ConnectorModeProvider is what DiagnosisResult.connectors is built from too, so the
        // live window and the finished report cannot disagree about what this run touched.
        when(props.defaultIncidentNumber()).thenReturn("");
        when(props.publicHostname()).thenReturn("");
        when(connectorModes.modes()).thenReturn(Map.of("gitlab", "real"));

        mvc.perform(get("/api/ui-config"))
                .andExpect(jsonPath("$.connectors.gitlab").value("real"));
    }
}
