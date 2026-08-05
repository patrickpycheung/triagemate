# RISK-AVERSE exploration — cause & resolution sections

**Trust legend**: 🔬 Spiked (verified in this codebase) · 📚 Documented · 🔍 Inspected ·
🤔 Reasoned · ❓ Unknown

**Stance**: adversarial. This document assumes the feature ships and asks only *how it
hurts someone*. It does not weigh the upside.

---

## 0. The frame shift nobody has named yet

🔬 Every guardrail this repo relies on is a **capability bound**.
`PromptInjectionGuardrailTest:77-90` proves it by reflection: `ServiceNowGateway` has
exactly five methods (`getIncident`, `findSimilarIncidents`, `findOwnership`,
`addWorkNote`, `findIncidentsCreatedSince`) and nothing containing
reassign/close/resolve/setPriority/escalate/delete/updateState. J8 states the doctrine
outright: *"Trust comes from what it's allowed to do (only comment) — not from a human
gate."*

📚 J8 then accepts a known weakness on exactly that reasoning (FND-44, closed
"not fixed"): *"the actual safety boundary (advisory-only, no destructive tools …) is
unaffected either way … Revisit if this app processes less-trusted input than an internal
ServiceNow queue."*

🤔 A **resolution section voids the premise of that acceptance**. The capability bound
bounds what the *app* can do; a "how to fix this" section recruits a **human with root**
as the actuator. The app still cannot restart a service; the sentence it writes can cause
a service to be restarted. The blast radius is no longer the gateway's method list — it
is whatever the assigned engineer can do, which is everything. Not a reason to refuse the
feature; the reason it cannot inherit the existing guardrails and must bring its own.

**Amplifier assumption used throughout**: the reader is 40 minutes into a Sev-1 and is
*scanning*, not reading. Hedging language is not read, bottom-of-note disclaimers are not
read, and a confident imperative near the top is executed. Any mitigation that depends on
careful reading is not a mitigation.

---

## 1. Harm enumeration

### H1 — Wrong fix executed under outage pressure
**Likelihood MEDIUM-HIGH · Severity CATASTROPHIC**

🤔 Blast-radius walk, by effect class rather than by verb:

**Class A — irreversible / data-destructive.** Recovery requires manual correction and
often customer notification.
- *Re-run the reconcile job* / *reprocess the batch* / *resubmit*. Reconciliation jobs are
  idempotent only when someone made them so; on a payment or ordering system a re-run
  double-posts. **The single most dangerous suggestion on the list, precisely because it
  reads as housekeeping** — nobody escalates before re-running a reconcile.
- *Replay / redrive the DLQ*. Same shape: duplicate fulfilment, duplicate customer email,
  duplicate charge. The demo domain (`payment_service`, `order_api`, `orderId`) sits
  squarely in this blast radius.
- *Roll back the deploy*. If the deploy carried a forward-only migration, the rollback runs
  old code against the new schema: silent write corruption found days later. Also destroys
  the state needed to diagnose.
- *Grant the entitlement* / *reset the password* / *rotate the key*. Permanent
  authorisation change, and the highest-value injection target (H2).
- *Delete / purge / truncate the stuck records.*

**Class B — outage-extending.** Recoverable, but converts a contained failure into a
worse or longer one.
- *Restart the service* / *bounce the pod*. Destroys heap, thread and connection state —
  the evidence. Triggers re-election or a cold start on leader-elected/slow-warmup
  systems. If the true cause is upstream, the restart loop masks it.
- *Fail over*. During a network partition, on a split-brain-capable store, this is how you
  get two writers.
- *Clear the cache*. Thundering herd against an already-struggling backend: degraded
  becomes down. Also destroys evidence of what was cached wrong.
- *Disable the circuit breaker* / *raise the rate limit* / *turn off the validation*.
  Removes the control that was containing the blast radius; contained becomes cascading.
- *Scale up*. Masks the symptom, delays diagnosis, and on a DB-bound failure more
  instances mean more connections and faster database death — counterproductive.

**Class C — merely useless.** Wrong team, wrong log query. Costs a bounce, which is the
metric this product claims to improve, so not free — but not harmful.

