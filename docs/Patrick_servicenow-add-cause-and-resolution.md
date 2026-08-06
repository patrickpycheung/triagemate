# ServiceNow First-pass Diagnosis: Add Incident Cause and Resolution

## 1) Current issue

The ServiceNow **First-pass diagnosis** comment is valuable but incomplete for application support because it does not consistently surface the two highest-value decision aids:

- **What likely caused the issue**
- **How we can likely resolve the issue**

Without these, support engineers still need to infer “why” and “what to do” from symptom/system/next-check data, which slows triage and handoff.

---

## 2) Implementation approach

The improvement should be implemented at the **DiagnosisReport data contract level** and then rendered into the ServiceNow note template so both engine paths (ADK and deterministic) produce the same output shape.

### A) Real ADK agent flow (model decides cause/resolution)

In ADK mode, the model should infer and return the two fields from evidence:

1. Extend/enforce the ADK output contract to include:
   - `likelyCause`
   - `likelyResolution`
2. Update ADK instruction/schema so the model is explicitly told:
   - when to populate these fields
   - when to return `null` (abstain if evidence is insufficient)
3. Keep strict validation for:
   - evidence grounding (`evidenceRefs` must exist)
   - cause/resolution integrity (verbatim quote/source constraints where required)
4. Ensure the parsed ADK report carries these fields through to `DiagnosisReport` (no dropping during stamping/rebuild).
5. Render the final ServiceNow diagnosis note from `DiagnosisReport.toDiagnosisNote()` so cause/resolution sections appear in the posted work note.

**Key implementation points in codebase**
- ADK thinking/output contract: `src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java`
- Report contract + ServiceNow note rendering: `src/main/java/com/company/triage/model/DiagnosisReport.java`
- Contract validation: `src/main/java/com/company/triage/model/DiagnosisReportValidator.java`

### B) Deterministic mock flow (hard-coded cause/resolution response)

In deterministic mock mode, cause/resolution should be produced predictably from seeded fixture data (hard-coded by design):

1. Seed resolved-incident fixture records with:
   - `resolutionNotes` (for likely cause narrative)
   - `resolutionCode` (for likely resolution step typing)
2. Deterministic engine builds:
   - `LikelyCause` from similar incidents with non-empty `resolutionNotes`
   - `LikelyResolution` from `resolutionCode`-driven categorisation
3. Include both objects when constructing `DiagnosisReport`.
4. ServiceNow diagnosis note renderer prints:
   - **“Why this may be happening”** (cause)
   - **“How similar incidents were resolved”** (resolution)

**Key implementation points in codebase**
- Mock fixture source for hard-coded precedent data: `src/main/java/com/company/triage/gateway/mock/MockServiceNowGateway.java`
- Deterministic construction logic: `src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java`
- ServiceNow note rendering: `src/main/java/com/company/triage/model/DiagnosisReport.java`

---

## Expected outcome

After this improvement, the ServiceNow **First-pass diagnosis** comment will include both the incident’s likely cause and likely resolution guidance (or an explicit abstention when evidence is insufficient), making the note directly actionable for application support.
