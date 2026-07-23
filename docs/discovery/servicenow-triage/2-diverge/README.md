# Phase 2: DIVERGE — Summary

**Status**: ✅ Complete. 4 explorations + external research + verification spike.

## Explorations (different biases, independent)
- **A — Platform fork** (⚖️🔧⚡): standalone-orchestrator vs Rovo-native. Found Rovo can't be externally triggered; claimed a headless Copilot SDK exists.
- **B — Log→code engine** (🏗️🧠🚀): recommended hybrid stack-trace → AST log-template index → Drain3 → grep fallback; LLM only for tie-break/narrative. **The correlation is deterministic.**
- **C — Integration plumbing** (📚🛡️): SN trigger = Business Rule/Flow → Outbound REST (polling fallback); GitLab live, Sumo mock, Confluence cached; per-system call sequences + demo risks.
- **D — Demo narrative** (👤⚡🎬): wow beat = autonomous work-note naming file+line + quoting matched log line ~60s after ticket creation; live-vs-mock policy; fallbacks.

## External research (Triple Perspective)
- **Gemini** (web-aware): Rovo reaches external APIs via Forge `api.fetch` + manifest egress; not webhook-triggerable (needs Automation bridge); GitHub Copilot has no backend API; M365 Copilot Graph = delegated-perms only. → research/platform-feasibility-gemini.md
- **Codex**: `--- Failed ---` (CLI stream closed 1.4s).

## Verification spike (Claude)
Resolved the A↔Gemini conflict on Copilot backend access to **⚠️ Uncertain**: a headless GitHub Copilot SDK plausibly exists (2026), but headless auth with corporate creds is unproven → day-1 spike S1. De-risked because correlation is deterministic (B) and the LLM is pluggable.

## Files
- exploration-brief.md · explorations/{A,B,C,D}/ · explorations/exploration-A-platform-fork/research/{gemini,codex}.md