🤔 **The load-bearing observation: harm ordering is inverse to how alarming the verb
sounds.** "Delete the records" is scary and a human stops. "Clear the cache" and "re-run
the reconcile" are soothing and a human proceeds. A deny-list built from alarming words
catches Class A's smallest member and misses its largest.

**Mitigation R-1 — closed-enum verbs (the primary recommendation).** Do not filter prose.
Remove the ability to express prose.

```java
public enum ResolutionVerb { CHECK, COMPARE, REPRODUCE_NON_PROD, CONTACT,
                             CONSULT_RUNBOOK, GATHER }
public record ResolutionStep(ResolutionVerb verb, String object,
                             List<String> evidenceRefs) {}
```

Why an allow-list and not a deny-list: a deny-list loses to paraphrase — "cycle the
process", "give it a kick", "let it re-sync" all mean restart. An enum has no paraphrase.
Jackson fails deserialization on an unknown constant, so an LLM emitting `RESTART` fails
the report rather than shipping it, and the FND-7 fallback catches it. Both engines are
constrained by the same type. Cost: one enum, one record, one validator rule.

🔬 This is not a new policy — it makes an existing implicit one enforceable.
`DeterministicDiagnosisEngine.java:524-538` produces `recommendedNextAction` from exactly
four hard-coded templates, and every one is an observation verb: *Review …*, *Trace …*,
*Widen the log search …*, *Reproduce the failure and capture a correlation id*. Zero
mutating verbs, by accident of authorship rather than by rule. R-1 writes the rule down
and makes it survive the next contributor.

**Mitigation R-1b — never name a mutating action, even to forbid it.** Do not emit "do
NOT restart the service." Negation is the first thing lost by a scanning reader, and the
sentence supplies the idea.

---

### H2 — Prompt injection weaponised through the resolution field
**Likelihood LOW-MEDIUM · Severity CATASTROPHIC**

🔬 There is **no `PromptInjectionGuardrail` class in this repo** — only
`PromptInjectionGuardrailTest`. The guardrail is entirely the capability bound of §0, plus
five fixture payloads (`jailbreak`, `role-override`, `exfiltration`, `tool-abuse`,
`markup-injection`).

🔬 More pointedly, the test **asserts verbatim echo as desired behaviour**:

```java
boolean payloadEchoed = snow.notes.stream().anyMatch(n -> n.contains(p.text()));
assertThat(payloadEchoed).as("payload %s should appear verbatim, not be stripped/altered",
        p.id()).isTrue();                          // PromptInjectionGuardrailTest:115-117
```

That is correct today: under the heading *"What appears to have happened"*, attacker text
is inert description, and echoing it verbatim is more honest than silently mangling it.
🤔 The same bytes under the heading *"How to fix this"* are an **instruction with a human
executor**. The render context, not the content, is what converts data into an
instruction — and the test locks the passthrough in.

🤔 Attack path: attacker edits a Confluence page (J8: *"Confluence search has no
space-scoping mechanism at all"* — containment is only the service account's permissions
and a 5-result cap), or gets a line into a prod log via any user-supplied field the app
logs → `search_confluence`/`search_logs` retrieves it as evidence → it surfaces in the
resolution field → it is posted verbatim into a work note under "how to fix" → an
engineer under Sev-1 pressure runs it.

The highest-value payload is not a shell command — it is **"grant `<attacker>` the
ORDER_SUBMITTER entitlement"** on an access-denied ticket. It looks exactly like the
legitimate fix for that ticket class, and the resulting audit trail shows a legitimate
engineer deliberately granting access. That is an authorisation bypass laundered through
an incident record.

**Mitigations.** R-1 removes the imperative form entirely — an enum verb plus an object
cannot carry a command line. Additionally:

- **R-4a** Text originating outside ServiceNow is rendered **only** as an attributed,
  linked quotation: *"KB-4471 (last edited by `<who>`, `<when>`) says: '…'"*. Never
  *"Run: …"*. Attribution is the human's injection defence — it lets the engineer notice
  the "fix" came from a page edited yesterday by an unknown account.
- **R-4b** Reject any resolution step whose `object` matches a command-shaped pattern
  (leading `$`/`#`, `curl`, `sudo`, `;`, `|`, `&&`, a bare URL, a SQL verb). Belt to R-1's
  braces, and cheap.
