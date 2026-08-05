# Spike — is "similar past incident" a real signal in production?

**Trigger**: the first-principles exploration proposed sourcing cause/resolution from
`ResolvedIncident.resolutionNotes` (a human's closed causal verdict, transferred by
analogy). That design is only as good as the *analogy* — i.e. as good as
`findSimilarIncidents`. The exploration flagged 🤔 that the real gateway may hardcode
similarity. Executed immediately per the DDS auto-spike rule.

**Result: 🔬 NEGATIVE — the analogy is not supported by real data today.**

## What was verified

`RealServiceNowGateway.findSimilarIncidents` (`:129-142`), verbatim:

```java
// Naive keyword match on short_description of resolved/closed incidents.
String kw = firstKeyword(incident.shortDescription());
JsonNode body = rows("/api/now/table/incident",
        "stateIN6,7^short_descriptionLIKE" + kw,
        "number,short_description,assignment_group,close_code,close_notes");
List<ResolvedIncident> out = new ArrayList<>();
if (body != null) body.forEach(r -> out.add(new ResolvedIncident(
        text(r, "number"), text(r, "short_description"),
        text(r, "assignment_group"), text(r, "close_code"),
        text(r, "close_notes"), 0.5)));
```

and (`:314-316`):

```java
private static String firstKeyword(String s) {
    return s == null || s.isBlank() ? "error" : s.split("\\s+")[0];
}
```

### Three findings, all 🔬 Spiked

1. **Similarity is a constant.** Every `ResolvedIncident` from the real gateway carries
   `similarity = 0.5`, hardcoded at `:140`. There is no similarity computation in
   production at all. Any ranking, any "most similar", any "N% match" rendered from
   live data is displaying a literal constant.

2. **The match is one word — the *first* word.** `firstKeyword` returns
   `s.split("\\s+")[0]`. For the project's own canonical example, *"User receives HTTP
   403 when submitting an order"*, the production query is
   `short_descriptionLIKE User`. Incident short descriptions overwhelmingly begin with
   "User", "Unable", "Cannot", "Error", "Users" — so the filter is close to a no-op on a
   real queue.

3. **Mock data hides all of it.** `MockServiceNowGateway` supplies genuine-looking
   values (`0.91`, `:101`) and hand-tuned descriptions that match well. So the
   deterministic demo will look excellent and the live path will cite arbitrary tickets.
   The gap is invisible from the demo — which is exactly the condition under which a
   feature ships broken.

## Why this is load-bearing

The most attractive design — *cheapest, most grounded, needs no LLM, needs no new
connector* — is "cite how similar past incidents were actually resolved". This spike
shows that in production **"similar" currently means "shares its first word"**.

The failure mode is not a missing feature. It is worse: the section renders with full
confidence and correct-looking attribution (*"INC0011455 was resolved by: …"*) while
citing a ticket that has nothing to do with the incident. Attribution, which
[claude-orchestrator](../explorations/claude-orchestrator/exploration.md) argued is the
main safety mechanism, **converts into a liability** when the cited thing is irrelevant
— it lends borrowed credibility to noise.

## Consequences for the design

This does not kill the feature. It adds a hard prerequisite and changes the wording.

1. **`findSimilarIncidents` must improve before the resolution section can cite it.**
   At minimum: multi-token matching with stopword removal, and a real similarity score.
   This is now in scope for the concept, not an unrelated pre-existing wart.
2. **Until it does, the section must not claim similarity it cannot compute.** Honest
   wording for today's implementation is *"a past incident that also mentions
   '<keyword>'"* — not *"the most similar past incident"*, and never a percentage.
3. **Never render `similarity` from the real gateway.** Displaying `0.5` as "50%
   similar" is a fabricated statistic. If the UI shows a match strength, it must be
   suppressed when the value is the sentinel, or the sentinel must be replaced by a
   real computation.
4. **The mock/real divergence is itself a defect** worth logging (it is the J23
   "live-UI-honesty" concern applied to data quality): the demo path and the live path
   disagree about how good the evidence is, and only the flattering one is ever seen.

## Trust ledger

| Claim | Trust |
|---|---|
| Real gateway hardcodes `similarity = 0.5` | 🔬 Spiked (`:140`) |
| Match is the first whitespace token only | 🔬 Spiked (`:314-316`) |
| Mock supplies realistic similarity, masking this | 🔬 Spiked (`:101`) |
| Real incident descriptions commonly start with generic words | 🔍 Inferred (strong; the codebase's own example does) |
| `close_notes` is usually populated on real resolved tickets | ❓ Unknown — needs a live probe against the dev instance |

The last row remains open and requires the corporate-network laptop; it is recorded in
[open-questions.md](open-questions.md) rather than guessed.
