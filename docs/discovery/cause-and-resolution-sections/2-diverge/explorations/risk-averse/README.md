# Exploration — RISK-AVERSE / SAFETY bias

**Bias**: deliberately one-sided — only how these sections cause harm, and what must be
true before they ship.

**Headline**: shippable, but **not as two `String` fields**. A resolution section moves
the actuator from the app (capability-bounded, no destructive gateway methods) to a
**human under outage pressure** (unbounded). Every guardrail this repo relies on protects
the machine; none protect the human.

## Top 3 harms

### 1. Wrong fix executed on a production incident — CATASTROPHIC × MEDIUM-HIGH
The dangerous suggestions *sound like housekeeping*: "re-run the reconcile job", "replay
the DLQ", "roll back the deploy", "clear the cache", "restart the service". These
double-post financial transactions, corrupt data against a forward-only migration, cause
thundering herds, and destroy the diagnostic state needed to find the real cause. Harm
ordering is **inverse** to how alarming the verb sounds, so a deny-list of scary words
fails.

**Mitigation (R-1)** — the resolution is **not prose**. It is
`List<ResolutionStep>`, each with a `verb` from a **closed Java enum**
(`CHECK, COMPARE, REPRODUCE_NON_PROD, CONTACT, CONSULT_RUNBOOK, GATHER`), an object,
and `evidenceRefs`. Jackson rejects an unknown enum constant, so a mutating verb is
*structurally unreachable* in both engines. This converts J8's accepted "prompt-only
guardrail" limitation (FND-44) into an enforced one for ~40 lines.

### 2. Self-poisoning on the ADK path — HIGH × HIGH · 🔬 VERIFIED UNFIXED
FND-67's filter has exactly two call sites, both deterministic-path helpers
(`IncidentSignals.java:115`, `MentionedPeople.java:197`). `TriageMateTools.getIncident()`
returns the raw `IncidentContext` — comments and work notes **unfiltered** — to the LLM,
and `AdkDiagnosisEngine.instruction()` step 1 explicitly *directs* the model to read the
ticket conversation. A confidently-worded cause is far more re-ingestible than
`reportedSymptom`: run 2 reads run 1's hypothesis as ticket fact and promotes it.

**Mitigation (R-3)** — filter at the **tool boundary**, not in two helpers, before either
section ships.

### 3. Injection-authored "resolution" reaching an engineer — CATASTROPHIC × LOW-MEDIUM
`PromptInjectionGuardrailTest:114-117` **asserts** payload text appears *verbatim* in the
posted note. Inert under "what happened"; an instruction under "how to fix". Confluence
is editable and log lines are attacker-influenceable.

**Mitigation (R-4)** — R-1, plus: text from outside ServiceNow is rendered only as an
**attributed, linked quotation**, never as an imperative.

## Verdict: **GO-WITH-CONSTRAINTS**

Non-negotiable, all in the shipping commit:

1. **R-1** Closed `ResolutionVerb` enum; no free-text remediation in either engine.
2. **R-2** Cause is a **ranked list of ≥2 hypotheses or none** (J4's existing
   never-one-forced-answer rule), each with a **falsifier**, no numeric confidence,
   **never naming a person or commit author** as a cause.
3. **R-3** `isAiAuthoredNote` filtering moved to `TriageMateTools.getIncident()`.
4. **R-4** No fetched text rendered imperatively; attribution + link mandatory.
5. **R-5** `DiagnosisReportValidator` enforces `evidenceRefs` on every hypothesis and
   step — same commit, not "later" (`recommendedNextAction` shows what "later" means).
6. **R-6** Disclaimer rewritten from *what the app didn't do* to *what the reader must
   not do*, plus an explicit "this is not the root cause for any review or customer
   communication" clause, and a retraction sub-marker.

Full harm enumeration, blast-radius walk and the complete verb policy: `exploration.md`.
