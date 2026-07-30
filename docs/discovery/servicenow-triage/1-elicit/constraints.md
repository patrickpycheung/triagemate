# Constraints

## Hard constraints
- **AI engine ∈ {corporate Copilot subscription, Atlassian Rovo}** — both already approved. No other AI platforms in scope. 🔍 Inferred (from operator answer)
- **Runs inside corporate network** — must reach ServiceNow, GitLab, Sumo Logic, Confluence. 📚 Documented (operator)
- **Code pulled from GitLab `master` only** (= production versions). 📚 Documented (operator)
- **Trigger = new ServiceNow ticket** (technical tickets only). 📚 Documented (operator)
- **Output = comment/work-note on the ServiceNow ticket.** 📚 Documented (operator)

## Soft constraints / preferences
- **Ease of implementation > everything** — it's a hackathon, limited time. 📚 Documented (operator)
- **Security matters** but prototype-level is acceptable; not the primary axis. 📚 Documented (operator)
- **Cost is not a driver** — both AI options are already paid/approved. 📚 Documented (operator)
- **Demo wow is the success axis** — a compelling end-to-end story on stage. 📚 Documented (operator)

## Key open unknowns (feasibility — candidates for research/spike)
- ❓ Can the **corporate Copilot** be called programmatically from a standalone backend? (Copilot is historically IDE-scoped; M365 Copilot Studio / GitHub Copilot API differ wildly.) — HIGH LEVERAGE: decides whether the standalone path can even use Copilot as its reasoning engine.
- ❓ Can **Rovo agents call external APIs** (GitLab, Sumo Logic, ServiceNow) via Actions/Forge/Rovo Connect, and how much effort is a custom connector?
- ❓ Which "Copilot" is it — GitHub Copilot vs Microsoft 365 Copilot? Changes the integration story entirely.
- ❓ How does ServiceNow trigger an external app (Business Rule + outbound REST / webhook / Flow Designer)?
