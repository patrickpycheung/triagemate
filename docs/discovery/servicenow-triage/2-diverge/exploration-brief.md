# Phase 2: DIVERGE — Exploration Brief

## What we're exploring
The solution space for a hackathon prototype that auto-triages ServiceNow technical tickets by correlating logs↔code across GitLab/Sumo/Confluence, optimizing for demo wow + build ease, using either corporate Copilot or Atlassian Rovo.

## Explorations (parallel, independent, different biases)

### A — Platform fork: Rovo-native vs Standalone+Copilot
**Biases**: Constraint-driven ⚖️ + Operations 🔧 + Minimum-Viable ⚡
**Question**: Given the two approved AI options, which platform gets us to a working end-to-end demo fastest? Can Rovo agents reach GitLab/Sumo/ServiceNow? Can corporate Copilot be called from a backend? What's the trigger + orchestration story for each?

### B — The log→code correlation engine
**Biases**: Technical-depth 🏗️ + First-principles 🧠 + Innovation 🚀
**Question**: How do we actually match Sumo log lines to GitLab source and localize the failure? Naive string match vs log-template extraction vs LLM-assisted. What's most demo-able AND buildable?

### C — Integration & trigger plumbing
**Biases**: Prior-art 📚 + Risk-averse 🛡️
**Question**: Cleanest ServiceNow→app trigger (Business Rule outbound REST / Flow / webhook). Simplest reliable access patterns for GitLab API, Sumo Logic Search Job API, Confluence API. Where are the demo-day failure risks (auth, rate limits, network)?

### D — Demo narrative & MVP scope
**Biases**: User-centric 👤 + Minimum-Viable ⚡ + Demo-lens 🎬
**Question**: What is the smallest end-to-end slice that produces a convincing 3-minute demo? What can be faked/pre-seeded vs must be live? What's the "wow beat"?

## External research (Triple Perspective) — REQUIRED for platform feasibility
Topic: "Atlassian Rovo agent external-API/Action capabilities + programmatic access to GitHub Copilot / M365 Copilot from a backend service." Run /gemini-research + /codex-research in parallel → research/ files.
