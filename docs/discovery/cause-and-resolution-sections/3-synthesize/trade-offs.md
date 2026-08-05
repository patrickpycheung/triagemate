# Conflicts and trade-offs

Where the explorations genuinely disagreed. These are the most valuable output — the
convergences (patterns.md) were largely predictable once the evidence was in.

---

## C1 — Schema shape: flat vs nested. **Real conflict, resolved on evidence.**

| Exploration | Proposal | Cost |
|---|---|---|
| minimum-viable | 3 flat fields: `String likelyCause`, `String likelyResolution`, shared `List<String>` refs | ~3h |
| first-principles | `quotedFinding` / `citedArtifact` / `evidenceRefs` + warrant enum, ranked | — |
| technical-depth | `LikelyCause(statement, InferenceBasis, Confidence, refs)` + `LikelyResolution(mitigation, permanentFix)` as nested records | — |
| risk-averse | `List<ResolutionStep>` with `verb` from a closed enum | — |

**The discriminating fact** is a technical one that only minimum-viable caught:
🔬 `AdkDiagnosisEngine.java:170-175` already carries a warning about the model's handling
of **nested** structures — the prompt has been tuned to fight exactly that failure. So
technical-depth's nested records buy validator precision at the cost of walking into a
known, already-documented LLM failure on the flagship path.

**Resolution — take the nesting but flatten what the model emits.** The conflict is
false: the model does not have to emit the shape the record stores. Keep one level of
nesting for the *fix/mitigation* split (P3 makes it non-negotiable) and for the closed
`verb` enum (P5 makes it non-negotiable), but keep each component's own fields flat and
scalar, and never require the model to nest more than one level. `basis` and `verb` as
closed enums are cheap for a model to emit correctly *because they are enums* — Jackson
rejects anything else, which converts a model error into a validation failure rather
than a bad note.

**What gets dropped**: technical-depth's `Confidence` field on each component. P4 says no
numeric confidence, and first-principles' MEDIUM-ceiling argument means the value would be
a near-constant. The denominator (*"2 of 2"*) carries the same information honestly and
for free.

## C2 — Confidence display: three incompatible answers

- first-principles: use the existing `Confidence` enum, validator-enforced **MEDIUM
  ceiling** (analogical transfer is never HIGH).
- prior-art: show **no** confidence indicator — displayed high confidence measurably
  degrades human performance, and explanations alone don't mitigate.
- user-centric: reuse the existing `confidencePillHtml`, and make the **denominator** the
  uncertainty display.

**Resolution — user-centric wins, with first-principles' ceiling as a validator rule.**
The denominator is strictly better than a label because it is *falsifiable by the reader*:
"2 of 2" invites clicking both tickets. Prior-art's objection is specifically to
**high**-confidence displays, and the MEDIUM ceiling makes HIGH unreachable, so the two
positions are compatible once the ceiling exists. No new UI primitive is introduced.

## C3 — Label framing: the reframe the user should get to weigh

**This is the one conflict that is not ours to settle.**

The request was *"what likely **caused** the issue"* and *"how **we can** likely resolve
it"* — predictive and prescriptive. The convergent answer across seven explorations is
historical and attributed:

| Asked for | Converged on |
|---|---|
| "What likely caused the issue" | "Why this may be happening" — ≥2 hedged hypotheses, or *not established* |
| "How we can likely resolve the issue" | "How similar incidents were resolved" — quoted `close_notes`, attributed to a ticket |

The second is a **materially different claim**. It is defensible where the literal
version is not, it survives being wrong, and it needs no causal substrate the app lacks.
But it is not what was asked for, and on a novel incident with no precedent it says
nothing at all.

Both are built the same way and share all their plumbing, so this is a **wording and
framing choice, cheap to change late** — not an architecture fork. It goes to the Phase 4
checkpoint as the one genuine decision for the operator.

## C4 — What "grounded" buys you. **Unresolved tension, and it should stay unresolved.**

minimum-viable's case: quoting `close_notes` is maximally grounded, needs no LLM, ~3h.

claude-orchestrator's counter: `close_notes` is **unvetted human free text** — written
under time pressure by someone who wanted to go home, frequently wrong about cause even
when right about fix, occasionally containing credentials, hostnames, or literal commands.
"It came from our ServiceNow" ≠ "it is true".

**Neither is wrong.** The properties are orthogonal and all three are required:

| Property | Means | Achieved by |
|---|---|---|
| **Grounded** | traceable to a real artifact | `evidenceRefs` — already enforced |
| **Safe** | wrong-following doesn't destroy state | closed verb enum, attribution, read-only verbs |
| **Honest** | uncertainty visible, abstention possible | H7, denominator, MEDIUM ceiling |

The mitigation is **quote, never paraphrase**. Paraphrasing launders someone else's guess
into TriageMate's assertion. Verbatim quotation with attribution keeps the epistemic
ownership where it belongs, and gives the reader the one thing they need to judge it — a
ticket number they can open.

## C5 — Two latent traps that would have shipped

Neither is a disagreement; both are things a reflexive implementation gets wrong.

1. 🔬 **`index.html:1235-1238` hand-mirrors `toDiagnosisNote()`.** The UI renders its own
   copy of the write-back preview. Changing only the Java makes the UI *assert a note body
   that differs from what was actually posted* — a J23-class honesty defect that **no
   existing test would catch**. Any change to the note must change both.
2. 🔬 **`AdkDiagnosisEngine.stampGeneratedAt` rebuilds the record positionally**, so it
   would silently drop new components. The arity change makes this a compile error instead
   of a silent data loss — which is luck, not design, and worth a comment.

## C6 — Demo gap

🔬 `MockServiceNowGateway.getIncident` throws `IncidentNotFoundException` for any number
except `INC0010005` (FND-54). So the **abstention path — which P2 says is the common
production path — cannot be demonstrated at all** without one thin extra fixture.

Given P2, showing only the strong case would misrepresent the feature to judges and to
ourselves. One extra mock incident with no similar resolved tickets is a cheap, offline,
no-risk addition and it is the single highest-value demo change available.
