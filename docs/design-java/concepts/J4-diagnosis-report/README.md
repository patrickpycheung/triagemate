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

## Verification
- Round-trips through Jackson; validator rejects a report with an empty
  `candidateSystems` or a dangling `evidenceRef`.