- **R-4c** Extend `PromptInjectionGuardrailTest` with a case asserting that a payload in
  an *evidence summary* does **not** reach the resolution section. The existing test's
  verbatim-echo assertion should be scoped to descriptive fields, not the new ones.
- **R-4d** Keep the reflection assertion at `PromptInjectionGuardrailTest:81` as a
  tripwire — it fails if `ServiceNowGateway` ever grows a sixth method. Do not relax it.

---

### H3 — Self-poisoning (FND-67 redux) · 🔬 VERIFIED PRESENT AND UNFIXED
**Likelihood HIGH · Severity HIGH**

🔬 `isAiAuthoredNote` has **exactly two call sites in `src/main`**:

- `IncidentSignals.java:115` — keyword/identifier extraction (deterministic path)
- `MentionedPeople.java:197` — contact mining (deterministic path)

🔬 `TriageMateTools.getIncident()` returns `serviceNow.getIncident(CURRENT_INCIDENT.get())`
— the whole `IncidentContext`, `comments` and `workNotes` included, **with no filter** —
directly to the model.

🔬 And `AdkDiagnosisEngine.instruction()` step 1 explicitly instructs the model to read
them: *"read the reported symptom, identifiers, and the ticket conversation (comments /
work notes)"*, adding that *"the caller's own follow-ups often carry the timing and scope
detail the description omits."*

**Conclusion: FND-67 was fixed on the deterministic path only. On `triage.engine=adk` the
app still feeds on its own output, today, before this feature is added.** The existing
`AI_NOTE_PREFIX` mechanism does **not** protect the LLM path.

🤔 Why the new sections make this worse. Today the re-ingested material is observational
— symptom text, system and contact names — and FND-67's observed damage was drift
(extracting "AI Triage" as a person). A cause statement is **assertive, causal, and reads
exactly like a senior engineer's work note**. The degradation is not drift but
**confidence laundering**:

- Run 1: *"Possible cause: connection-pool exhaustion in Payment Service (low confidence)."*
- Run 2 reads that as ticket conversation, treats it as a human's judgement, and finds it
  "corroborated" by the same logs that produced it.
- Run 3 states it as the cause, citing the ticket.

Each round-trip strips a hedge and adds an apparent independent source, while the
evidence chain is circular. The `evidenceRefs` contract does not catch this — the refs
resolve fine; they just point at the app's own prior conclusion.

**Mitigations.**
- **R-3a** Move the filter to the boundary: `TriageMateTools.getIncident()` returns an
  `IncidentContext` with AI-authored journal entries removed, so *every* consumer inherits
  it. Two hand-copied call sites is precisely the pattern J18 exists to eliminate
  (*"a bound is owned by the boundary, not by the caller"*) — this is a fourth instance of
  its thesis and belongs in that card.
- **R-3b** Add a test that fails if a new `IncidentContext` consumer skips the filter.
- **R-3c** Evidence provenance: an `Evidence` item derived from a ServiceNow journal entry
  records whether that entry was AI-authored, so a future cause can never cite the app's
  own prior note as support.

---

### H4 — Anchoring and premature closure
**Likelihood HIGH · Severity MEDIUM**

🤔 This is the *normal* path, not a failure path: a stated cause makes a human stop
looking, and it does so even when the cause is correct — because it narrows the search
before the engineer has formed their own read. In a Sev-1, twenty minutes of anchored
investigation is customer impact.

🔍 Two existing details make it worse. `toDiagnosisNote()` puts *"What appears to have
happened"* first, so a cause placed nearby becomes the frame for everything after it. And
the same method renders `"%s (%.0f%%)"` for candidate systems — numeric anchoring is
stronger than verbal, so importing that precedent onto a causal claim is a double hit.

**Mitigation R-2 — a presentation that informs without anchoring.** Four rules, all
already consistent with J4's stated posture (*"ranked shortlists, never one forced
answer"*):

1. **Plural by construction: ≥2 hypotheses, or none.** A single cause is an anchor. Two
   competing causes is an investigation plan. Reuse the rule J4 already applies to
   `candidateSystems` rather than inventing one.
