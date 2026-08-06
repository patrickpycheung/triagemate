package com.company.triage.gateway;

/**
 * A connector could not answer — as distinct from answering "nothing found".
 *
 * <p>J14/FRI-5 and J25/KQR-4 are the same rule, stated from two directions, and this is the
 * single mechanism both use. Every real gateway used to end in {@code catch (Exception) →
 * List.of()  // best-effort}, which collapses two completely different facts into one value:
 *
 * <ul>
 *   <li><b>"I searched and there was nothing"</b> — a finding. The engine should say so and
 *       move on.</li>
 *   <li><b>"I could not search"</b> — an absence of evidence <em>about</em> evidence. The
 *       engine has learnt nothing, and must not narrate the run as though it had.</li>
 * </ul>
 *
 * <p>That collapse is not hypothetical: a Confluence base-URL 404 read as a clean no-match for
 * a full day, because the report said "no runbook matched" in exactly the words it uses when a
 * runbook genuinely does not exist. Nobody looks for a broken connector when the app says the
 * search worked.
 *
 * <p>Throwing rather than returning a typed result keeps the gateway interfaces unchanged, so
 * the mock implementations — which cannot fail and should not pretend they can — need no edit.
 * The engine catches per call site, records a FAILED trace row and a {@code missingInformation}
 * line naming the system as unreachable, and continues: the safety net degrades <b>per call</b>,
 * never per run.
 */
public class GatewayUnavailableException extends RuntimeException {

    private final String system;

    public GatewayUnavailableException(String system, Throwable cause) {
        super("%s is unreachable: %s".formatted(system, rootMessage(cause)), cause);
        this.system = system;
    }

    /** The system a human would name — "Confluence", "Sumo Logic", "GitLab", "ServiceNow". */
    public String system() {
        return system;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
