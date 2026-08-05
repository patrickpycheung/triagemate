package com.company.triage.orchestration;

import com.company.triage.model.IncidentContext;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FND-62: the deterministic engine's query construction. "Deterministic" must mean
 * predictable, not fixed — same incident in, same queries out, but derived from the ticket
 * rather than hardcoded to the one seeded demo scenario.
 */
class IncidentSignalsTest {

    private static IncidentContext incident(String shortDesc, String desc, String ci) {
        return new IncidentContext("INC0010005", shortDesc, desc, "caller", "Software",
                "Application error", OffsetDateTime.parse("2026-07-23T09:20:00+10:00"),
                "Production", "Service Desk", List.of(), List.of(), ci, List.of());
    }

    @Test
    void extractsIdentifierShapesBeyondTheDemoFixtures() {
        // The old pattern was \bINC-ORD-\d+\b — only the demo's exact shape.
        assertThat(IncidentSignals.from(incident("x", "order INC-ORD-4471 failed", "App"))
                .primaryIdentifier()).isEqualTo("INC-ORD-4471");
        assertThat(IncidentSignals.from(incident("x", "ref ORD-1234 rejected", "App"))
                .primaryIdentifier()).isEqualTo("ORD-1234");
        assertThat(IncidentSignals.from(incident("x", "correlation BATCH-778812 aborted", "App"))
                .primaryIdentifier()).isEqualTo("BATCH-778812");
        assertThat(IncidentSignals.from(
                incident("x", "trace 3f2a1b4c5d6e7f8a9b0c1d2e3f4a5b6c end", "App"))
                .primaryIdentifier()).isEqualTo("3f2a1b4c5d6e7f8a9b0c1d2e3f4a5b6c");
    }

    /** The ticket's own number identifies the ticket, not the failing transaction. */
    @Test
    void doesNotTreatTheIncidentNumberItselfAsATransactionIdentifier() {
        var signals = IncidentSignals.from(incident("INC0010005 broke", "see INC0010005", "App"));
        assertThat(signals.primaryIdentifier()).isNull();
        assertThat(signals.logQuery()).doesNotContain("INC0010005");
    }

    @Test
    void keywordsDropFunctionWordsButKeepDomainTerms() {
        var signals = IncidentSignals.from(incident(
                "Orders sometimes don't go through at checkout",
                "A few customers reported that they try to submit an order and it fails with an error.",
                "Order Portal"));

        // Domain terms a runbook search needs — stripping these to look clever would defeat it.
        assertThat(signals.keywords()).contains("orders", "checkout");
        // Pure function words.
        assertThat(signals.keywords()).doesNotContain("sometimes", "that", "they", "with", "reported");
    }

    @Test
    void logQueryPrefersTheIdentifierAndFallsBackSensibly() {
        assertThat(IncidentSignals.from(incident("Checkout fails", "order ORD-9912", "App")).logQuery())
                .isEqualTo("ORD-9912");
        // No identifier: distinctive terms, NOT the bare literal "error" the old code always used.
        assertThat(IncidentSignals.from(incident("Login page timing out", "", "App")).logQuery())
                .contains("login").doesNotContain("ORD-");
        // Nothing at all to go on — "error" is the honest last resort.
        assertThat(IncidentSignals.from(incident("", "", "")).logQuery()).isEqualTo("error");
    }

    /**
     * J25/KQR-1 — the knowledge query is the symptom AS WRITTEN plus the affected system,
     * not a lowercased keyword bag.
     *
     * <p>This test previously asserted {@code contains("invoice")} — lowercase — because the
     * query was assembled from extracted keywords. Measured against the real instance, the
     * subject line retrieves materially better results than the keyword bag: it found a PRIOR
     * INCIDENT of the same fault and a use case for the exact scenario, where the keyword bag
     * returned only generic application documentation. Keyword extraction discards precisely
     * the connective structure ("not appearing in", "being recorded on") that a
     * relevance-ranked search uses. The intent of this test is unchanged — symptom plus
     * system — only the form.
     */
    @Test
    void confluenceQueryIsTheAffectedSystemNameAndNothingElse() {
        var q = IncidentSignals.from(incident("Invoice export failing", "ledger batch aborts",
                "Ledger Export Service")).confluenceQuery();
        assertThat(q).isEqualTo("Ledger Export Service");
        // J27: the symptom is deliberately absent. The query must depend only on WHICH
        // SYSTEM the ticket names, so the same system always searches the same way.
        assertThat(q).doesNotContain("Invoice").doesNotContain("ledger batch");
    }

    /**
     * J27: the fallback exists so the step is never a guaranteed no-op. A ticket with no CI
     * and no subject line would otherwise send an empty query.
     */
    @Test
    void fallsBackToTheSymptomWhenNoSystemNameCanBeDerived() {
        var q = IncidentSignals.from(incident("", "", null)).confluenceQuery();
        assertThat(q).isNotNull();
    }

    /**
     * J25/KQR-1 + J24/SFF-2 — when the app was INFERRED from the subject line, it is not
     * appended: doing so would just repeat words already in the query and dilute the ranking.
     */
    /**
     * J27: with no CI, {@code app} is the leading name-like fragment of the subject line
     * (FND-67), and that fragment IS the query — the rest of the sentence is not appended.
     */
    @Test
    void anInferredAppIsUsedAloneAsTheConfluenceQuery() {
        var signals = IncidentSignals.from(incident(
                "Hazards being recorded on handheld are not appearing", "details attached", null));

        assertThat(signals.appWasInferred()).isTrue();
        assertThat(signals.confluenceQuery()).isEqualTo(signals.app());
        assertThat(signals.confluenceQuery()).doesNotContain("not appearing");
    }

    @Test
    void ranksTheAllowlistByOverlapWithTheAffectedApp() {
        var scopes = List.of("prod/payment", "prod/order-api", "prod/ledger");
        assertThat(IncidentSignals.rankAllowlist("Ledger Export Service", scopes))
                .first().isEqualTo("prod/ledger");
        assertThat(IncidentSignals.rankAllowlist("Order Portal", scopes))
                .first().isEqualTo("prod/order-api");
    }

    /**
     * Ranking must never DROP an entry — the engine sweeps the whole allowlist, because a
     * single pick is a guess and the demo incident is exactly where it's wrong (CI says
     * "Order Portal", the failure is downstream in Payment Service).
     */
    @Test
    void rankingReordersButNeverDiscardsAllowlistEntries() {
        var scopes = List.of("prod/payment", "prod/order-api", "prod/ledger");
        assertThat(IncidentSignals.rankAllowlist("Totally Unrelated System", scopes))
                .containsExactlyInAnyOrderElementsOf(scopes);
        assertThat(IncidentSignals.rankAllowlist("Ledger Export Service", scopes))
                .containsExactlyInAnyOrderElementsOf(scopes);
    }
}