2. **Each hypothesis carries its falsifier**: *"if this is it, you would also see X;
   if X is absent, it is not this."* This is the strongest available answer to the
   anchoring question — it converts an anchor into a test, and it makes a wrong hypothesis
   self-correcting instead of self-reinforcing. It is also what makes the section
   genuinely useful rather than merely confident.
3. **No numeric confidence on a cause.** False precision, and stronger anchoring. Verbal
   tiers only, and only where the evidential precondition is met.
4. **Placement after `contradictingEvidence` and `missingInformation`**, not before —
   the reader meets the doubt before the hypothesis.

**R-5 (relates)**: both sections are **absent by default**, not hedged by default. A
rendered heading containing "insufficient evidence to state a cause" still anchors on the
*idea that a cause is knowable from this note*; omission does not. Emit a cause only when
a hard precondition holds (e.g. ≥2 corroborating evidence ids from *distinct* sources),
a resolution only when a runbook was actually cited.

---

### H5 — Liability and the permanent record
**Likelihood MEDIUM · Severity HIGH (and uniquely un-undoable)**

🤔 A ServiceNow work note is retained, auditable, timestamped, and reachable by
post-incident review, customer RCA, regulator request, and legal discovery. An
AI-asserted cause on a customer-impacting incident becomes part of the organisation's
**record of what it believed and when**. Two specific exposures:

- **Contradicting the final RCA.** The note says "upstream payment gateway timeout"; the
  real RCA says "our own bad deploy". The record now shows either an org that got it
  wrong, or — adversarially framed — one that blamed a named third party without basis,
  in writing, at hour one.
- **Naming a human as a cause.** 🔍 Reachable, not hypothetical: `MentionedPeople` and
  `gatherContacts` already extract named individuals, and J13's evidence model ties
  candidates to specific commits. A cause reading *"introduced by the change in
  `<commit>`"* is an AI-authored, permanently retained accusation against an identifiable
  employee.

**Mitigations.**
- **R-2e** **Never name a person, handle, or commit author inside a cause.** Systems and
  changes may be named as causes; humans may appear only under `suggestedContacts`, whose
  framing is "who to talk to", not "who did this".
- **R-6 Rewrite the disclaimer from what the app didn't do to what the reader must not
  do.** Current text — *"AI-assisted and advisory. No reassignment, closure, or priority
  change has been made — the assigned engineer decides"* — describes the app's restraint.
  It says nothing about acting on the content, and nothing about the record. It must add,
  in substance: this is a machine-generated hypothesis, not a finding; no human has
  reviewed it; it is not an authorisation to change anything; nothing here should be
  actioned on production without independent verification; **and it does not constitute
  this incident's root cause for any review, report, or customer communication.** That
  final clause is the liability-critical one — it severs the note from the RCA record
  pre-emptively, the only moment severing is cheap.
- **R-6b Retraction marker.** If a systemic defect is later found, the org must locate and
  annotate every affected note. `AI_NOTE_PREFIX` gives coarse greppability;
  cause/resolution need their **own sub-marker** so a retraction targets the causal claims
  without retracting the still-valid sources notes.
- 🤔 **R-6c** Consider a **third, separately-labelled note** rather than appending to the
  diagnosis note — isolates the riskiest content for retraction and keeps the defensible
  note defensible. Cost: a third write, and the two-note invariant is asserted in several
  tests (`PromptInjectionGuardrailTest:109-113`) — real but bounded.

---

### H6 — Grounding vacuum: the cheap implementation silently voids success criterion 1
**Likelihood HIGH · Severity MEDIUM-HIGH**

🔬 `DiagnosisReportValidator` enforces `evidenceRefs` on `candidateSystems` (dangling refs,
uniqueness, uncited candidates) and on `suggestedAssignment`. It enforces **nothing** on
`recommendedNextAction`, which is a bare `String` with no `evidenceRefs` at all.

🤔 The path of least resistance is to add `String likelyCause, String likelyResolution`
next to it — which inherits exactly zero grounding enforcement, while the problem
statement's criterion 1 requires the opposite. J13's own framing predicts the outcome:
*"A one-off code fix is a fix; a validator rule is a guarantee."* And the history is on
the record — `recommendedNextAction` is the field that was added without refs and never
got them.

