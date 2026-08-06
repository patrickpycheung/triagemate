# J28 — Precedent-grounded cause & resolution (why it broke, what was done about it)

**State**: 🟢 **Built — both engines** (deterministic 2026-08-05; **ADK path ungated 2026-08-06** when J13/ECI-6 landed, per PGC-7) · **Complexity**: Moderate ·
**Priority**: MEDIUM ·
**Depends on**: J4 (report contract), J5 (note rendering), J7 (UI), **J26** (similar-incident
ranking — supplies the data it quotes), **J27** (ADK journal filter — prevents laundering) ·
**Gated on** (emission, per PGC-5/PGC-7): ~~J13/ECI-6 (ADK path)~~ ✅ **opened 2026-08-06**; **J25** (`KNOWN_ERROR_DOC`) — note J25 is 🟡 mostly built, so this may now be openable too;
**J13** (`CODE_PATH`), **J24** (restores ~30% of J26's ranking input) ·
**Amends**: J4 (two new components + three validator rules), J5 (`toDiagnosisNote` gains two
sections), J7 (the write-back preview must render them too) ·
**Source**: DDS [`cause-and-resolution-sections`](../../../discovery/cause-and-resolution-sections/)
— 7 independent explorations + 1 spike; `/doc-test dds` 2026-08-05 (Codex + Gemini + Claude)

> **Design is complete and implementable for the deterministic engine today.** Everything
> gated above concerns *emission* on paths whose prerequisite hasn't landed — no gate changes
> the schema or the rules, so nothing here is provisional.
>
> **Convergence trail** — three rounds changed the design, and each correction is recorded
> in place rather than smoothed away:
> - **R2** found `close_notes` is *one* string holding both cause and fix, so the Round-1
>   two-`quotedFinding` schema forced either sentence-splitting (inference) or duplication.
>   → **PGC-1a**, which also made the resolution section entirely closed-vocabulary.
> - **R4** caught that my own first fix (hard-on-deterministic / logged-on-ADK) contradicted
>   J13's explicitly ⛔-rejected engine-asymmetric validation — FND-39 by name. → **PGC-7**,
>   gating *emission* instead of *enforcement*.
> - **R7** found the DDS's demo requirement had been dropped in translation. → **PGC-8**.
>
> R6, R9 and R10 produced no design changes.

## Essence

The report says *what* broke and *who* owns it. It never says **why**, or **what to do**.
Those are the two questions an engineer opens a ticket asking.

The design answer is not "add two strings". It is a claim about what this system is
entitled to assert:

> **TriageMate has no causal substrate. It may CITE a cause; it may not ASSERT one.**

Every product that says *"the root cause is X"* — Dynatrace, Traversal — owns a causal
substrate (a topology graph, causal ML over deploy history). Everything precedent- or
text-based hedges. TriageMate is structurally in the second family. Its one genuinely causal
artifact is a **human's closed verdict on a past incident**: someone diagnosed it, fixed it,
watched it stop, and wrote it down in `close_notes`.

So J28 **quotes that verdict verbatim with attribution** and never paraphrases it into
TriageMate's own voice. Paraphrase launders someone else's guess into our assertion.

## Why this is a concept, not two fields

Three things make it a design problem rather than a schema addition:

1. **It is a different epistemic class from every existing field.** Today's fields are
   observations and routings, defensible because J4 mechanically enforces *every conclusion
   ties to `evidenceRefs`*. A **cause** is an inference beyond the evidence; a **resolution**
   is an instruction to act on a production incident, written into a retained journal by a
   system whose whole posture is *advisory — it comments, it never acts*.
2. **The guardrail's second leg does not survive it.** `PromptInjectionGuardrailTest`'s own
   javadoc states the bound: injected text is *"rendered as an inert, verbatim string, not
   specially interpreted or acted on"*. That is a claim about **machine** interpretation, and
   it is sufficient for `candidateSystems` (worst case: a human reads a wrong system name).
   A resolution field's entire purpose is that a human reads it **and executes it** — so the
   blast radius stops being bounded by machine capability and becomes bounded by human
   compliance. TriageMate acquires an execution engine it cannot sandbox: the on-call
   engineer. Attacker path is already fully built (edit a Confluence page the runbook search
   will match → gathered as evidence → surfaced as a fix); and a **stale runbook** is the
   same failure with no attacker, which is the normal state of runbooks.
3. **Abstention is the common path, not the edge case.** Real ServiceNow close notes are
   overwhelmingly *"Issue resolved"* / *"Done"*. A schema that makes "no cause determined"
   invalid **forces the model to fabricate**. This is not hypothetical — the repo already
   ruled this way once: `DiagnosisReportValidator:100-103` deliberately exempts
   `suggestedAssignment` from the citation rule because *"forcing a citation there would push
   the code toward inventing one"*.

## Evidence

| # | Finding | Trust | Consequence for the design |
|---|---|---|---|
| 1 | LLM root-cause **correctness 2.40–2.88 / 5** while **readability 3.5–4.6** (Ahmed et al., ICSE 2023 — 44,340 Microsoft incidents, graded by the engineers who fixed them) | 📚 | It reads far better than it is right. Governs every constraint below, and is the pitch's sharpest line. |
| 2 | Best published precision came from an agent whose incorrect predictions were **66% "Insufficient Information"**; hallucination 6% vs 18% for CoT (Roy et al., FSE 2024) | 📚 | Abstention is a *performance* feature, not a cop-out. |
| 3 | ServiceNow's own "Similar Resolved Incidents" is ranked and deliberately shows **no percentage** | 📚 | Don't invent a percentage the product it embeds in declines to show. |
| 4 | Displayed **high** confidence measurably *decreases* human performance; low-confidence displays increase deliberation. Explanations alone don't mitigate (Bansal, CHI '21) | 📚 | High confidence is the dangerous label, not the safe one. |
| 5 | `index.html:1207` records **"No percentage, no progress bar (direct product feedback)"** | 🔬 | A confidence bar would re-litigate a settled call. |
| 6 | `ResolvedIncident.resolutionNotes` is fetched by both gateways (`close_notes`) and **read by nothing** in `src/main` | 🔬 | The richest available signal is currently discarded. |
| 7 | `close_notes` is raw, unvetted human free text — written under time pressure, often wrong about cause even when right about fix, occasionally containing credentials or literal commands | 🔬 | "Grounded" ≠ "safe to repeat". Quote + attribute; never paraphrase. |
| 8 | `index.html:1251-1258` reconstructs the note **client-side** from report JSON — it is a paraphrase, not a render of `toDiagnosisNote()`, and already drops `missingInformation` | 🔬 | Adding sections to the note alone widens an existing divergence under a heading that says "Posted to ServiceNow". |

