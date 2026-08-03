package com.company.triage.gateway.real;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the timestamp format Sumo's Search Job API actually accepts.
 *
 * <p>Regression guard for a real bug: the gateway used to interpolate
 * {@code OffsetDateTime.toString()} straight into the request body, producing
 * {@code 2026-08-03T14:16:40.644281692+10:00}, and the API rejected every such call with
 * {@code 400 searchjob.invalid.timestamp.from}. It wants second precision in UTC with no
 * offset suffix — the body carries {@code "timeZone":"UTC"} separately.
 *
 * <p>Offline on purpose. {@link RealSumoGatewayLiveTest} is the one that found this, but
 * it only runs with credentials present; this keeps the format pinned for everyone else.
 */
class RealSumoGatewayTimeFormatTest {

    @Test
    void timestampsAreSecondPrecisionUtcWithNoOffsetOrFraction() {
        // A non-UTC instant with sub-second precision — exactly the shape that 400'd.
        OffsetDateTime melbourne = OffsetDateTime.parse("2026-08-03T14:16:40.644281692+10:00");

        String formatted = RealSumoGateway.sumoTime(melbourne);

        assertThat(formatted).isEqualTo("2026-08-03T04:16:40");   // converted to UTC
        assertThat(formatted).doesNotContain("+", "Z", ".");      // no offset, no fraction
    }

    @Test
    void anInstantAlreadyInUtcIsUnchangedApartFromTrimming() {
        assertThat(RealSumoGateway.sumoTime(OffsetDateTime.parse("2026-08-03T04:16:40Z")))
                .isEqualTo("2026-08-03T04:16:40");
    }
}