**Mitigation R-5** — ship the validator rule in the **same commit**: every hypothesis and
every resolution step carries ≥1 non-dangling `evidenceRef`, or the section is omitted.
📚 Note J13/ECI-6's warning: on the ADK path a validation failure throws *after* the agent
loop, so each new hard rule is a new way to degrade to the fallback mid-demo. Since these
sections are omissible by design (R-5), prefer **drop the section** over **fail the
report** — the report stays valid and the risky content simply does not appear.

---

### H7 — The safe path becomes the confidently-wrong path
**Likelihood MEDIUM · Severity HIGH**

📚 The deterministic engine is *"the demo's guaranteed-safe path and cannot invent prose"*
and is also the FND-7 fallback — it runs when the ADK path has already failed. 🤔 The
demo pressure is to make the fallback look as good on stage as the live agent. If it
gains four hand-written cause templates the way it has four `nextAction` templates, then
a scripted guess is asserted as a cause on the path that runs precisely when everything
else has broken, and it will be indistinguishable from the LLM's output to the audience.

**Mitigation R-7** — the deterministic engine emits a hypothesis only from a **structural
correspondence it actually computed** (e.g. an error token in a log line tied to an
emitting `file:line` in an allowlisted project — the 0.86 tier it already builds), and
omits both sections otherwise. No template prose. Under R-1 the resolution side is
already safe by construction; the cause side needs this rule.

---

### H8 — Confidence theatre
**Likelihood MEDIUM · Severity MEDIUM** — see H4.3. Verbal tiers only; no percentage on
a causal claim. `toDiagnosisNote()`'s existing `%.0f%%` render is the precedent to
*avoid*, not to follow.

### H9 — Write-scope creep
**Likelihood LOW · Severity CATASTROPHIC-if-it-happens.** The natural next request after
"tell me how to fix it" is "then fix it". 🔬 `PromptInjectionGuardrailTest:81-89` is the
tripwire: it pins the gateway to five named methods and rejects eight forbidden verb
substrings. **R-9**: keep it, never relax it, and state in J8 that the resolution section
is the app's permanent ceiling, not a step toward remediation.

### H10 — Retraction impossibility
**Likelihood LOW-MEDIUM · Severity MEDIUM.** Covered by R-6b/R-6c.

---

## 2. The verb policy, stated once

**Allowed** (`ResolutionVerb`, closed): `CHECK`, `COMPARE`, `REPRODUCE_NON_PROD`,
`CONTACT`, `CONSULT_RUNBOOK`, `GATHER`.

**Never emitted, by effect class** (unreachable under R-1; recorded so the enum is never
"just extended"): state mutation on a live system (restart, bounce, failover, scale,
deploy, roll back, revert); data mutation (delete, purge, truncate, replay, redrive,
re-run, reprocess, resubmit, requeue); authorisation change (grant, revoke, reset, rotate,
add-to-group); safety-control change (disable, bypass, raise-limit, turn-off); anything
carrying an executable payload (shell, SQL, curl, a URL to fetch).

🤔 **`ENABLE_DEBUG_LOGGING` is deliberately excluded** despite reading as observational —
in production it fills disks and changes system behaviour under load. It is a state
mutation wearing an observation's clothes, and it is the exact shape the enum exists to
keep out.

**Escape hatch, deliberately absent.** No `OTHER` constant, no free-text override. The
first `OTHER` reintroduces prose and with it every harm in §1.

---

## 3. What would change this verdict

- ❓ If the resolution section were **not written to ServiceNow** and appeared only in the
  demo UI (J7), H5 (record/liability) and most of H2's severity evaporate. The record is
  what makes this expensive; a read-only surface is a much cheaper feature.
- ❓ Whether the target ServiceNow queue is genuinely internal-only. J8's FND-44 acceptance
  rests on it, and the resolution section is the "revisit" trigger that clause names.
- ❓ Whether any real reconciliation/replay tooling exists in the target environment. If
  the answer is no, H1's Class A collapses to Class B and the feature is materially
  cheaper to ship safely.
