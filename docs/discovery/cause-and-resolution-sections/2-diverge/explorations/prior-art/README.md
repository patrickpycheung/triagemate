# Prior art — summary

Detail + citations: [exploration.md](exploration.md). 🔬 spiked · 📚 documented · 🔍 inferred
· 🤔 assumed · ❓ unknown.

## Top patterns

1. 📚 **One shape across the category**: hypothesis → evidence → confidence → probable cause
   → suggested next step, plus an explicit **inconclusive** state (Datadog, Rootly, Resolve,
   incident.io, Azure SRE Agent, Moogsoft). Nobody ships a bare "cause is X, do Y".
2. 📚 **Only vendors with a causal substrate assert "the root cause"** (Dynatrace Smartscape,
   Traversal causal ML). Text/precedent-based tools say *probable / likely / potential*.
   🔍 No substrate here — we are in the hedged family by construction.
3. 📚 **Rejected hypotheses are shown, not hidden.** Azure SRE Agent prints `INVALIDATED`
   first; Rootly mandates "Alternative Hypotheses Considered — ruled out because…".
4. 📚 **Abstention is concrete.** incident.io names the missing thing; a reflexive *"I don't
   have that information"* when the data exists is labelled **"Bad behaviour"**.
5. 📚 **ServiceNow itself makes no cause claim**: "Similar Resolved Incidents" (ranked, **no
   percentage**) and "Resolution Notes Generation" (a **draft** the agent edits before
   saving). ❓ No published ServiceNow AI work-note disclaimer — our
   `[AI Triage · … advisory only]` prefix breaks no convention.

## Avoid

- 📚 **Percentages.** HAX **G2-B**: numeric precision must match *measured* performance; we
  measured none, and ServiceNow's similarity UI shows none. 🔍 Our `%.0f%%` on
  `candidateSystems` already overstates — don't extend it to cause/fix.
- 📚 **"HIGH confidence" is the dangerous label** — high displayed confidence produced a
  small but *significant decrease* in human performance.
- 📚 **Explanations alone don't protect** (Bansal, CHI '21 — they raise acceptance of wrong
  answers too). Evidence must be checkable, not merely present.
- 📚 First-person voice ("I think this is…") — PAIR: inflates perceived ability. Keep the
  human as the actor; FireHydrant attributes cause to *the team*, not the AI.

## The 3 conventions to copy verbatim

1. **Datadog's three-state confidence** per claim: `validated / invalidated / inconclusive`,
   with its rule — *"marks the investigation as inconclusive when the available data is
   insufficient to support a defensible conclusion."* Wordless, cheap, works deterministically.
2. **Rootly's hypothesis block** (under its own heading, "Transparency Required"):
   `Root Cause Hypothesis: … / Confidence: [HIGH|MEDIUM|LOW] / Evidence: - [point with
   source] / Alternative Hypotheses Considered: - [X] - Ruled out because [reason]`, plus
   *"Never present 'black-box' recommendations."*
3. **incident.io's abstention phrasing** — name the specific missing thing: *"I cannot find
   recent changes to service-payment in the last hour."* Positive form equally specific:
   *"I searched the last 50 deploys… and found PR #8934 deployed at 14:23, 4 minutes before
   the first alert."*

Structurally, steal the **Azure SRE Agent ledger** (slot-filling, so the deterministic
engine can emit it) and ServiceNow's ordering: ticket text primary, precedent tickets a
documented *fallback*.

## Accuracy reality-check

📚 Ahmed et al., **ICSE 2023**, Microsoft, **44,340 incidents**, rated 1–5 by the 25 incident
owners who actually fixed them: root-cause **correctness 2.40–2.88 / 5** (3.52 best-of-5);
mitigation 2.28–3.16. **Readability 3.5–4.6** — it reads far better than it is right. 📚 The
famous **0.766** (RCACopilot, EuroSys 2024) is root-cause **category classification**, not
free text. 📚 Roy et al. (FSE 2024): best precision came from a ReAct agent whose wrong
answers were **66% "Insufficient Information"** — it won by abstaining; hallucination **6%
vs 18%** CoT. 📚 incident.io's bar: *"At 50% precision, you're essentially flipping coins."*

🔍 With full telemetry, LLM free-text root cause is right about half the time and sounds
right nearly always. We have strictly less signal. Word cause as a **hypothesis to
disprove**, make "insufficient evidence" the cheap default path, never let readability
outrun correctness.
