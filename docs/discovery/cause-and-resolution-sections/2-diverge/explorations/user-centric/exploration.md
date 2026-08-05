# User-Centric / Demo-Impact exploration — cause & resolution wording

**Bias**: this feature ships as *strings*. The engine can be perfect and the feature still
fails if the label makes a tired person believe a guess, or makes them skip a good lead.
Two readers: the engineer paged at 2am, and the hackathon judge.

Trust markers: 🔬 verified in code/docs · 📚 established practice · 🔍 inferred from
evidence in this repo · 🤔 reasoned opinion · ❓ unknown.

---

## 1 · Audit of the voice we have to match 🔬

`DiagnosisReport.toDiagnosisNote()` is disciplined in ways worth naming, because the new
sections must not break any of them:

- **Every label hedges.** `What *appears* to have happened`, `*Likely* involved systems`,
  `*Suggested* assignment group`, `*Recommended* next check`. No unhedged assertion anywhere.
- **Zero second person.** No "you", no "I", no imperative. Even the directive line is a
  noun phrase (`Recommended next check:`), not a command.
- **The hedge is in the label, not the body.** `Suggested assignment group: Payments
  Platform Support` — the body is a bare fact; the label carries the uncertainty. The
  single most transferable pattern in the file.
- **Confidence is a word after an em dash**: `— medium confidence`, from
  `Confidence.name().toLowerCase()`. Reuse it; don't invent a second vocabulary.
- **One disclaimer, at the end, well written.**
- **The UI has no percentages and no progress bars** — `index.html:1187` records this as
  *direct product feedback* 🔬. Any "confidence bar" proposal re-litigates a settled call.

---

## 2 · Choosing the cause label: the two-failure-mode test

A cause label fails in two opposite directions. **Over-trust**: the reader acts on a
hypothesis as if it were a finding. **Dismissal**: the reader's eye classifies the line as
boilerplate hedging and skips it, so the feature costs vertical space and returns nothing.
The right label is low on *both*, not minimal on one. 🤔

| Candidate | Over-trust | Dismissal | Verdict |
|---|---|---|---|
| `Root cause:` | severe | none | **Ban.** In an ITIL shop "root cause" is a formal Problem Management artifact with a process behind it. A first-pass triage note claiming one is a category error, and it is the exact phrase that makes a senior engineer distrust the whole tool. |
| `Cause:` | severe | none | Bare assertion; breaks the "every label hedges" rule. |
| `Likely cause:` | moderate | low | Best parallel to `Likely involved systems:`, but "likely" is a >50% claim the engine often cannot support. Would have to vary to `Possible cause:` on weak evidence — a varying label is worse than a varying body. |
| `Possible cause:` | low | **moderate** | Reads as "we guessed". Skipped by the exact reader who most needs it. |
| `Hypothesis:` | low | moderate | Right for an SRE, wrong for the L1 service-desk agent who also reads this note. Jargon. |
| `One explanation consistent with the evidence:` | very low | **severe** | Legally immaculate, unreadable. 9 words before any information. Fails the 10-second test outright. |
| **`Why this may be happening:`** | **low** | **low** | **Chosen.** |

Why the winner wins:

1. **It mirrors the existing opener.** `What appears to have happened:` is the only
   question-shaped label in the note, and it opens it. `Why this may be happening:` is its
   sibling. The note gains a *what → why* spine rather than a new species of line. 🔬
2. **The hedge is front-loaded.** At 2am people read the first half of a sentence. A
   trailing hedge ("…, possibly") is read *after* the claim has already landed. `may be`
   sits at word four of the label, before any content.
3. **Present tense.** `have happened` is past — correct for a symptom report. A cause is a
   standing condition and the incident is usually still live. `is happening` matches the
   reader's situation. 🤔
4. **It doesn't need to vary.** "May" is honest at high evidence (the citation carries the
   strength) and honest at low. Stable labels are scannable; varying ones train people to
   re-read the label instead of the content.

---

## 3 · The resolution label: make it historical, not prescriptive

This is the load-bearing move in the whole exploration.

A prescriptive label — `Suggested fix:`, `How to resolve:`, `Recommended remediation:` —
creates an obligation the product cannot meet. TriageMate has never fixed anything. It has
read what other people did. Every prescriptive label therefore needs heavy hedging bolted
on, and hedged advice is the worst of both worlds: still actionable enough to be dangerous,
too qualified to be useful.