## Design

### PGC-1 — Two nullable components; `null` is the abstention

```java
// appended to DiagnosisReport
LikelyCause likelyCause,              // nullable — null IS "not established"
LikelyResolution likelyResolution     // nullable

public record LikelyCause(
        String quotedFinding,         // verbatim from the cited artifact, never paraphrased
        List<String> citedArtifacts,  // ["INC0011902","INC0011455"] — what the reader opens
        InferenceBasis basis,
        List<String> evidenceRefs,
        int supportingCount,          // the denominator: "2 of 2"
        int consideredCount
) {}

public record LikelyResolution(
        ResolutionStep mitigation,    // nullable — restore service now
        ResolutionStep permanentFix   // nullable — stop it recurring
) {}

public record ResolutionStep(
        ResolutionVerb verb,
        String resolutionCode,        // VERBATIM ServiceNow close_code — a CONTROLLED
                                      // vocabulary ("Resolved - Code Fix"), not free text
        String citedArtifact,         // singular ON PURPOSE — one step cites one incident
        List<String> evidenceRefs
) {}

enum InferenceBasis { PRIOR_RESOLUTION, KNOWN_ERROR_DOC, CODE_PATH }
enum ResolutionVerb { CHECK, COMPARE, REPRODUCE_NON_PROD, CONTACT,
                      CONSULT_RUNBOOK, GATHER }
```

⛔ **Rejected: an `InferenceBasis.NONE` constant.** Two encodings of abstention (`null`
component *and* `NONE` basis) diverge the moment one path sets one and not the other.
`null` alone. *(Caught by Codex in `/doc-test dds`.)*

⛔ **Rejected: a `Confidence` field on `LikelyCause`.** Evidence 3–5 say no numeric
confidence; the denominator carries the same information and is **falsifiable by the reader**
— "2 of 2" invites opening both tickets, where "MEDIUM" invites nothing.

### PGC-1a — One quote, two readings (Round 2 correction)

🔬 **Round 2 found a defect in the Round 1 schema.** `close_notes` is **one string carrying
both the cause and the fix**. The mock's own fixture proves it:

> *"Discount was applied after tax in the gateway; reconcile check failed. Fixed order of
> operations in payment_service."*

Round 1 gave `LikelyCause` and `ResolutionStep` each a `quotedFinding`, which forces one of
two bad outcomes: **split the prose by sentence** — inference on free text, exactly what
PGC-1 forbids — or **quote it whole in both places**, duplicating it in a note that has to
stay readable at 3am.

