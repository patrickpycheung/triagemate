# Problem & constraints (Phase 1)

## Problem (operator-confirmed framing)
The company will not issue LLM **API keys** — only **GitHub Copilot seats**. TriageMate's
live reasoning path today needs an OpenAI-compatible endpoint + API key. We want that
intelligence to run off the **Copilot subscription** instead. The operator's hypothesis:
adopt the **Copilot CLI** as the agent runtime and build **MCP servers** around
ServiceNow, Sumo Logic, Confluence (Atlassian) and GitLab, leaning on Copilot CLI's
**built-in planning/agent loop** so we don't build that ourselves.

## Constraints & evidence
| # | Constraint | Trust | Source |
|---|-----------|-------|--------|
| L1 | Auth is a **Copilot subscription**, no API keys | 📚 | operator |
| L2 | Runs on the **locked-down corp laptop** (no Tailscale, Cloudflare blocked, restricted installs) | 📚 | [[servicenow-local-trigger]] DDS |
| L3 | Copilot CLI has a non-interactive mode `copilot -p "<prompt>"` and a `--server` mode | 📚 | [DevLeader headless](https://www.devleader.ca/2026/07/27/running-github-copilot-cli-in-scripts-and-cicd-pipelines-headless-mode) |
| L4 | Copilot CLI supports **custom local (STDIO) MCP servers** via `~/.copilot/mcp-config.json` / repo `.copilot/mcp-config.json`; **no OAuth for remote MCP**; **128-tool limit**; **MCP is org-policy-gated (off by default)** | 📚 | [GitHub Docs: add MCP servers](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers) |
| L5 | Headless `copilot -p` + MCP is currently **buggy**: prompt runs before MCP connects (empty tools first turn); empty-message injection at **≥7 tools**; workspace `.mcp.json` sometimes silently skipped | 📚 | [#3329](https://github.com/github/copilot-cli/issues/3329), [#4038](https://github.com/github/copilot-cli/issues/4038), [#3313](https://github.com/github/copilot-cli/issues/3313) |
| L6 | Copilot's abuse-detection **flags excessive automated/scripted use**; ToS narrowly allows **one machine-user account for automated tasks**; violations risk suspension | 📚 | GitHub ToS / acceptable-use |
| L7 | Copilot can be exposed as a **local OpenAI-compatible endpoint** (LiteLLM `github_copilot` provider via OAuth device-flow; or `copilot-api`) — **unofficial**, rate-limited | 📚 | [LiteLLM Copilot](https://docs.litellm.ai/docs/tutorials/github_copilot_integration), [copilot-api](https://github.com/ericc-ch/copilot-api) |
| L8 | We already have a working, bounded, advisory, **tested** agent layer targeting an OpenAI-compatible endpoint | 🔬 | this repo (5 tests green) |

## Success criteria
1. Live reasoning runs off the **Copilot seat** (no API key), on the corp laptop.
2. Preserve TriageMate's non-negotiables: **advisory-only**, **bounded** tool use (J8),
   auditable trace, and the **offline deterministic demo** path.
3. Prefer the path with the **least new fragility** and the **clearest ToS standing**.
4. Be honest about what needs **IT/legal sign-off** (subscription terms) vs what we can
   just build.
