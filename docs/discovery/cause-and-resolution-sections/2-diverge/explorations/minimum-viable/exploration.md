# Minimum Viable / 80-20 — Cause & Resolution sections

**Trust legend**: 🔬 spiked (read in code, line-verified) · 📚 documented ·
🔍 inferred from adjacent code · 🤔 judgement · ❓ unknown

> Method note 🔬: `DeterministicDiagnosisEngine.java` contains a non-UTF-8 byte,
> so `file` reports it as `data` and plain `grep` **silently skips it**. Every
> claim below about that file was re-verified with `grep -a`. An earlier pass of
> this exploration briefly concluded the engine never calls
> `findSimilarIncidents` — that conclusion was an artifact of the skipped file
> and is wrong. Anyone auditing this repo should use `grep -a`.

## 1. The lead, verified

| Claim | Status | Evidence |
|---|---|---|
| `ResolvedIncident` carries `resolutionCode` + `resolutionNotes` | 🔬 | `model/ResolvedIncident.java:11-12` |
| Mock gateway populates both with real cause+fix prose | 🔬 | `MockServiceNowGateway.java:95-108` |
| Real gateway fetches `close_code`/`close_notes` into them | 🔬 | `RealServiceNowGateway.java:130-142` |
| Deterministic engine **already calls** `findSimilarIncidents` | 🔬 | `DeterministicDiagnosisEngine.java:156` |
| …and already builds `e-sim-<number>` Evidence rows | 🔬 | same file `:157-162` |
| …whose summary includes `resolutionCode` but **not** `resolutionNotes` | 🔬 | same file `:158-161` |
| `similar` is still in lexical scope at report assembly | 🔬 | used at `:456`, report built at `:540` |
| `resolutionNotes` is read **nowhere** in main source | 🔬 | repo-wide `grep -ra` |
| ADK tool returns the whole record, so notes already reach the LLM | 🔬 | `TriageMateTools.java:83-85` (contrast `findOwnership:89-95`, which projects to a `Map` and drops fields) |

So the true gap is one unread string per prior ticket. The mock literally
contains a cause — *"Discount was applied after tax in the gateway; reconcile
check failed"* — and a fix — *"Fixed order of operations in payment_service"* /
*"Workaround then code fix"*. On the demo incident, the answer to "why + how" is
already sitting in memory, fetched, and thrown away.

## 2. Why citation beats inference (the honesty argument)

The problem statement's hard part is that a **cause** is an inference beyond the
evidence and a **resolution** is an instruction to act. Both are a different
epistemic class from every existing J4 field.

The 80-20 move is to refuse the class change. Don't assert *"this happened
because X"*. Assert *"two resolved incidents matching this one were closed as
`Resolved - Code Fix`, with these notes"*. That is:

- **Grounded** — it *is* evidence, already in `evidence[]` as `e-sim-*`.
- **Honestly hedged** — the hedge is structural, not stylistic. The sentence's
  subject is a past ticket, not this one.
- **Advisory-safe** — "here is what someone did last time" cannot be mistaken
  for an authorised instruction. If the fix is wrong, the failure mode is "the
  prior ticket wasn't actually similar", which the reader can check in one click.
- **Deterministic-engine-compatible** — 🤔 this is the binding constraint, and
  citation is the *only* approach that clears it cleanly. That engine cannot
  invent prose; it can select and template. Quoting a human's `close_notes` is
  pure selection.

