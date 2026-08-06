package com.company.triage.gateway.fixture;

import com.company.triage.model.LogSearchRequest;

/**
 * Normalised lookup keys for recorded gateway calls.
 *
 * <p>The key is what makes a recording findable on the next run, so it must contain
 * everything that changes the ANSWER and nothing that merely changes between runs. The
 * Sumo window is the reason this class exists rather than "just hash the arguments": the
 * deterministic engine searches the last 24h, so {@code fromTime}/{@code toTime} differ on
 * every single run and an argument-hash key would miss 100% of the time — a replay that
 * always returns nothing, which is indistinguishable from a real empty result.
 *
 * <p>Case is normalised because the callers vary (a ticket's CI string, a model-supplied
 * term) while the underlying system does not care.
 */
public final class FixtureKeys {

    private FixtureKeys() {}

    public static String of(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('&');
            sb.append(p == null ? "" : p.trim().toLowerCase());
        }
        return sb.toString();
    }

    /** Scope + term only — the window and result cap are deliberately excluded (see above). */
    public static String forSumo(LogSearchRequest req) {
        if (req == null) return "";
        return of(req.sourceCategory(), req.query());
    }
}
