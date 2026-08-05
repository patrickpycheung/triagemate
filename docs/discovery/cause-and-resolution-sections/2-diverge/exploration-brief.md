# Exploration Brief — Phase 2 DIVERGE

**Question put to every agent**: how should TriageMate express a *likely cause* and a
*likely resolution* honestly enough to post onto an incident?

Six agents, independent, no communication. Biases chosen to be non-overlapping — each
owns a question the others cannot answer.

| Exploration | Bias | The question it owns |
|---|---|---|
| `first-principles/` | 🧠 First Principles | What *is* a cause claim? When is the correlation→causation leap licensed? Is mitigation the same thing as a fix? |
| `prior-art/` | 📚 Prior Art | How do PagerDuty/Datadog/Dynatrace/ServiceNow word AI-suggested cause+fix? How accurate is LLM root-cause analysis really? |
| `risk-averse/` | 🛡️ Risk-Averse | How does this cause harm? Is there a class of fix we must never suggest? Does the new field widen the prompt-injection and self-poisoning surface? |
| `minimum-viable/` | ⚡ Minimum Viable | What is the cheapest honest version? How far does citing `resolutionNotes` from similar past incidents get us? |
| `technical-depth/` | 🏗️ Technical Depth | Exact record shape, validator rules, abstention legality, both-engine production, backward compatibility. |
| `user-centric/` | 👤 User + Demo | The actual strings. What wording makes a tired engineer appropriately skeptical without making the feature useless? |

## Deliberate tensions

The biases are set to **collide**, because the collisions are the findings:

- **minimum-viable vs risk-averse** — "just print the past resolution notes" is cheapest
  and most grounded, but past close notes are unvetted free text written by humans in a
  hurry, and now flow into an engineer's advice. Who wins?
- **first-principles vs user-centric** — the epistemically honest rendering (ranked
  hypotheses with abstention) may be exactly the rendering nobody reads at 2am.
- **technical-depth vs minimum-viable** — one new field or four? Abstention as a first-
  class state costs schema complexity that RAPID rigor resists.

## Shared inputs

All agents were given `1-elicit/problem-statement.md`, the J4 contract, the validator,
both engines, and the 🔬 Spiked finding that `resolutionCode`/`resolutionNotes` are
already fetched and discarded.

## Trust discipline

Every claim carries 🔬 Spiked / 📚 Documented / 🔍 Inferred / 🤔 Assumed / ❓ Unknown.
Agents with the code in hand were told most of their claims should be 🔬.
