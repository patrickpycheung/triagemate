# Exploration: User-Centric / Demo-Impact

**Bias**: the wording *is* the product. Two readers matter — the engineer paged at 2am,
and the judge watching the stage.

**Thesis**: the cause section is safe to ship because it is *question-shaped*, and the
resolution section is safe to ship because it is *historical, not prescriptive*. Both
carry a **denominator** ("2 of 2 similar incidents"), which is the single feature that
separates a shortcut from a rumour. 🤔

## Recommended labels

| Section | Label (verbatim) | Why |
|---|---|---|
| Cause | `Why this may be happening:` | Mirrors the existing opener `What appears to have happened:` 🔬. Question-shaped, so the hedge sits at the **front** where a tired reader actually reads. Present tense — the incident is usually still live. |
| Resolution | `How similar incidents were resolved:` | A **report of history**, not advice. If the fix doesn't apply here, the sentence is *still true* — it survives being wrong, which no prescriptive label does. |

**Rejected**: `Root cause` (in an ITIL shop that is a formal Problem Management artifact
— claiming one in a first-pass note loses the senior room), `Likely cause` (over-claims
on thin evidence), `Possible cause` / `Hypothesis` (skipped as noise), `One explanation
consistent with the evidence` (doesn't scan).

**Order**: cause *after* `Suggested assignment group`, resolution *after* `Recommended
next check`. At 2am the first question is "is this mine", not "why". Showing the plural,
hedged system shortlist **before** a single-threaded story is anti-anchoring — lead with
the cause and you send them down a wrong path faster. The next check then *tests* the
cause; the prior fix lands last, where it is safest.

**Always render both, even when empty** ("not established"). A vanished section can't be
told apart from a search that never ran — the inverse of the FND-8 failure class.

## Banned phrases (enforceable as a unit test, cf. `PromptInjectionGuardrailTest` 🔬)

`root cause` · `confirmed` · `the issue is caused by` · `this will fix` · `should
resolve the issue` · `immediately` / `urgent` / `critical` (asserting urgency **is** a
priority change in prose — it breaks the advisory promise 🔬) · `you should` / `I
recommend` / any second person (the note has none today 🔬) · `based on my analysis` ·
`it's worth noting that` · `in summary` · `try restarting` (any remediation not traced to
`resolutionNotes` or a runbook) · stacked hedges (`it appears there may possibly be`) ·
`deep dive` / `leverage` / `robust` · `N/A` as a cause · emoji.

**One hedge per sentence. One disclaimer per note** — per-section warnings cause
disclaimer fatigue and readers skip all of them, including the one that matters.

## Final proposed note rendering (verbatim, as it would appear in ServiceNow)

```
[AI Triage · First-pass diagnosis — advisory only]

What appears to have happened: Orders sometimes don't go through at checkout — A few customers reported that when they try to submit an order it just fails with an error and the order is not placed. Happens intermittently. One example order id they gave was INC-ORD-4471.

Likely involved systems: Payment Service (86%), Order Portal (55%)
Suggested assignment group: Payments Platform Support — medium confidence

Why this may be happening: a percentage discount is applied after tax, while the expected total discounts before tax. Named as the common cause on the KB001234 reconciliation runbook, and the closing cause on 2 of 2 similar resolved incidents. — medium confidence

Recommended next check: Review payment_service.py:44, which emits 'PAYMENT_RECONCILE_MISMATCH' — the log line correlated to this incident.

How similar incidents were resolved: both matching incidents were closed as code fixes to the same service. INC0011902 (Resolved - Code Fix) — "Discount was applied after tax in the gateway; reconcile check failed. Fixed order of operations in payment_service." INC0011455 (Resolved - Known Error) — workaround applied, then a code fix.

AI-assisted and advisory. No reassignment, closure, or priority change has been made — the assigned engineer decides. The cause above is a hypothesis, not a finding; the prior fixes are what closed other tickets, not instructions for this one. Sources are in the comment above.
```
