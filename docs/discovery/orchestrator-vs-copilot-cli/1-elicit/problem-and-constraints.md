# Problem & constraints (Phase 1)

## Problem
Compare **our custom LangChain/Google-ADK orchestration** (the `-Padk` `LlmAgent` loop we
already built) against **Copilot CLI's automatic autonomous agent with a high-tier model**
(Claude Opus 4.6 / GPT-5.3-Codex). Operator's instinct: *we can build orchestration in
LangChain, but it won't be as good as Copilot CLI's.* Question: is that true, and **which
is more suitable for the hackathon demo?**

## Two different things are being compared
1. **Raw autonomous-reasoning quality** — how well each plans + tool-calls open-endedly.
2. **Suitability for a live demo** — reliability, repeatability, control, wow-factor,
   failure cost. These are NOT the same axis (a "better" agent can be a *worse* demo).

## Constraints & evidence
| # | Fact | Trust | Source |
|---|------|-------|--------|
| M1 | Copilot CLI is a mature autonomous terminal agent (GA Feb 2026): autopilot (`--max-autopilot-continues`), `/fleet` subagents, MCP tools | 📚 | [Copilot CLI GA](https://awesomeagents.ai/news/github-copilot-cli-generally-available/) |
| M2 | Copilot CLI can run **high models**: Claude Opus 4.6, GPT-5.3-Codex, Gemini 3 Pro; GPT-5 series highlighted for **tool-heavy** work; `/model` or `--model=` | 📚 | [Copilot CLI guide](https://www.devleader.ca/2026/07/09/github-copilot-cli-the-complete-guide-to-the-agentic-terminal-agent) |
| M3 | **Our `-Padk` path can use the SAME high models** via the E2 Copilot proxy (`LLM_BASE_URL`) | 🔍 | [[triagemate-copilot-backend-decision]] + L7 |
| M4 | **Model quality dominates orchestration cleverness**: "one unnecessary reasoning turn costs more than any orchestration micro-optimization"; frameworks add replay/audit/governance, not raw reasoning | 📚 | [SitePoint orchestration wars](https://www.sitepoint.com/agent-orchestration-framework-comparison-2026/) |
| M5 | **Compounding error**: 95%/step reliability collapses over many chained autonomous steps; "match autonomy to failure cost, not to what the demo can get away with"; prefer **governed autonomy** (bounded, auditable) | 📚 | [Foundra: demos lie](https://www.foundra.ai/key-reads/ai-agent-production-reliability-testing-2026), [Tandem: workflow-first](https://tandem.ac/blog/workflow-first-autonomy-reliability-beats-demo-only-agents) |
| M6 | Copilot CLI headless+MCP is currently fragile (≥7-tool empty-message bug); autonomous runs are non-deterministic + network/ToS-dependent | 📚 | [[triagemate-copilot-backend-decision]] (L5/E3) |
| M7 | We already have a bounded, advisory, **tested** loop + an **offline deterministic** engine (bulletproof demo fallback) | 🔬 | this repo |

## Success criteria
An honest, evidence-based verdict on (a) the quality gap and (b) **which to put in front of
judges**, given a demo that posts advisory comments to real tickets and must not fail on stage.