**`How similar incidents were resolved:`** dissolves the problem instead of hedging it.
It is a factual claim about `ResolvedIncident.resolutionCode` / `.resolutionNotes` — fields
both gateways already fetch and nothing currently reads 🔬 (per `STATUS.md`). If the prior
fix does not apply to this incident, **the sentence is still true**. It survives being
wrong. No prescriptive label does.

Secondary benefits: it is *more* useful, not less ("two engineers hit this and both fixed
it in `payment_service`" beats "we suggest you fix `payment_service`"); it names its own
source class in the label, so the reader calibrates before reading the body; and it
degrades into a valuable signal — *"no resolved incident with a comparable symptom was
found — this one may be new."* At 2am, "this is novel" is worth knowing. 🤔

Runner-up: `What has fixed this before:` — punchier, but "this" implies the same incident
and slightly over-claims. Fallback if the longer label crowds the UI heading.

---

## 4 · The denominator — the one feature that makes cause honest

A bare "based on 3 similar incidents" hides how many *disagreed*. Report both halves:

> `2 of 2 similar resolved incidents` · `1 of 4 similar resolved incidents`

The denominator is the hedge. It is deterministic-computable from `findSimilarIncidents`
with no LLM 🔬, it needs no new vocabulary, and it degrades to `0 of 3`, which reads as an
explicit warning rather than an absence. It is also the phrase a judge remembers.

**Design rule:** *never render a cause sentence without its warrant on the same visual
line.* A cause with a denominator and a KB link is a shortcut; a cause without one is a
rumour, and rumours are exactly how a triage tool "sends them down a wrong path faster".

**Citation style in the note**: cite by human-readable name (`the KB001234 runbook`,
`INC0011902, INC0011455`), **not** by internal evidence id (`[e-kb-KB001234]`). The
ServiceNow note is read by humans; `evidenceRefs` remain in the J4 JSON where the validator
needs them, and become anchors in the UI. Bracketed internal ids in a work note read as
machine exhaust. 🤔

---

## 5 · Full renderings

### 5a · Strong evidence (the demo incident, INC0010005)

```
Why this may be happening: a percentage discount is applied after tax, while the expected total discounts before tax. Named as the common cause on the KB001234 reconciliation runbook, and the closing cause on 2 of 2 similar resolved incidents. — medium confidence

How similar incidents were resolved: both matching incidents were closed as code fixes to the same service. INC0011902 (Resolved - Code Fix) — "Discount was applied after tax in the gateway; reconcile check failed. Fixed order of operations in payment_service." INC0011455 (Resolved - Known Error) — workaround applied, then a code fix.
```

Note the two-sentence shape: **answer first (≤20 words), warrant second.** A pathology
report reads this way. The reader who stops after sentence one still got the cause; the
reader who continues learns whether to believe it.

### 5b · Thin evidence

```
Why this may be happening: 1 of 4 similar resolved incidents was closed on the same cause — a stale connection pool in the order gateway. The other 3 closed on unrelated causes. — low confidence

How similar incidents were resolved: the one matching incident (INC0009991, Resolved - Workaround) was closed by restarting the gateway pool; no code fix was recorded. The other three were resolved for different reasons and are not repeated here.
```

`The other 3 closed on unrelated causes` is the honesty. It is also what stops a tired
engineer chasing a 25% lead as if it were the answer.

### 5c · No evidence

```
Why this may be happening: not established. No runbook named a cause for this error, and no resolved incident with a comparable symptom was found.

How similar incidents were resolved: no resolved incident with a comparable symptom was found — this one may be new.
```

**These sections must still render when empty.** This is the strongest user-centric claim
here, and the codebase already agrees with it in principle: FND-8's failure class is
"narrating something that did not happen" 🔬. The inverse — silently not narrating a search
that *did* happen and came up empty — is the same honesty bug wearing a different hat. If
the section vanishes, the reader cannot tell whether TriageMate looked and found nothing,
or never looked. Rendering "not established" costs two lines and buys the tool's
credibility for every future run.

("not established" beats "unknown" — which reads as a failure — and "insufficient data" —
which reads as a machine excuse. It is what a clinician writes: *we looked, we are not
saying*.)

### 5d · Contradicted

When `contradictingEvidence` is non-empty, the cause body must acknowledge it inline rather
than leaving the reader to reconcile two cards:

```
Why this may be happening: 2 of 2 similar resolved incidents point to a discount-ordering bug in the gateway. Against that: the CMDB owner for this CI is Order Portal, and no Order Portal errors appear in the searched window. — low confidence
```

`Against that:` is the cheapest possible in-body contradiction marker and matches the
note's plain register.

---

## 6 · Order on the page

**Proposed note order** (insert-only; nothing existing moves):

1. `What appears to have happened:` — symptom
2. `Likely involved systems:` — where
3. `Suggested assignment group:` — who
4. **`Why this may be happening:`** ← new
5. `Recommended next check:` — what now
6. **`How similar incidents were resolved:`** ← new
7. `Still missing:` — caveats
8. Advisory disclaimer

**Why cause is #4, not #2.** The tempting user-centric move is to lead with "why" — it is
the emotionally satisfying answer and the demo payoff. It is wrong. Most notes an engineer
opens at 2am are **not theirs**, so the fastest exit ("is this mine?") must stay at the
top. More importantly: the system shortlist is *plural and hedged*, and the cause is
*singular and narrative*. Showing the plural first is an anti-anchoring device — the reader
meets ambiguity before they meet a story. Reverse the order and the story frames the
shortlist instead of the shortlist tempering the story. 🤔

**Why resolution is #6, after the next check.** `Recommended next check` is diagnostic
("Review payment_service.py:44") and the prior fix is corrective. Adjacent, they blur.
Separated by the check, the note reads as *hypothesis → experiment → what worked for
others*, which is the actual workflow. It also places the most dangerous-to-act-on content
last, immediately above the disclaimer that governs it.

**Disclaimer amendment** — one sentence appended, keeping all safety in one place:

> `The cause above is a hypothesis, not a finding; the prior fixes are what closed other
> tickets, not instructions for this one.`

Do **not** add per-section warnings. Disclaimer fatigue is real: the third warning teaches
the reader to skip all three, including the one that matters. 📚

---

## 7 · UI sketch

**Placement**: two new cards immediately after `Suggested assignment`, before `Who to talk
to`. That is above the fold, inside the demo screenshot, and it keeps the diagnosis block
(what / where / who / why / how) together, with the supporting apparatus (contacts,
evidence, trace) below it.

**Two cards, not one.** The poster styling gives each card an uppercase accent heading with
a red rule 🔬; two stacked red headings — `WHY THIS MAY BE HAPPENING` and `HOW SIMILAR
INCIDENTS WERE RESOLVED` — are legible from the back of the room and are *themselves* the
proof that the product now answers why+how. One merged card hides that at a distance.

```
┌────────────────────────────────────────────────────────────┐
│ ▌WHY THIS MAY BE HAPPENING                       [MEDIUM]  │
│ 2 of 2 similar resolved incidents · KB001234 runbook       │   ← .src muted, 13px
│                                                            │
│ A percentage discount is applied after tax, while the      │
│ expected total discounts before tax.                       │
│                                                            │
│ ▏KB001234 — "Common cause: a percentage discount applied   │   ← .ev strip, existing style
│ ▏ AFTER tax…"                          [confluence link]   │
└────────────────────────────────────────────────────────────┘
┌────────────────────────────────────────────────────────────┐
│ ▌HOW SIMILAR INCIDENTS WERE RESOLVED                       │
│ 2 of 2 · both code fixes                                   │
│ ▏INC0011902  Resolved - Code Fix                           │
│ ▏ "Fixed order of operations in payment_service."          │
│ ▏INC0011455  Resolved - Known Error                        │
│ ▏ Workaround applied, then a code fix.                     │
└────────────────────────────────────────────────────────────┘
```

**Uncertainty, visually — reuse only what exists:**

- **`confidencePillHtml`** for the cause card (LOW green / MEDIUM amber / HIGH red) 🔬.
- **No percentage, no progress bar.** Settled by direct product feedback in
  `index.html:1187` 🔬 — do not reopen it.
- **The denominator line** (`2 of 2 similar resolved incidents · KB001234 runbook`) in the
  existing muted `.src` treatment, in the same slot as the explainer paragraph under *Who
  to talk to*. That line *is* the uncertainty visualisation; it beats a bar because it is
  falsifiable.
- **No-evidence state**: render the card with body text in `var(--muted)` and **no pill**.
  Absence of a pill is the signal, and `confidencePillHtml` already degrades to `—` for
  unrecognised input 🔬. A greyed card that says "not established" is the honest visual.
- **Evidence citation** reuses the `.ev` left-rule strip so a cause looks visually
  identical to the evidence supporting it — reinforcing "one click from its evidence" from
  `toSourcesNote()` 🔬.

---

## 8 · The 2am test

Ten seconds, four questions, in this order: *is this mine* (team) → *is it real*
(confidence + evidence count) → *what now* (next check) → *why* (cause). Cause is fourth in
the 10-second scan and **first in the 30-second scan** — it is what decides whether the
reader trusts answers 1–3 at all.

Does a cause section help, or send them down a wrong path faster? **Both, and the
discriminator is the denominator.** Which is why §4's rule is not decoration: a cause
sentence without its warrant on the same visual line should not ship.

---

## 9 · The judge test — staging it

The runbook's current stage narration is *sources → diagnosis → the log↔code citation →
who to talk to → the trace*, and the D3 punchline is that a tool-less frontier model
"cannot name `payment_service.py:44`" 🔬.

**These sections sharpen D3 rather than duplicating it.** A tool-less frontier model will
happily produce a confident, fluent "likely cause" — fabricating causes is the thing LLMs
are *best* at. So the contrast stops being "we have more detail" and becomes:

> "Both of them told you why. Only one of them told you **how many times that was right
> before** — and showed you the two tickets."

**The single screenshot that sells it**: the two new cards stacked, amber MEDIUM pill on
the cause, `2 of 2 similar resolved incidents · KB001234` underneath, and the literal
`Resolved - Code Fix` note quoted below. The whole thesis in one frame, no scrolling.

**Recommended extra beat (15 seconds): demo the failure mode.** Show the greyed "not
established" state. A team that stages its own null result reads as trustworthy in a way no
amount of confident output does, and it inoculates against the judge question everyone asks
("what happens when it's wrong?").

⚠️ **Blocker on that beat** 🔬: `MockServiceNowGateway.getIncident` throws
`IncidentNotFoundException` for any number other than `INC0010005` (FND-54), so there is no
second fixture to demo the weak/empty states live. Either add one thin fixture incident
with no similar incidents and no runbook match (cheap, offline, no new connector), or stage
the beat as a static screenshot and say so. Do not narrate it as live.

---

## 10 · Anti-patterns — the ban list, and how to enforce it

What makes this read as generic AI filler, with the reason each is worse than merely ugly:

| Banned | Why |
|---|---|
| `root cause` | Claims a Problem Management artifact a first-pass triage cannot produce. |
| `confirmed`, `the issue is caused by`, `this will fix`, `should resolve the issue` | Unhedged assertion / future-tense certainty about an action nobody took. |
| `immediately`, `urgent`, `critical` | **Asserting urgency is a priority change in prose.** The note's own disclaimer promises no priority change has been made 🔬 — these words break that promise lexically. |
| `you should`, `I recommend`, `try restarting`, any second person | The note has zero second person today 🔬. An imperative is an authorised instruction in disguise. |
| `based on my analysis`, `as an AI` | The note is a report, not a chat turn. |
| `it's worth noting that`, `please note`, `in summary`, `overall` | Filler. A six-line note has no summary. |
| `it appears there may possibly be` | Stacked hedges. **One hedge per sentence.** |
| `this suggests a potential issue with` | Content-free. |
| `deep dive`, `leverage`, `utilize`, `robust`, `seamless` | Consultant register; alien to a work note. |
| `N/A` as a cause | Write the degradation sentence instead. |
| Emoji | Not in this document class. |

**Enforcement is cheap and already patterned**: a unit test asserting these substrings never
appear in `toDiagnosisNote()` output, mirroring `PromptInjectionGuardrailTest`, which already
asserts over `recommendedNextAction` 🔬. That covers the ADK path (where prose is generated)
and future-proofs the deterministic path against a well-meaning template edit.

---

## 11 · Open questions

- ❓ Does L1 service desk read these notes, or only the assigned engineer? If L1 reads
  them, the resolution section may need to say explicitly that L1 must not apply the fix.
- 🤔 A customer-facing `comments` write (`--triage.servicenow.write-field=comments` 🔬)
  puts a cause hypothesis in front of a customer. Recommend suppressing both new sections
  on that field and emitting them only to `work_notes`.
- 🔍 If both sections are frequently empty on real tickets, "always render" becomes two
  lines of noise per ticket. Measure on the first ten live runs; fallback is always-render
  for cause, conditional for resolution.