**Where it falls down** 🤔: a novel incident with no similar ticket gets nothing.
That is the correct outcome (degrade, don't invent) but it means the feature is
*data-dependent* — it shines on the demo incident and may be blank on a random
real one. Second weakness 🔬: `RealServiceNowGateway` hardcodes `similarity = 0.5`
and matches on `firstKeyword(shortDescription)` with a `LIKE`, so "most similar"
is a weak claim against the real instance. Mitigation is wording, not code: say
*"resolved incidents matching this one's keywords"*, never *"the most similar
incident"*. Do not fix the matcher — out of MVP scope.

## 3. Contract delta — arguing for the smallest change

The brief asked whether one field can replace two. Four candidates:

**(a) Reuse `recommendedNextAction`.** Rejected. It works today, it is rendered
in three places (🔬 note `DiagnosisReport.java:96-98`, UI `index.html:1237,1294`),
and it answers a genuinely different question ("what to check next"). Overloading
it makes both claims less trustworthy and gains nothing — the UI would still need
a second section to be demo-legible.

**(b) One nested record**, e.g. `CausalHypothesis(cause, resolution, refs,
confidence)`. Superficially "one field", actually the most expensive option: a new
model file, a null-check on the wrapper *and* on each component in the UI and the
note, and — decisively 🔬 — the ADK prompt already carries a warning block at
`AdkDiagnosisEngine.java:170-175` about the model confusing two nested
`confidence` shapes. Nesting is this project's demonstrated LLM failure surface.
Don't add another nest to save a comma.

**(c) Two flat `String` fields, no refs.** Cheapest, but drops the J4 spine rule
(every conclusion ties to `evidenceRefs`) for about 15 minutes of saved work.

**(d) Recommended — three flat components:**

```java
String likelyCause,
String likelyResolution,
List<String> causeEvidenceRefs
```

*One shared* refs list, not one per claim, and that is not a compromise: in the
citation approach both sentences derive from the same prior-incident rows, so a
single list is the honest shape. It plugs straight into the existing validator
helper (🔬 `DiagnosisReportValidator.java:116-128` already has `refEntry` +
`danglingRefs`), so criterion 1 stays *mechanically* enforced for ~3 lines.

Both prose fields are nullable. Null is the "insufficient evidence" state; the
renderers simply omit the card.

## 4. Deterministic engine sketch 🔬

`similar` is in scope; `assignmentRefs` at `:454-456` already computes the exact
ref list. Insert before the `new DiagnosisReport(...)` at `:540`:

```java
// Cause & fix are CITED, not inferred: the app never asserts "because", it
// reports how tickets matching this one were actually closed. Notes are a
// human's words from close_notes — this engine selects, it does not narrate
// (the FND-63 / FND-8 rule).
String likelyCause = null, likelyResolution = null;
List<String> causeRefs = List.of();
if (!similar.isEmpty()) {
    ResolvedIncident top = similar.stream()
            .max(Comparator.comparingDouble(ResolvedIncident::similarity)).orElseThrow();
    causeRefs = refsThatExist(gathered,
            similar.stream().map(r -> "e-sim-" + r.number()).toList());
    likelyCause = ("%d resolved incident(s) matching this one's keywords were closed as '%s'. "
            + "%s wrote on %s: %s")
            .formatted(similar.size(), top.resolutionCode(),
                       top.resolutionGroup(), top.number(), text(top.resolutionNotes()));
    likelyResolution = ("What resolved %s: %s — confirm it applies here before acting.")
            .formatted(top.number(), text(top.resolutionNotes()));
} else {
    missing.add("No previously-resolved incident matched this one's keywords — "
            + "no cause or fix can be cited from history");
}
```

Notes on the sketch 🤔:

- `refsThatExist` (🔬 `:719-720`) is reused, so a dangling ref is impossible by
  construction — the validator check is belt-and-braces.
- Cause and resolution both quote `resolutionNotes` because in practice
  `close_notes` contains both halves in one paragraph (🔬 see the mock's
  `"Discount was applied after tax… Fixed order of operations in
  payment_service."`). Splitting them mechanically would be guessing. Accepting
  a little repetition is the 80-20 call; the two sections differ in **framing**
  ("this is what was wrong" vs "this is what fixed it, verify first"), which is
  what makes the demo legible.
- The `else` branch reuses the existing `missing.add(...)` idiom (🔬 `:487-522`).
- No new gateway call, no new evidence row, no new trace step. ~45 min including
  the empty-`similar` test.

**Note + UI**: two lines in `toDiagnosisNote()` (guarded by `!= null`, matching
the existing `recommendedNextAction` guard at `:96`) and one card in
`index.html` next to the existing "Recommended next action" card at `:1294`.

## 5. ADK path sketch 🔬

Cost is almost entirely prompt, because the data already flows:

1. `TriageMateTools.java:80-82` — extend the `@Schema` description from
   *"and their resolution groups"* to *"…their resolution groups, close codes and
   resolution notes"*. One clause; makes the model aware the notes exist.
2. `AdkDiagnosisEngine.java` schema block (🔬 `:159-168`) — add three keys:
   `"likelyCause","likelyResolution","causeEvidenceRefs":[]`.
3. One instruction paragraph, in the imperative voice the rest of the prompt uses:

   > `likelyCause` and `likelyResolution` must CITE, never speculate. Base them
   > on the resolution notes of incidents returned by `find_similar_incidents`,
   > and name the incident number in the text. If no similar incident was found,
   > set both to `null` and record the gap in `missingInformation`. Never write
   > a cause you cannot tie to an evidence id in `causeEvidenceRefs`.

4. `stampGeneratedAt` (🔬 `:690-696`) — three more constructor args.

Jackson is already lenient (🔬 `FAIL_ON_UNKNOWN_PROPERTIES=false`,
`AdkDiagnosisEngine.java:72-74`), and a record component the model omits
deserialises to `null` — which is exactly the intended "insufficient evidence"
state. **No new failure mode, no new repair-retry risk.** ~30 min.

## 6. Cost / value ranking

| # | Option | Build | Demo value | Honesty | V/C | Verdict |
|---|---|---|---|---|---|---|
| 1 | **Cited prior resolution, 3 flat components** | **~3 h** | High — two new cards saying "why + how" in the ticket's own language | High — quotes a human, degrades to null | **Best** | **Recommend** |
| 2 | UI-only card derived client-side from `e-sim-*` evidence | ~30 m | Medium — on-screen only | Low — never reaches the ServiceNow note (the real product surface); 🔬 breaks on the ADK path, where the model authors its own evidence `id`s | High but capped | Emergency fallback if <1 h remains |
| 3 | Two flat Strings, no `causeEvidenceRefs` | ~2.5 h | Same as 1 | Drops the J4 grounding rule | Slightly worse than 1 | Only if the 3-line validator edit somehow fights back |
| 4 | Nested `CausalHypothesis` record | ~4 h | Same as 1 | Same as 1 | Worse | Reject — new file, null-nesting, known LLM nesting hazard |
| 5 | LLM-authored causal inference (ADK-only, free prose) | ~2 h | Highest on stage | Lowest — pure FND-8 fabrication class, and 🔬 the deterministic engine (the guaranteed demo path, and the FND-7 fallback) cannot produce it, so the sections vanish exactly when the agent fails | Poor | Reject |
| 6 | Reuse `recommendedNextAction` | ~1 h | Low — one blurred paragraph | Muddies two epistemic classes | Poor | Reject |

Total for option 1, itemised: record +3 components / +2 note lines 15 m ·
validator 10 m · deterministic engine 45 m · ADK prompt + `stampGeneratedAt`
30 m · UI card 20 m · 11 `new DiagnosisReport(` call sites (🔬 2 main, 9 test)
20 m · strict-JSON regression test (🔬
`DiagnosisControllerPostResponseShapeRegressionTest.java:122` uses
`content().json(EXPECTED_JSON, true)` — strict, *will* fail) 10 m · two new
engine tests 30 m. **≈3 h.**

Optional 10-minute saving 🤔: give `DiagnosisReport` a 15-arg secondary
constructor delegating with `null, null, List.of()`, and the 9 test call sites
need no edit at all. Worth it only if the clock is genuinely tight — it is a
back-compat shim in a codebase with no back-compat obligation.

## 7. The cheapest thing that visibly answers "why + how" on stage

If the answer must fit in one sitting: build option 1 but ship the **deterministic
path first** and the ADK prompt delta second. The deterministic engine is the
guaranteed demo path and the FND-7 fallback (🔬 `DiagnosisOrchestrator` degrades
to it), so a feature that exists only there still shows on stage in every
scenario, including a failed agent run. A feature that exists only on the ADK
path shows in none of the failure scenarios. Roughly 1.5 h of the 3 h buys the
whole visible outcome; the remaining 1.5 h buys parity.

## 8. Open questions ❓

- ❓ How often does a real ServiceNow instance return a similar incident at all?
  With `firstKeyword` + `LIKE` (🔬 `RealServiceNowGateway:132-135`) the hit rate
  is unmeasured. On mock it is always 2.
- ❓ `close_notes` on real tickets is frequently one word ("fixed", "user error").
  A minimum-length guard before quoting is ~3 lines — 🤔 worth adding if a live
  run shows it, not before.
- ❓ Should `confidenceOverall` be capped at LOW when `likelyCause` is null?
  Arguably yes; deliberately out of MVP scope.
