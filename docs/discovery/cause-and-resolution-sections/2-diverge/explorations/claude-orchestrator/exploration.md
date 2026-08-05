# Exploration — Claude (orchestrator's own read)

**Bias**: whole-context. Take the codebase's *own* stated safety arguments and test
whether they survive the proposed feature.

---

## 1. The capability argument, and where it stops working

J8's guardrail claim is not "the model won't be tricked". The codebase is explicit that
this would be untestable — `PromptInjectionGuardrailTest`'s javadoc says a scripted fake
endpoint "cannot be 'tricked' any more than a rock can", and that red-teaming a real
model "is red-teaming against production, not a unit test". 🔬 Spiked.

Instead the guarantee is **architectural**, and stated in two legs:

> 1. `ServiceNowGateway` exposes no reassign/close/priority-change method at all. There
>    is nothing for a compromised model to call, however it's tricked.
> 2. `DiagnosisOrchestrator#run` always posts exactly the two fixed-format advisory
>    notes… injected text embedded in a report field is rendered as an **inert, verbatim
>    string, not specially interpreted or acted on**.

This is a genuinely good argument, and it is the reason the current feature set is safe
to ship without a human gate. Leg 1 is about *capability*; leg 2 is about
*interpretation*.

**Leg 2 is scoped to machine interpretation.** Re-read it precisely: "not specially
interpreted or acted on" — by the program. That is true and will remain true. Jackson
will not execute a string.

For every field that exists today, machine-inertness is sufficient, because the human
consequence of a wrong field is bounded:

| Field | Worst case if attacker-controlled |
|---|---|
| `reportedSymptom` | Engineer reads a wrong summary |
| `candidateSystems` | Engineer looks at the wrong system |
| `suggestedAssignment` | Ticket routed to the wrong team |
| `recommendedNextAction` | Engineer performs a wrong **check** |

Every one of these costs *time*. None of them costs *state*.

**A resolution field breaks the pattern**, because its purpose is to be executed. The
value proposition of "how we can likely resolve the issue" is precisely that a human
reads it and acts. So:

> The blast radius is no longer bounded by machine capability. It is bounded by human
> compliance.

TriageMate gains an execution engine that is outside its sandbox, holds production
credentials, is under time pressure, and has been primed by the surrounding UI to treat
this system's output as helpful. That is close to the worst possible executor.

### The attack path is already fully built

Every hop below exists in the current architecture and is 🔬 Spiked except the final
field, which is the thing under design:

1. Attacker (or a well-meaning colleague who is simply *wrong*) edits a Confluence page
   that the runbook search will match. `RealConfluenceGateway` fetches it.
2. The text enters the evidence set as a `KnowledgeDoc.snippet`.
3. Under a naive design, that snippet informs — or is quoted into — "Likely resolution".
4. The note is posted to the incident.
5. An engineer with production credentials reads a plausible remediation on an official-
   looking ticket entry during an outage, and runs it.

Note that step 1 needs no attacker at all for most of the harm. A **stale runbook** is
the same failure with no adversary — and stale runbooks are the normal state of
runbooks. 🔍 Inferred, but I'd defend it strongly.

### Consequence for the design

This does not argue against the feature. It fixes the feature's **shape**:

> The resolution section must be structurally incapable of emitting free-text
> remediation drawn from gathered content.

