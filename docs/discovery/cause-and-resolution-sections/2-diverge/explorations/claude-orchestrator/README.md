# Exploration — Claude (orchestrator's own read)

**Bias**: whole-context. Reads the codebase's *stated safety arguments* and tests
whether they still hold once this feature exists.

## Core finding: the guardrail's second leg breaks

`PromptInjectionGuardrailTest`'s javadoc states the guarantee J8 actually relies on —
even a **fully compromised** model has bounded blast radius because:

1. `ServiceNowGateway` exposes no reassign/close/priority-change method. *(Still true.)*
2. The orchestrator always posts two fixed-format notes, and injected text in a report
   field is **"rendered as an inert, verbatim string, not specially interpreted or acted
   on."** *(Still true of the program. **False of the reader.**)*

Leg 2 is a claim about *machine* interpretation. It is sound for `candidateSystems` or
`reportedSymptom`, where the worst case is a human reading a wrong system name.

A **"how to resolve this"** field inverts it. The field's entire purpose is that a human
reads it as an instruction and executes it. The blast radius is no longer bounded by
machine capability — it is bounded by **human compliance**. TriageMate acquires an
execution engine it does not control and cannot sandbox: the on-call engineer.

Attack path, fully within the current architecture: attacker edits a Confluence page the
triage will consult → text is gathered as evidence → surfaces in "Likely resolution" →
engineer with production credentials runs it. Every hop already exists and is 🔬 Spiked
except the last field, which is what we are proposing to add.

**This is not an argument against the feature.** It is the constraint that decides its
shape: the resolution section must be built so that *no attacker-influenceable text
reaches it as a directive*. Sourcing it from a **closed vocabulary** (resolution codes,
templated actions) rather than free text is the structural fix. Free-text passthrough
from wiki/log/ticket content into a resolution field is the one thing that must not ship.

## Secondary findings

- **FND-67 self-poisoning widens.** Assertive cause text read back on a later run is
  worse fuel than today's fields. `isAiAuthoredNote` filters by prefix — needs
  re-verification against the new sections, not assumption. 🔍 Inferred.
- **`close_notes` is unvetted human free text.** The cheapest grounded source
  (`resolutionNotes`) is *also* attacker/mistake-influenceable — it is whatever a hurried
  engineer typed years ago. "Grounded" ≠ "safe to repeat verbatim". 🔬 Spiked (it is a
  raw ServiceNow field, `RealServiceNowGateway.java:135-140`).
- **Degree-not-kind cuts both ways.** `recommendedNextAction` already gives directive
  advice, so the line is already crossed — but it is deliberately a *verification* step
  ("Confirm the user has the entitlement"), which is read-only and self-limiting.
  A *remediation* step is not. The distinction between **"go look at X"** and
  **"go change X"** is the sharpest available safety boundary, and it is already
  implicitly the one the codebase drew. 🔍 Inferred.

## Position

Build it, with the cause/resolution sections **structurally incapable of emitting
free-text remediation from gathered content**. Prefer: cite what past incidents did
(attributed, linked, quoted as history — *"INC0011902 was resolved by…"*) over
generating what this incident should do. Attribution converts an instruction into a
citation, which is both safer and more useful.

Full reasoning: [exploration.md](exploration.md)
