# Prior art: how the industry words "probable cause" + "suggested fix"

**Bias**: PRIOR ART. What already exists, how it is worded, what is measured.
**Question**: what makes a machine-generated cause/resolution claim honest enough to post
into a ServiceNow work note?

**Trust legend**: 🔬 Spiked (I ran it) · 📚 Documented (URL cited) · 🔍 Inferred (reasoned
from documented facts) · 🤔 Assumed · ❓ Unknown.

---

## 1. The category has already converged on one shape

📚 Across ~14 products the vocabulary is near-identical: **hypothesis → evidence →
confidence → probable cause → suggested next step**, with an explicit *inconclusive* state.
Nobody in the AIOps/AI-SRE category ships a bare "the cause is X, do Y". Two families:

- **Calibrated-probability family** (Rootly, Resolve AI, Moogsoft, BigPanda, New Relic,
  PagerDuty, Datadog): hedge lexically ("probable", "likely", "potential"), expose some
  confidence artefact, keep the human as the actor.
- **Causal-certainty family** (Dynatrace Davis, Traversal): claim determinism/"true root
  cause" — but both buy that claim with a *proprietary causal substrate* (Smartscape
  topology graph from OneAgent; causal ML over deploy history), not with an LLM.
  📚 Dynatrace: "only causal AI can deterministically know the root cause of an issue"
  ([dynatrace.com/news/blog/what-is-causal-ai-deterministic-ai](https://www.dynatrace.com/news/blog/what-is-causal-ai-deterministic-ai/)).

🔍 **Implication for TriageMate**: we have no causal substrate. We have keyword overlap,
similar closed incidents, and Confluence text. We are structurally in the
calibrated-probability family, and any wording borrowed from Dynatrace/Traversal would be
a claim we cannot back.

---

## 2. Comparison table

| Tool | Cause wording | Resolution wording | Confidence shown as | When unsure | Source |
|---|---|---|---|---|---|
| **Dynatrace Davis** | "root cause" (asserted); fault-tree analysis | "recommend remediation actions" | ranked *root cause contributors*; no % | merges/withholds during `Processing` state until one cause identified | 📚 [docs.dynatrace.com/…/root-cause-analysis](https://docs.dynatrace.com/docs/dynatrace-intelligence/root-cause-analysis) |
| **Datadog Bits Investigation** | "**likely** root cause", "evidence-backed conclusion" | "next steps" | **validated / invalidated / inconclusive** per hypothesis — no numbers | "marks the investigation as inconclusive when the available data is insufficient to support a defensible conclusion" | 📚 [docs.datadoghq.com/bits_ai/bits_ai_sre/investigate_issues](https://docs.datadoghq.com/bits_ai/bits_ai_sre/investigate_issues/) |
| **PagerDuty AIOps** | "**Probable Origin**s" (card title, plural) | links to past incident's remediation metadata | **% likelihood**, top 3, ranked | card simply does not render; no empty-state copy | 📚 [support.pagerduty.com/main/docs/probable-origin](https://support.pagerduty.com/main/docs/probable-origin) |
| **Moogsoft APEX** | "**Probable Root Cause**" (named feature) | recommendations list | **High / Medium / Low** bands (cloud); % estimate (Enterprise 8.0) | Recommendations section **is not displayed at all** without applicable feedback | 📚 [docs.moogsoft.com/…/probable-root-cause-overview](https://docs.moogsoft.com/moogsoft-cloud/en/probable-root-cause-overview.html) |
| **BigPanda** | "**probable** root cause", "**suspected** changes" | "context" on the change | "high-confidence causality **ranking**", 29 vector dimensions | ❓ not documented | 📚 [bigpanda.io/our-product/root-cause-analysis](https://www.bigpanda.io/our-product/root-cause-analysis/) |
| **New Relic** | "**probable** root cause"; "**Potential causes**" tab | "next best action" | no user-facing score; 👍/👎 feedback loop | ❓ not documented | 📚 [docs.newrelic.com/…/response-intelligence-ai](https://docs.newrelic.com/docs/alerts/incident-management/response-intelligence-ai/) |
| **incident.io Investigations** | "posts a root cause, the evidence behind it" | "clear next steps" | **none numeric** — evidence + audit trail instead | must say it cannot find the thing; explicit anti-confabulation rule | 📚 [docs.incident.io/investigations/overview](https://docs.incident.io/investigations/overview) |
| **Rootly** | "**probable** root causes with confidence scores" | "suggested fixes and next steps" | **numeric score + HIGH/MED/LOW** + reasoning chain | <0.3 score → say "AI suggestions have low confidence", fall back to manual | 📚 [rootly.com/ai-sre](https://rootly.com/ai-sre) + [Rootly MCP skill](https://github.com/Rootly-AI-Labs/Rootly-MCP-server/blob/main/examples/skills/rootly-incident-responder.md) |
| **FireHydrant** | avoids the claim: "**potential** cause", attributed to *the team* not the AI | "Suggested Incidents", "AI-drafted" | none | stays in "summary"/"suggestion" framing; never asserts cause | 📚 [docs.firehydrant.com/docs/ai-powered-incident-management](https://docs.firehydrant.com/docs/ai-powered-incident-management) |
| **Resolve AI** | "**probable** root cause" + "shows its work" | "recommends concrete fixes"; generates PRs | confidence score + evidence chain + dependency chain | eliminates hypotheses explicitly, states which | 📚 [resolve.ai/glossary/what-is-root-cause-analysis](https://resolve.ai/glossary/what-is-root-cause-analysis) |
| **Traversal** | "**true** root cause" (strongest claim) | isolate the breaking change | headline accuracy % (>90% claimed; 82% at Amex) | ❓ | 📚 [traversal.com/blog/introducing-causal-search-engine](https://www.traversal.com/blog/introducing-causal-search-engine-from-correlated-alerts-to-causally-consistent-diagnoses) |
| **Azure SRE Agent** | explicit hypothesis ledger → `ROOT CAUSE:` | `RECOMMENDED ACTION:` + prior incident ref | `VALIDATED` / `INVALIDATED` per hypothesis | 🔍 ledger with no VALIDATED line is the abstention | 📚 [learn.microsoft.com/azure/sre-agent/root-cause-analysis](https://learn.microsoft.com/en-us/azure/sre-agent/root-cause-analysis) |
| **ServiceNow** | "Similar Resolved Incidents" (ranked, no cause claim) | "Resolution Notes Generation" — a *draft* | **ranked matches, no percentage** | 🔍 skill unavailable outside resolved/closed state | 📚 see §4 |

---

## 3. Verbatim strings worth stealing

📚 **Azure SRE Agent** — the single best structural model found. Published verbatim as a
code block in Microsoft Learn
([source](https://learn.microsoft.com/en-us/azure/sre-agent/root-cause-analysis)):

```text
HYPOTHESIS 1: Recent deployment broke something
├─ Checked: Last deployment was 3 days ago
├─ Evidence: Error rate stable until 30 minutes ago
└─ Result: INVALIDATED

HYPOTHESIS 2: Database overloaded
├─ Checked: Azure SQL metrics (CPU, DTU, connections)
├─ Evidence: DTU at 98%, query duration 4x normal
├─ Traced: SELECT * FROM orders WHERE... taking 8.2s
└─ Result: VALIDATED

ROOT CAUSE: Orders table missing index on customer_id column.
Query plan shows full table scan on 2.1M rows.

RECOMMENDED ACTION: Add index on orders.customer_id
Similar fix applied in INC-2341 (3 weeks ago)
```

Note three things: the *rejected* hypothesis is shown first; every hypothesis carries its
`Checked:` and `Evidence:` lines inline; and the recommended action cites a precedent
ticket rather than asserting authority. The page's own description: "reasons like an
expert SRE by forming hypotheses, testing them with evidence, and explaining its
conclusions."

📚 **Rootly's open-source responder skill** — the most explicit published *wording contract*
from any vendor
([source](https://github.com/Rootly-AI-Labs/Rootly-MCP-server/blob/main/examples/skills/rootly-incident-responder.md)):

```text
Root Cause Hypothesis: [Your hypothesis]
Confidence: [HIGH/MEDIUM/LOW]
Evidence:
- [Evidence point 1 with source]
Alternative Hypotheses Considered:
- [Alternative 1] - Ruled out because [reason]
```

with the rule, under a heading literally titled **Transparency Required**: *"Cite your
sources… Never present 'black-box' recommendations."* And an explicit low-confidence path:
below a 0.3 similarity score the agent must say *"AI suggestions have low confidence"* and
switch to manual investigation.

📚 **Datadog** — the three-state confidence vocabulary: each hypothesis is *"classified as
validated, invalidated, or inconclusive, enabling teams to quickly see what's confirmed,
what's ruled out, and where further investigation is needed."*

📚 **Moogsoft** — the strongest epistemic disclaimer found anywhere in the category. Its own
docs say Probable Root Cause *"does not use 'Root Cause Analysis' techniques"*
([source](https://docs.moogsoft.com/Enterprise.8.0.0/en/probable-root-cause.html)). A vendor
actively narrowing its own claim.

📚 **incident.io** — abstention phrased as concrete, checkable negatives rather than a vague
hedge ([source](https://incident.io/blog/ai-root-cause-analysis-accuracy-testing-guide)):
*"I cannot find a service named service-xyz-9999 in the catalog"*; *"I cannot find recent
changes to service-payment in the last hour."* Its positive-evidence exemplar is equally
specific: *"I searched the last 50 deploys to payment-service and found PR #8934 deployed
at 14:23, 4 minutes before the first alert."* The article also flags the opposite failure:
saying *"I don't have that information"* when the data *is* reachable is labelled **"Bad
behaviour"** — abstention must be earned, not reflexive.

📚 **Microsoft Business Chat** (cited by the HAX Toolkit as a G2 exemplar): *"As your
Copilot, I'm here to assist you but I do make mistakes, so sources are provided for your
review when possible."*

📚 **Dynatrace refusal string** (the one verbatim UI string Dynatrace publishes): *"I'm
sorry, but I can't respond to this request. Please try rephrasing it or adding additional
context."*

---

## 4. ServiceNow specifically — the target platform

This matters most and is the thinnest evidence base.

- 📚 The similarity capability is **"Similar Resolved Incidents"**, an out-of-box Predictive
  Intelligence *solution definition* surfaced through **Agent Assist**. The agent picks it
  from a dropdown, reviews predicted resolved incidents, and *"if one has a relevant
  solution, can copy that resolution to the open incident."* The AI-agent-side tool is named
  **Get Similar Incidents**.
- 📚 **Resolution Notes Generation** (Now Assist skill) drafts resolution notes from case
  fields + work notes; *similar resolved cases and KB articles are a documented **fallback**
  source*, used only when journal activity is absent. Ordering matters: real ticket text
  first, precedent second.
- 📚 The human-in-the-loop control is **draft-then-review**, expressed as product behaviour,
  not banner text: *"Agents can review and edit the generated content before saving it."*
- ❓ **No ServiceNow-published verbatim work-note disclaimer string exists.** I searched
  ServiceNow docs, community and KB and found none. The exact banner is
  implementation-defined. 🔍 So TriageMate's existing `[AI Triage · … — advisory only]`
  prefix is *not* deviating from a ServiceNow convention — there isn't one to deviate from.
- 📚 ServiceNow does **not** render a similarity percentage in its recommendation wording;
  matches are *ranked*. 🔍 TriageMate's `"%.0f%%"` on `candidateSystems` therefore implies a
  calibration ServiceNow itself declines to imply. That is a wording risk to weigh (see §7).
- 📚 Known data-quality trap: most real instances' resolution notes are variations of
  *"Issue resolved" / "Fixed per user request" / "Done"*. 🔍 A resolution section mined from
  precedent tickets will, on real Australia Post data, frequently have nothing substantive
  to say — the abstention path is the *common* path, not the edge case.

---

## 5. Documented wording conventions for hedged machine claims

📚 **Microsoft HAX Guideline 2 — "Make clear how well the system can do what it can do"**
([source](https://www.microsoft.com/en-us/haxtoolkit/guideline/make-clear-how-well-the-system-can-do-what-it-can-do/)).
Its two design patterns are exactly our problem:

- **G2-A**: *match the level of precision in UI communication with the system performance —
  **Language***
- **G2-B**: *…— **Numbers***

i.e. hedging words and numeric confidence must be **no more precise than measured
performance justifies**. 🔍 We have measured nothing, so G2-B says: do not print a
percentage. G2-A says: use coarse words.

📚 **Google PAIR, Explainability + Trust**
([source](https://pair.withgoogle.com/guidebook-v2/chapter/explainability-trust/)) — the
framing is *calibrate* trust, not *build* it: the user should know "when to trust the
system's predictions and when to apply their own judgement". PAIR names the tradeoff
explicitly: admitting uncertainty lowers trust in *that* prediction but raises trust in the
product over time. 📚 A systematic review of HCI guidelines found PAIR is the *only*
industry guideline that addresses trust **calibration** rather than trust-building
([arXiv:2311.06305](https://arxiv.org/pdf/2311.06305)).
📚 Related wording advice: avoid first-person ("I think this is…"), which anthropomorphises
and inflates perceived ability; prefer *"This answer is based on…"*.

---

## 6. The failure mode: automation bias

📚 Automation bias = treating automated cues as infallible, producing **commission errors**
(adopting wrong output) and **omission errors** (missing what the system missed). Evidence
of real harm from *suggested-fix* UIs:

- 📚 Erroneous AI suggestions caused pathology experts to **overturn correct diagnoses in 7%
  of cases**.
- 📚 Incorrect AI suggestions significantly degraded mammogram interpretation, **worst among
  less-experienced clinicians** — the same asymmetry we should expect between a senior
  Australia Post engineer and a L1 triage operator.
- 📚 Risk amplifiers documented: **time pressure**, **low task experience**, and
  **presenting AI output early in the reasoning path** — users stop seeking contradictory
  evidence once a superficially reasonable prediction appears.
  ([Bowtie analysis, ScienceDirect](https://www.sciencedirect.com/science/article/pii/S2666449624000410);
  [NHS England ch. 5.3](https://digital-transformation.hee.nhs.uk/building-a-digital-workforce/dart-ed/horizon-scanning/understanding-healthcare-workers-confidence-in-ai/chapter-5-clinical-use/cognitive-biases-and-appropriate-confidence-in-ai-assisted-crdm))
- 📚 **LLMs are a worse offender than classifiers**: unlike systems giving discrete
  classifications with confidence scores, LLMs generate *narrative* recommendations that
  "appear highly sophisticated yet may contain subtle but clinically significant errors."

Documented mitigations, and their limits:

- 📚 **Confidence display works asymmetrically.** Low confidence reduced agreement and
  *increased* deliberation time (good). High confidence slightly increased trust **and
  produced a small but significant decrease in performance** — overreliance
  ([arXiv:2501.16693](https://arxiv.org/pdf/2501.16693)). 🔍 So a "HIGH confidence" label is
  the *dangerous* label, not the safe one.
- 📚 **Explanations alone do not fix it** — Bansal et al., CHI '21, found explanations tend
  to raise acceptance of AI recommendations uniformly, including wrong ones
  ([arXiv:2006.14779](https://arxiv.org/abs/2006.14779)).
- 📚 **Cognitive forcing functions** (making the human commit or act before seeing the
  suggestion) reduce overreliance but are **disliked** — users rated them more mentally
  demanding and trusted them less (Buçinca et al., CSCW '21).
- 📚 **"Provide information rather than recommendations"** is listed as a design mitigator
  alongside on-screen position of advice and updated confidence levels.
- 📚 HAX's own caveat, repeated across every G2 example: *"overreliance mitigations can
  backfire, so they should be tested in context."*

🔍 Note the counterweight: **algorithm aversion** is a real cost too. The target is
*appropriate* reliance, not minimum reliance. A section that hedges so hard it says nothing
fails success criterion 5 (demo-legible) as surely as an overconfident one fails 3.

---

## 7. Accuracy reality-check on LLM root-cause claims

This is the number that should govern how strongly TriageMate words its cause section.

| Study | Setting | Real figure |
|---|---|---|
| 📚 Ahmed et al., **ICSE 2023**, Microsoft, **44,340 incidents** / 1,759 services ([arXiv:2301.03797](https://arxiv.org/abs/2301.03797)) | Fine-tuned GPT-3.x generating root cause + mitigation text; 25 incident owners interviewed, 1–5 scale | **Mean *correctness* of root cause = 2.40–2.88 / 5** per model; best-of-5-samples 3.52. Mitigation correctness 2.28–3.16, best-of 4.04. *Readability* 3.5–4.6 — i.e. **it reads far better than it is right**. "More than 70% of incident owners gave three or above." |
| 📚 same paper | automatic metrics | Root-cause **BLEU-4 3.38–4.24**, ROUGE-L ~11. Authors: metric–human correlation ranged **−0.42 to +0.62**, so metrics "may not be coherent with human perception". |
| 📚 **RCACopilot** / Chen et al., EuroSys 2024 ([arXiv:2305.15778](https://arxiv.org/abs/2305.15778)) | Predicting root-cause **category** (a classification task, not free text), a year of Microsoft incidents | **accuracy up to 0.766** |
| 📚 Zhang et al., 2024, **>100,000 incidents**, GPT-4 in-context learning ([arXiv:2401.13810](https://arxiv.org/abs/2401.13810)) | vs fine-tuned GPT-3 baseline | **+43.5% correctness, +8.7% readability** — a *relative* improvement over a baseline scoring ~2.5/5. Not an absolute correctness figure. |
| 📚 Roy et al., **FSE 2024**, out-of-distribution Microsoft incidents ([arXiv:2403.04123](https://arxiv.org/abs/2403.04123)) | ReAct agent vs CoT vs retrieval | **Hallucination 6% (ReAct), 18% (CoT)**; ReAct has highest precision *at lower overall accuracy*; **66% of ReAct's wrong predictions were "Insufficient Information"** — it abstained rather than fabricated |
| 📚 incident.io's buyer guidance ([source](https://incident.io/blog/ai-root-cause-analysis-accuracy-testing-guide)) | what to demand | *"Target greater than 80% precision"*; *"At 70% precision, three out of every ten suggestions waste investigation time"*; *"At 50% precision, you're essentially flipping coins"*. Recall target only ~60%. |

🔍 **Synthesis**: free-text LLM root cause, evaluated by the engineers who actually fixed the
incident, at Microsoft, with full telemetry, scores roughly **halfway** on a 1–5 correctness
scale — while scoring 4–4.6 on readability. The gap between *sounds right* and *is right* is
the whole risk. The strongest published result (0.766) is **classification into a known
category**, not open-ended prose. And the best-precision configuration wins by **abstaining
two-thirds of the time it would otherwise be wrong**.

🔍 TriageMate has strictly less signal than any of these systems (no metrics, no traces, no
deploy history — text and precedent tickets only). Assuming our free-text cause is right
more than ~50% of the time is unsupported. 🤔 The deterministic engine, which cannot invent
prose, is plausibly *more* honest here than the ADK engine — it can only restate patterns it
matched.

---

## 8. Open / unknown

- ❓ No vendor publishes an independently-verified accuracy number for its shipped RCA
  feature. All vendor figures (Traversal 82–90%, "high degree of accuracy") are self-reported.
- ❓ No published ServiceNow work-note template for an AI-suggested resolution.
- ❓ Whether percentage confidence *increases* over-trust vs High/Med/Low bands in an ITSM
  context specifically — the evidence is from clinical/pathology domains.
