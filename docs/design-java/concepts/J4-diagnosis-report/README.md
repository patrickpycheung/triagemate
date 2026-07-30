# J4 — Diagnosis Report (JSON contract)

**State**: 🟢 Stable · **Complexity**: Simple · **Depends on**: —

## Essence
The strict JSON contract every run produces. Building the report **before** the
agent (per the analysis) forces the output shape and makes the work note (J5) and
UI (J7) mere renderings of it. This is the demo's spine.

## Schema
```json
{
  "incidentNumber": "INC0012345",
  "generatedAt": "2026-07-23T14:30:00+10:00",
  "reportedSymptom": "User receives HTTP 403 when submitting an order",
  "affectedFunction": "Order submission",
  "environment": "Production",
  "identifiers": { "correlationId": "abc-123", "errorCode": "ORD-4031" },
  "candidateSystems": [
    { "name": "Order Portal",     "confidence": 0.82, "evidenceRefs": ["e1","e3"] },
    { "name": "Identity Gateway", "confidence": 0.68, "evidenceRefs": ["e2"] }
  ],
  "suggestedAssignment": {
    "group": "Identity Platform Support", "confidence": "medium",
    "evidenceRefs": ["e2","e4"]
  },
  "evidence": [
    { "id": "e1", "source": "servicenow-cmdb", "summary": "...", "link": "..." },
    { "id": "e2", "source": "confluence", "summary": "KB001234 ...", "link": "..." },
    { "id": "e3", "source": "servicenow-incident", "summary": "2 similar resolved ...", "link": "..." },
    { "id": "e4", "source": "sumo", "summary": "matching ORD-4031 logs ...", "link": "..." },
    { "id": "e5", "source": "gitlab", "summary": "order-api Foo.java:118 emits ORD-4031", "link": "..." }
  ],
  "suggestedContacts": [
    { "name": "Priya Nair", "contact": "priya.nair@example.com",
      "source": "confluence+gitlab",
      "why": "edited the runbook and committed reconcile()",
      "link": "...", "recency": "recent" }
  ],
  "contradictingEvidence": ["Order API also emits ORD-4031 on a different path"],
  "missingInformation": ["Affected user ID", "Whether all users affected"],
  "recommendedNextAction": "Confirm the user has the ORDER_SUBMITTER entitlement",
  "confidenceOverall": "medium",
  "advisory": true
}
```

## Rules
- Candidates and assignment are **ranked shortlists**, never a single forced answer;
  the report **explains conflicting evidence** rather than hiding it.
- Every conclusion ties to `evidenceRefs`. `advisory` is always `true` this phase.
- Java: immutable records (`DiagnosisReport`, `CandidateSystem`, `Evidence`, …);
  Jackson (de)serialization; a validator the agent's final step must satisfy.
- Renders to the two ServiceNow comments (J5): `toSourcesNote()` (the cited
  evidence, posted first) and `toDiagnosisNote()` (the advisory view), and to the
  demo UI (J7). Report is the single source; both comments and the UI derive from it.
- **`suggestedContacts` is UI-only and must never reach ServiceNow (J9).** It is the one
  field that is *not* rendered into either comment. "Who to talk to" is a suggestion for
  the engineer looking at our screen — naming individuals in an incident journal is a
  customer-visible, permanently-retained accusation-by-proximity, and the people named
  were inferred from wiki edits and commit history, not from any statement about fault.
  So it renders in the UI (J7) only.
  Enforced by `DiagnosisReportNoteTest#suggestedContactsNeverAppearInServiceNowNotes`
  (FND-2) — before that test the exclusion held only because neither note-builder happened
  to reference the field, which is not an invariant, just a coincidence waiting to be
  edited away.

## Verification
- Round-trips through Jackson; validator rejects a report with an empty
  `candidateSystems` or a dangling `evidenceRef`.
