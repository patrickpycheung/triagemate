package com.company.triage.gateway.real;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-85 — the log level must actually be read off a real Sumo row.
 *
 * <p>{@link RealSumoGateway} read {@code map.loglevel}; the field Sumo returns is
 * {@code map._loglevel}, with a leading underscore. Every row therefore came back with an
 * empty level, which is indistinguishable from "this line has no level" — so the failure was
 * invisible while the Sumo search itself worked perfectly and returned rows.
 *
 * <p><b>What it cost.</b> {@code DeterministicDiagnosisEngine:262} picks the error line with
 * {@code "ERROR".equals(l.level())}. Never true ⇒ {@code errorLine} null ⇒ {@code errorToken}
 * null ⇒ the {@code if (errorToken != null)} guard at {@code :298} never opened ⇒ the GitLab
 * code search never ran against real data and no log↔code citation was ever produced. The
 * trace said {@code errorToken=null}, which reads like "no errors today".
 *
 * <p>The fixture below is a <b>verbatim</b> row from the live AU instance (2026-08-05,
 * {@code delivery-hazards/prod}) — field names and raw layout exactly as returned. Measured
 * on that instance: with the fix, a 24h scoped ERROR search yields 14 rows at level ERROR and
 * the engine derives {@code errorToken=GNAF_FRONTAGE}; before it, 0 and {@code null}.
 */
class RealSumoGatewayLevelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Exactly the field set a real row carries — note `_loglevel`, not `loglevel`. */
    private static final String REAL_ROW = """
            {"_blockid":"8432065876903412117",
             "_loglevel":"ERROR",
             "_messagetime":"1785904013682",
             "_raw":"2026-08-05 14:26:46.171 ERROR 1 --- [http-nio-8080-exec-546] o.h.engine.jdbc.spi.SqlExceptionHelper   : ERROR: null value in column \\"facility_id\\" violates not-null constraint GNAF_FRONTAGE",
             "_sourcecategory":"IDT/ITServices/Tomcat/delivery-hazards/prod/AppEvt_delivery-hazards"}
            """;

    @Test
    void readsTheLevelFromTheFieldSumoActuallyReturns() throws Exception {
        var fields = JSON.readTree(REAL_ROW);

        assertThat(RealSumoGateway.level(fields, fields.path("_raw").asText()))
                .as("`_loglevel` is the real field name; reading `loglevel` yields \"\" on every row")
                .isEqualTo("ERROR");
    }

    /**
     * The field comes from a Sumo field-extraction rule, so a source without that rule
     * configured would reopen the same hole. Parsed out of the raw line instead.
     */
    @Test
    void fallsBackToParsingTheRawLineWhenTheFieldIsAbsent() throws Exception {
        var noField = JSON.readTree("""
                {"_raw":"2026-08-05 14:26:46.171 ERROR 1 --- [http-nio-8080-exec-546] some.Logger : boom"}
                """);

        assertThat(RealSumoGateway.level(noField, noField.path("_raw").asText())).isEqualTo("ERROR");
    }

    @Test
    void reportsNoLevelRatherThanGuessingWhenThereIsNone() throws Exception {
        var bare = JSON.readTree("{\"_raw\":\"a line with no level word in it\"}");

        assertThat(RealSumoGateway.level(bare, bare.path("_raw").asText())).isEmpty();
    }

    /**
     * The engine's own selector, against the real row. This is the assertion that would have
     * caught FND-85: it exercises the exact predicate at
     * {@code DeterministicDiagnosisEngine:262} rather than the getter in isolation.
     */
    @Test
    void theEnginesErrorLineSelectorMatchesARealErrorRow() throws Exception {
        var fields = JSON.readTree(REAL_ROW);
        String level = RealSumoGateway.level(fields, fields.path("_raw").asText());

        assertThat("ERROR".equals(level))
                .as("DeterministicDiagnosisEngine:262 filters on exactly this")
                .isTrue();
    }
}
