# Patterns — where the independent reads converge

Two independent perspectives — **ChatGPT analysis PDF** and **Claude + Context7/spike**
— plus the operator's inputs. High-confidence patterns = agreement across both.

## P1 — One bounded investigator, not a swarm (HIGH)
Both reads: build **one** orchestrator holding several tools, not four autonomous
agents. The app owns tool allowlist, order, limits, timeouts, max-calls; the model
owns search-term choice + interpretation. ADK maps this exactly (`SequentialAgent`
macro-flow + `LlmAgent` per step + `beforeToolCallback` guardrails). → J1, J2, J8.

## P2 — ServiceNow is the primary evidence source, not just the trigger (HIGH)
Both reads: historical incidents + CMDB ownership are often the strongest routing
signal — frequently more useful than Confluence or GitLab. → J5 gets read depth
(getIncident + findSimilarIncidents + findOwnership), not just a fetch.

## P3 — Evidence ladder: cheap→targeted→last (HIGH)
Confluence (broad) → Sumo (bounded, only after app+time+id known) → GitLab (targeted,
last, only with a concrete term). Never "search everything." → J6 ordering + bounds.

## P4 — Contract before agent (HIGH)
A strict JSON diagnosis contract (confidence + sources + contradicting evidence +
missing info + next action) is the spine; work note and UI are renderings of it.
→ J4 built first.

## P5 — Advisory only + untrusted input (HIGH)
No auto-reassign/close/priority/remediation; treat all fetched content as untrusted
(prompt-injection defense); least-privilege allowlists. → J8 cross-cutting.

## P6 — Mock ⇄ real behind one interface (HIGH)
Every connector mockable so the demo runs offline and dev proceeds before API
approvals. → J3.

## P7 — Engine choice is now low-risk and reversible (NEW, from spike)
ADK-Java is GA 1.x with **two** model-backend routes to an OpenAI-compatible endpoint
(`google-adk-langchain4j` and `google-adk-spring-ai`). The Spring-AI "fallback" is a
backend-module swap, not a rewrite — orchestrator/tools/contract are engine-agnostic.
→ D1 decided with a cheap escape hatch.

## Divergence (the interesting bit)
The PDF's one substantive disagreement was **engine**: it leaned Spring AI (it wasn't
told ADK was mandated). The spike **dissolves** the disagreement: `google-adk-spring-ai`
means choosing ADK does not forfeit Spring AI — you can have both. No live tension left.