**Resolution: the narrative is quoted once; the resolution section carries the
classification.**

- `LikelyCause.quotedFinding` — the **whole** `close_notes`, verbatim. It genuinely is the
  causal verdict, and it is the only free text J28 ever emits.
- `ResolutionStep.resolutionCode` — the **`close_code`**, verbatim. That field is a
  ServiceNow **controlled vocabulary** ("Resolved - Code Fix", "Resolved - Known Error"),
  not prose.

This is strictly better than the Round 1 shape, because it means **the entire resolution
section is built from closed vocabularies** — `ResolutionVerb` (ours) and `close_code`
(ServiceNow's). Attacker- or mistake-influenced free text can reach the *cause* section as an
attributed quotation, but it can **never reach the resolution section at all**. That is the
structural guarantee PGC-3 was reaching for, now covering the whole component rather than
just the verb.

⛔ **Rejected: sentence-splitting `close_notes` into cause and fix.** It is inference dressed
as extraction; it would fail on the many notes that are one clause; and a wrong split
attributes a human's fix to the wrong half of their own sentence.

### PGC-2 — Mitigation and permanent fix are separate

Different reversibility, evidence requirement, actor, and cost-if-wrong. Merged, they force
the reader to do risk triage at 3am and invite applying a code-fix line with the restart-line
reflex.

🔬 The mock fixture already yields both halves with no edit: `INC0011902` is
`Resolved - Code Fix`, `INC0011455` is `Resolved - Known Error`, each citing its own
`e-sim-*` row.

### PGC-3 — Remediation verbs are a closed enum

**Rule**: `ResolutionVerb` is a closed Java enum with **no `OTHER`**. Jackson rejects any
unknown constant, so mutating verbs are *unreachable in both engines*.

⛔ **Rejected: a deny-list of dangerous verbs.** It loses to paraphrase — "cycle it", "give
it a kick", "let it re-sync" all mean restart. Safety must be structural, not lexical. Same
thesis as J18 and as J8's capability bound: **enforce at the boundary, don't ask the model
nicely.**

🔬 This codifies a policy the code already follows by accident:
`DeterministicDiagnosisEngine:524-538` currently emits only observation verbs (Review / Trace
/ Widen / Reproduce) — by authorship habit, not by rule.

`ENABLE_DEBUG_LOGGING` is deliberately excluded: it fills disks and changes behaviour under
load — a state mutation dressed as observation.

**The boundary this draws** is the sharpest one available, and J4 already drew it implicitly:
`recommendedNextAction`'s own example is *"Confirm the user has the ORDER_SUBMITTER
entitlement"* — a **read-only verification**. Verification steps are self-limiting; a wrong
one costs minutes. Remediation steps change state; a wrong one during an outage deepens it.
**"Go look at X" vs "go change X."**

### PGC-4 — Three validator rules, firing only on positive claims

- **CR-6 — basis↔source agreement.** `basis` must match the `Evidence.source` of its refs
  (`PRIOR_RESOLUTION` → `servicenow-incident`, etc.). `Evidence.source` is already a closed
  vocabulary, so this is a 4-way switch. **This is the rule that catches what `evidenceRefs`
  cannot**: a citation of the *wrong kind*. J13's `danglingRefs` covers fabricated refs; the
  new owners simply join it.
- **CR-7 — MEDIUM ceiling.** `LikelyCause` carries no confidence field, so this constrains
  the *report-level* `confidenceOverall`: a report whose only new signal is a `LikelyCause`
  may not raise it above `MEDIUM`. Analogical transfer is never HIGH.
- **CR-8 — quote fidelity.** `quotedFinding` must be a substring of a cited artifact's text,
  so invention is **mechanically detectable** rather than merely discouraged.

**All three fire only on positive claims**, so they can never turn a passing run into a
mid-demo degrade — the J13/ECI-6 concern ("every new hard rule is a new way to degrade") does
not apply here.

### PGC-5 — Ship restricted to `PRIOR_RESOLUTION`

Each basis rests on a concept that was 🔴 designed-not-built **when this was written**.
**All three have since shipped** (J25, J13, J24 all 🟢 Built as of 2026-08-06), so the reason
for the restriction below no longer holds — see the note after the table.

| Basis | Rests on | If enabled now |
|---|---|---|
| `PRIOR_RESOLUTION` | **J26** ✅ built | safe |
| `KNOWN_ERROR_DOC` | **J25** ✅ *(was 🔴)* | Confluence returns unrelated pages — quote fidelity would prove only that an irrelevant page was quoted accurately |
| `CODE_PATH` | **J13** ✅ *(was 🔴)* | code-evidence ids collide; citations can name the wrong system |

J26's ranker also weights `cmdb_ci` at 0.3, and **J24** ✅ *(was 🔴)* showed reference fields parse to
`""` — so ~30% of the ranking input is dead until J24 lands.

**The enum keeps all three** (the schema is right); emission is restricted. Nothing is thrown
away. *(All three flagged HIGH by Codex in `/doc-test dds`.)*

> **Status 2026-08-06 — the gate is still shut and its reason is gone.** Every dependency this
> restriction waited on has shipped. The restriction itself is real and enforced in two places:
> `AdkDiagnosisEngine`'s instruction says `PRIOR_RESOLUTION` is *"the ONLY value you may use"*,
> and the deterministic engine hardcodes it. Note this is **not** the "restricted by config"
> mechanism the paragraph above describes — no such config key was ever added; the restriction
> is in an instruction string and a literal.
>
> Consequence today: cause and resolution can only ever cite a prior incident's resolution,
> never a known-error runbook or a code path, even though both are now safe to cite. That is
> narrower than designed, and it is not a defect — abstention is legal here and the app never
> claims a basis it lacks — but it is capability that exists and is switched off.
> Tracked as **FND-91**; deliberately NOT flipped as a pre-demo change.

### PGC-6 — Rendering, in both places

`toDiagnosisNote()` gains two sections, in the existing register:

```
Why this may be happening: INC0011902 was closed with this note —
  "Discount was applied after tax in the gateway; reconcile check failed. Fixed order
  of operations in payment_service."
  Based on 2 of 2 similar resolved incidents.

How similar incidents were resolved:
  Mitigation    — INC0011455, closed as: Resolved - Known Error
  Permanent fix — INC0011902, closed as: Resolved - Code Fix
```

Note the asymmetry this makes visible: the cause section carries **one attributed
quotation**, the resolution section carries **only closed-vocabulary values and ticket
numbers**. A reader who wants the fix narrative opens the cited ticket — which is the
behaviour we want, and the same thing they would do if they had found the ticket themselves.

Labels are **historical, not prescriptive** — *"How similar incidents **were** resolved"* is
a factual report that **survives being wrong**, which no prescriptive label does. It
satisfies the advisory-safety criterion structurally rather than lexically.

Abstention renders as *"Why this may be happening: not established from the evidence
gathered (0 of 3 similar resolved incidents carried resolution notes)."* — **"not
established" beats "unknown"**: it implies work was done and is actionable negative
information.

🔬 **`index.html:1251-1258` must change too.** It reconstructs the note client-side rather
than rendering `toDiagnosisNote()`, under a heading that says *"Posted to ServiceNow"*.
Changing only the Java would make the UI assert a note body that differs from what was posted
— a J23-class honesty defect **no existing test would catch**.

### PGC-7 — The ADK path emits nothing until ECI-6 lands (Round 4 conflict resolution)

🔬 **Round 3 found a hard sequencing conflict.** `AdkDiagnosisEngine:440` calls `validate`
*outside* `runAgentAndParse` (whose retry lives at `:686-703`). So on the ADK path, **every
new hard validator rule is a new way to degrade mid-demo** — after the full ~37 s agent run,
J28's CR-6/7/8 would each become an FND-7 fallback trigger. J13's **ECI-6** is the card that
fixes this by moving validation inside the retry; it is 🟡 not built.

⛔ **Rejected: ship CR-6/7/8 hard on deterministic, logged-only on ADK.** This was my first
Round 4 answer and it is **wrong** — J13's card ⛔-rejects engine-asymmetric validation *by
name*: "That is FND-39's defect... Re-introducing asymmetry in the *consequence* re-opens it
in a subtler form." FND-39 was precisely "the validator was wired into ADK only". J28 must
not re-open it from the other side.

**✅ DECIDED — gate emission, not enforcement.** The rules stay **identical and hard on both
engines**. What is gated is whether the ADK path *emits* the components at all: until ECI-6
lands, `AdkDiagnosisEngine` does not populate `likelyCause`/`likelyResolution`, so — because
CR-6/7/8 fire only on positive claims (PGC-4) — they can never trigger there. Zero new
degrade paths, zero asymmetry in the validator.

This is the **same mechanism PGC-5 already uses** to gate `KNOWN_ERROR_DOC`/`CODE_PATH` on
J25/J13, not a second one: J28 ships with a per-basis, per-engine emission gate, and each
gate opens when its prerequisite concept lands. The deterministic engine — the guaranteed
demo path — gets the full feature immediately.

**Dependency added**: J13/ECI-6 blocks J28's ADK path (not its deterministic path).

### PGC-8 — A second demo incident, so abstention can be shown (Round 7 finding)

🔬 `MockServiceNowGateway:45-49` throws `IncidentNotFoundException` for any number but
`INC0010005` — the dataset deliberately "models exactly one incident (J7)".

That is fine today, but J28 makes it a **misrepresentation risk**: this card's own Evidence 1
and 2 establish that **abstention is the common production path**, so a demo that can only
ever show the strong case sells a version of the feature that will not be what anyone sees
against real data. The two sections would look reliably confident on stage and reliably
silent in production — which is exactly the mock-fidelity escape layer that produced
FND-84/85/86/87 in this same batch.

**Rule**: the mock dataset gains **one** second incident whose similar-incident search yields
either nothing or only tickets with empty `close_notes`, so `likelyCause` is `null` and the
"not established (0 of N)" rendering is demonstrable offline.

Cheap, offline, no new connector, and it makes the honest case a **first-class demo beat**
rather than a caveat: *"and here's what it does when it doesn't know — it says so, and tells
you what it looked at."* Per Evidence 2 that is a feature, not an apology.

⛔ **Rejected: demoing abstention by pointing the real connector at a novel ticket.** It
needs the corp laptop, it is not reproducible on stage, and the whole point of the mock
profile is that the demo cannot fail.

## Verification

- **PGC-4** · `DiagnosisReportValidatorTest` — CR-6 rejects `PRIOR_RESOLUTION` citing only
  `e-log`; CR-8 rejects a `quotedFinding` that is not a substring of any cited artifact;
  CR-7 rejects a report raising `confidenceOverall` to HIGH on a `LikelyCause` alone; and
  **a report with both components null passes** — that last one guards against a rule so
  strict it forbids abstention, which PGC-1 makes the *expected* output.
- **PGC-6** · `DiagnosisReportNoteTest` — the two sections render in the good case; the
  abstention case renders "not established" with the denominator; neither section appears
  when both are null.
- **PGC-1 · PGC-1a · PGC-2** · `DeterministicDiagnosisEngineTest` — against the mock fixture,
  `likelyCause.quotedFinding` equals `INC0011902`'s `close_notes` **in full and verbatim**
  (assert against the fixture constant, never a hand-copied string — a hand-copy would pass
  while the production path paraphrased), `citedArtifacts` names both incidents,
  `supportingCount`/`consideredCount` are 2/2, and `likelyResolution`'s steps carry only
  `close_code` values, never prose.
- **PGC-3** · New `ResolutionVerbClosureTest` — deserializing an unknown verb throws, and a
  maintained-list assertion over `ResolutionVerb` fails CI if a constant is added, so
  introducing a mutating verb cannot pass silently.
- **Under `-Padk`** — one test that the model emitting a `LikelyCause` whose `quotedFinding`
  is absent from the cited artifact triggers the J13/ECI-6 repair turn rather than shipping.
  ⚠️ **Blocked on ECI-6, which is not built** (Round 3/4 finding) — see PGC-7.
- **UI** — assert `index.html`'s preview block contains the two new labels, so PGC-6's
  both-places rule has a failing test if only the Java changes.
- **PGC-8** — diagnosing the second mock incident yields `likelyCause == null` and a note
  containing "not established", with the denominator naming how many incidents were
  considered. This is the *abstention* regression, and it is the one most likely to rot:
  every future change to the mock dataset or the ranker can silently turn it into a
  positive-claim case, which would make the honest path undemonstrable again without
  anything failing.
- Baseline to hold: **266** under `-Padk`, green.

## Out of scope

- **Making the similarity signal good** → **J26** (shipped). J28 renders it; J26 owns it.
- **Whether the agent re-reads its own notes** → **J27** (shipped). Without it, J28's cause
  text turns FND-67 drift into *confidence laundering* — run 1's hypothesis becoming run 3's
  stated cause through a chain `evidenceRefs` cannot detect, because every link is a genuine,
  correctly-cited artifact.
- **Confluence relevance** → **J25**; **code-evidence identity** → **J13**; **reference-field
  parsing** → **J24**. PGC-5 gates on all three.
- **A predictive framing** (*"the likely cause is X"*, in TriageMate's own voice). Deferred,
  not rejected — revisit when J26 yields a genuine similarity signal **and** a live probe
  shows real `close_notes` are substantively populated. Those two together are what would
  license the stronger claim. Open questions in
  [`verification-similar-incidents/open-questions.md`](../../../discovery/cause-and-resolution-sections/2-diverge/verification-similar-incidents/open-questions.md).
