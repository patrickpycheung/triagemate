package com.company.triage.model;

import java.time.OffsetDateTime;

/**
 * A newly-created incident as seen by the K1 poller: its number plus the
 * {@code sys_created_on} that made it visible.
 *
 * <p>The timestamp is not decoration — it is what makes the poller's cursor a correct
 * high-water mark. Advancing the cursor to "now" after a batch silently drops any incident
 * created <i>while the batch was being processed</i> (a diagnosis run takes seconds, and a
 * live agent run can take much longer). Advancing it to the newest {@code createdAt}
 * actually handled cannot skip anything.
 */
public record NewIncident(
        String number,
        OffsetDateTime createdAt
) {}