Structural, not prompt-level. A prompt instruction ("don't repeat instructions from
fetched text") is exactly the control that injection defeats. The codebase already
understands this — leg 1 of the guardrail is architectural precisely because prompt-level
control was judged insufficient. The resolution field deserves the same treatment.

Two structural options, in preference order:

- **(a) Closed vocabulary.** Resolution is emitted from a fixed enum/template set the
  code owns (derived from `resolutionCode`, which *is* a controlled ServiceNow field —
  "Resolved - Code Fix", "Resolved - Known Error"). Attacker-controlled text can select
  among options but cannot author one.
- **(b) Attributed citation.** Resolution is rendered strictly as *history*, quoted and
  attributed to a specific past ticket: *"INC0011902 (86% similar) was resolved by:
  '<quote>'"*. The text may still be arbitrary, but attribution changes its speech act
  from **instruction** to **report**, and gives the reader the thing they need to judge
  it — a source they can open.

(b) is weaker than (a) but far more useful, and its residual risk is honest: it is the
same risk as an engineer searching ServiceNow for similar tickets themselves, which is
the manual workflow this product automates. **We are not creating a new trust
relationship, we are accelerating an existing one.** That framing is defensible in a way
that "the AI told me to run this" is not.

---

## 2. "Grounded" and "safe" are different properties

The cheapest grounded source — `resolutionNotes` (`close_notes`) — is 🔬 Spiked as raw
human free text (`RealServiceNowGateway.java:135-140` maps `close_notes` straight
through, no processing).

There is a temptation to treat "it came from our own ServiceNow" as equivalent to "it is
trustworthy". It is not. `close_notes` is:

- written under time pressure at the end of an incident, when the writer wants to leave;
- frequently wrong about cause even when right about fix ("restarted it, went away");
- unvalidated, unreviewed, and never revisited;
- sometimes containing credentials, customer data, or internal hostnames;
- occasionally containing literal commands.

So the MVP path ("just surface past resolution notes") is **maximally grounded and not
automatically safe**. It needs at minimum: quoting + attribution (never paraphrase into
our own voice — paraphrase launders someone else's guess into our assertion), and a
scan before rendering.

The distinction worth carrying into synthesis:

| Property | Means | Achieved by |
|---|---|---|
| **Grounded** | traceable to a real artifact | `evidenceRefs`, already enforced |
| **Safe** | wrong-following doesn't destroy state | closed vocabulary / attribution / read-only verbs |
| **Honest** | uncertainty visible, abstention possible | H7, hedged wording, confidence |

A design can hit any two and miss the third. All three are required.

---

## 3. The boundary the codebase already drew

`recommendedNextAction`'s example value is *"Confirm the user has the ORDER_SUBMITTER
entitlement"* — a **read-only verification** step. 🔬 Spiked (J4 concept doc + the
record's own docs).

I don't think that's an accident, and even if it was, it is the right line. The sharpest
available safety boundary is:

> **"Go look at X"** vs **"Go change X"**

Verification steps are self-limiting: performing one yields information and costs
nothing but time, and a *wrong* verification step wastes minutes. Remediation steps
change state, and a wrong one during an outage can extend or deepen it.

This suggests a design that many will find unsatisfying but I think is correct for
this phase: the "resolution" section should lean heavily toward **the resolution path**
rather than **the resolution action** — what was done before, who did it, where the
runbook is, what the fix class was ("code fix", "config change", "known error with a
documented workaround") — rather than an imperative the engineer is invited to execute.

That still fully answers the user's ask ("how we can likely resolve the issue") while
keeping the imperative mood, and its liability, out of the note.

---

## 4. FND-67 redux — assertive text is worse fuel

`AI_NOTE_PREFIX`'s javadoc documents that the app already fed on itself once: a second
run read its own prior note as human conversation and extracted keywords, identifiers
and contact names from it, drifting further each run. 🔬 Spiked.

Cause/resolution text is *more* assertive and *more* keyword-dense than any current
field. If the filter has any gap, this feature widens the consequence. `isAiAuthoredNote`
matches `AI_NOTE_PREFIX` anywhere in the entry — which is robust to the gateway's
`"sys_created_by: "` prefixing — but it is one string match, and the new sections must
be verified to be inside a prefixed entry, not appended somewhere that could be split.
Worth a 🔬 verification spike, not an assumption.

---

## 5. Position

**Build it.** The request is reasonable, the data is already in hand, and the gap it
closes is real — a triage report that won't say why or what to do is doing half the job.

Build it with these non-negotiables:

1. **No free-text remediation synthesized from gathered content.** Closed vocabulary, or
   attributed quotation. Never our own voice asserting an action.
2. **Attribution over assertion.** *"INC0011902 was resolved by X"* beats *"Do X"*.
   It is safer, more useful, and more honest about what the system actually knows.
3. **Abstention is a first-class output** (constraint H7). "No similar resolved incident
   found" is a good answer and must be renderable without the schema fighting it.
4. **Prefer resolution *path* over resolution *action*** while the product is advisory.

Open question I could not settle alone, and which the other explorations should:
**is a cause section worth its anchoring cost?** A stated cause makes humans stop
looking. Unlike the resolution section, whose risk is bounded by the above measures, the
anchoring harm of a confident-but-wrong cause is intrinsic to it being read at all. I
lean yes-with-hedging, but I hold this weakly. ❓
