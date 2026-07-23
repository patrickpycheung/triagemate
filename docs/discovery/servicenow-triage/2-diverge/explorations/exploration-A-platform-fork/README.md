# Platform Fork: Rovo-Native vs Standalone App

## Summary

This exploration compares two architectural paths for an AI-driven ServiceNow ticket triage agent:
1. **Rovo-native**: Build inside Atlassian Rovo using Forge actions to orchestrate integrations
2. **Standalone app**: Deploy a Python/Node backend that orchestrates all four systems (GitLab, Sumo Logic, Confluence, ServiceNow)

## Recommendation: Go with PATH 2 (Standalone App)

**Promising? YES — with clear winner identified.**

### Top Reasons

1. **Trigger mechanics are simpler in reality.** Rovo cannot be invoked by ServiceNow events; you'd end up building a standalone listener anyway to catch "new ticket" events via Business Rule → Outbound REST message. The standalone path just cuts out the middleman.

2. **Orchestration control.** Standalone app gives you full visibility and control over the correlation logic (matching log lines to code statements). Rovo's action-based model requires custom JavaScript/TypeScript on Forge, which is harder to debug than a self-contained Python/Node service.

3. **GitHub Copilot SDK is ready.** The headless CLI server mode is officially documented and works as a backend service. No custom proxies or ToS risks needed.

4. **Demo timeline.** Standalone: define one webhook endpoint, wire 4 API calls, test end-to-end. Rovo: stand up Forge, define 4 custom actions, still need ServiceNow setup anyway.

5. **Fallback path if time runs out.** If Copilot integration stalls, a standalone app can still query GitLab + Sumo + Confluence and post a hypothesis without the AI engine—still useful. Rovo with incomplete actions is broken.

---

## Key Findings by Trust Level

**HIGH CONFIDENCE (🔬 Spiked)**
- ServiceNow has no native outbound webhooks; requires Business Rule + Outbound REST Message setup
- GitHub Copilot SDK supports official headless CLI server mode for backend services

**MEDIUM CONFIDENCE (📚 Documented)**
- Rovo agents can call external APIs via Forge actions (Node.js only)
- M365 Copilot requires enterprise licensing and Copilot Studio subscription

**LOWER CONFIDENCE (🔍 Inferred)**
- Hackathon timeline favors standalone app (fewer unknowns)
- Rovo path requires double integration work (Rovo + ServiceNow trigger)

---

## Open Questions

1. **Which "corporate Copilot" is approved?** GitHub or M365? This changes the auth/deployment model.
2. **ServiceNow admin availability.** Can we get a ServiceNow admin to set up the Business Rule trigger, or will we need to demo with polling?
3. **Sumo Logic query access.** Do we have read-only API creds for production logs, or are we limited to a test instance?

---

## Next Steps

If team agrees on standalone app:
- Spike GitHub Copilot SDK headless server setup (1–2 hours)
- Build ServiceNow Scripted REST API endpoint to receive webhook (1–2 hours)
- Prototype GitLab API client and Sumo Logic query handler (2–3 hours)
- Write correlation logic matching log lines to code (2–4 hours)
- Polish demo narrative and post-back comment formatting (1–2 hours)

**Estimated hackathon effort: 8–13 hours for working end-to-end demo.**
