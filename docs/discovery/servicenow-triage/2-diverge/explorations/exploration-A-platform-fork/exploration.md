# Full Platform Fork Analysis

## Problem Statement

We need to build an AI agent that:
1. Listens for new ServiceNow incident tickets
2. Pulls code from GitLab (master branch, production)
3. Queries logs from Sumo Logic
4. Reads Confluence wiki docs
5. Correlates runtime logs with code logging statements to localize the failure
6. Posts a root-cause hypothesis back to ServiceNow as a comment

Success axis: hackathon demo WOW + fast build.

Two candidate architectures:
- **PATH 1: Rovo-native** — Agent logic lives in Atlassian Rovo, uses Forge actions to call external APIs
- **PATH 2: Standalone app** — Self-contained Python/Node backend service, calls corporate Copilot + orchestrates all 4 integrations

---

## PATH 1: Rovo-Native

### How It Would Work

1. **Agent definition**: Define a Rovo agent in Atlassian Rovo Studio (no-code or code-assisted)
2. **External API calls**: Implement Forge custom actions for GitLab, Sumo Logic, and ServiceNow
   - Each action is a Node.js function deployed on Forge
   - Rovo agent decides which actions to call based on natural language reasoning
3. **Confluence integration**: Native — Rovo has read access to Confluence
4. **Trigger**: Someone manually @mentions the agent in Confluence or Jira, or it's invoked via Flow automation

### Strengths

- **Confluence is native.** No additional API integration needed; Rovo has first-class access.
- **Model Context Protocol (MCP) support.** Rovo now supports MCP, which enables cleaner third-party integrations without custom Forge actions. This could simplify GitLab and Sumo Logic integration.
- **Team familiarity.** If the team is already using Atlassian products, the UI/UX is familiar.
- **No-code option.** Rovo Studio allows building agents without code, reducing development friction for non-engineers.
- **Enterprise governance.** Atlassian provides audit logging and enterprise oversight (important for demo to enterprise customers).
- **Audit trail.** Agents created in Rovo have built-in governance, so the hackathon team could claim "enterprise-ready" during the pitch.

### Critical Limitations

#### 1. **ServiceNow Trigger Problem (MAJOR)**

Rovo agents cannot be invoked by external systems like ServiceNow. Atlassian explicitly states: *"At the moment there isn't a supported way to call or orchestrate a Rovo Agent from something running outside the Atlassian platform."* Rovo agents don't expose webhooks, APIs, or any external endpoints.

**This is a showstopper for the "new ticket → agent runs automatically" workflow.**

To work around this, you'd need:
- A ServiceNow admin to set up a Business Rule (triggered on new incident)
- An Outbound REST Message that calls *your own* webhook endpoint (not Rovo)
- That endpoint would then somehow trigger the Rovo agent (currently not supported)

In practice, this means you'd **still need to build a standalone service** to listen to ServiceNow and orchestrate the Rovo agent—which defeats the purpose of using Rovo in the first place.

#### 2. **Forge Actions Are Node.js Only**

Custom Rovo actions run on Atlassian Forge, which has a Node.js runtime. If your team prefers Python, you'd either:
- Write Forge actions in Node.js + call out to an external Python service via API
- Abandon the local Forge action approach and rely entirely on MCP

Both add complexity and latency.

#### 3. **5 MB Dependency Size Limit**

Forge functions have a 5 MB dependency size limit. Pulling large codebases from GitLab or returning large log datasets from Sumo Logic could hit this limit, requiring segmentation or external storage.

#### 4. **Response Time**

Teams building Rovo agents report that **API-heavy workflows are slow**. Each action call to GitLab, Sumo, Confluence adds latency. A three-to-five-second response time isn't unusual, which doesn't inspire awe in a demo ("waiting for the agent...").

#### 5. **Orchestration Complexity Remains Manual**

While Rovo added subagents in 2026, real-world experience shows that orchestrating multiple specialized tasks still requires **explicit consultants to design the workflow.** The agent won't autonomously combine GitLab + Sumo + Confluence data into a coherent hypothesis—you need to prompt it correctly or design multi-step flows.

#### 6. **No Reverse Integration Path**

Cannot build a lightweight ServiceNow → Rovo bridge without substantial custom work. You're forced to either:
- Manual trigger (demo only, not impressive)
- Build a separate webhook listener (which becomes the real agent in a standalone app)

### Hackathon Effort Estimate (Rovo)

1. Set up Forge app + Rovo agent definition: 1–2 hours
2. Write 3 custom Forge actions (GitLab, Sumo Logic, ServiceNow): 3–4 hours each = 9–12 hours
3. Debug cross-action orchestration: 2–3 hours
4. **STILL** need ServiceNow Business Rule + Outbound REST Message setup to auto-trigger: 1–2 hours
5. Polish demo narrative: 1–2 hours

**Total: 16–21 hours** (and half of that is work you'd replicate if you pivoted to standalone).

---

## PATH 2: Standalone App (Python/Node Backend)

### How It Would Work

1. **ServiceNow trigger**: A ServiceNow admin sets up a Business Rule (triggered on new incident) that sends an Outbound REST Message to your webhook endpoint.
2. **Your backend**: A simple Flask/Express API server listening for the webhook, receives ticket details, and spawns the triage workflow.
3. **Agent logic**: Calls corporate Copilot (GitHub or M365) with a prompt that instructs it to:
   - Pull code from GitLab
   - Query Sumo Logic logs
   - Read Confluence docs
   - Correlate logs with code
4. **Post back**: Posts the result (root-cause hypothesis) as a ServiceNow comment via the ServiceNow API.

### Strengths

#### 1. **Trigger Mechanism Is Simple**

ServiceNow Business Rule → Outbound REST Message → Your webhook. This is well-documented, battle-tested, and requires just one ServiceNow admin to set up. No reverse-engineering of Rovo required.

#### 2. **Full Orchestration Control**

Your Python or Node.js code controls the entire workflow. You can:
- Parallelize GitLab + Sumo Logic queries
- Chain results (e.g., "fetch logs for this service → pass to Copilot → wait for analysis")
- Catch errors gracefully and post partial results
- Debug with print statements

This is much easier than debugging Rovo's agent reasoning.

#### 3. **GitHub Copilot SDK Is Production-Ready**

GitHub released the Copilot SDK in GA (June 2026) with official support for headless CLI server mode. Your backend can:
```
copilot-cli server --host 0.0.0.0 --port 8080
```

Then your backend talks to the CLI server over TCP. It's supported, documented, and doesn't rely on unofficial proxies.

#### 4. **Language Choice**

Use Python (with requests, Flask) or Node.js. No dependency size limits. Existing libraries for GitLab, Sumo Logic, Confluence are mature.

#### 5. **Demo Flow Is Impressive**

Demo script: Open ServiceNow, create a new incident ticket. Within 30–60 seconds, the agent posts a comment with root-cause hypothesis. All within the customer's existing ServiceNow UI—no need to context-switch to Rovo/Jira/Confluence. **The demo stays in the ticket.**

#### 6. **Fallback Plan**

If Copilot integration stalls, you can still build a simpler version that:
- Fetches GitLab code
- Queries Sumo Logic
- Pulls Confluence docs
- Uses regex/keyword matching to suggest a root cause (no AI needed)

Still useful and deployable. Rovo without working actions is broken.

### Limitations

#### 1. **Copilot Access (Depends on Corporate Choice)**

**GitHub Copilot Path:**
- Requires GitHub Copilot subscription (likely already approved if org uses GitHub)
- Headless CLI server is official and well-documented
- No per-call cost (subscription-based)
- ✅ Good fit for hackathon

**M365 Copilot Path:**
- Requires Enterprise E3/E5 subscription + $30–90/user/month Copilot add-on
- No standard backend API; requires Copilot Studio (low-code, Power Automate-based)
- Custom integrations via Power Automate are slower to iterate on
- ❌ More friction for hackathon

**Unknown**: Which one is "approved" at the customer's company?

#### 2. **Service Deployment**

You need to run the backend somewhere:
- AWS Lambda (with persistent connection to Copilot CLI server—tricky)
- EC2 instance or corporate on-prem server
- Kubernetes cluster

For a hackathon, a simple EC2 instance or on-prem VM is fine. But demo relies on that service staying up during the pitch.

#### 3. **Integration Code Is Manual**

No low-code UI like Rovo Studio. You're writing REST API calls to GitLab, Sumo Logic, ServiceNow, and Confluence. Doable in 2–4 hours per integration, but you own the debugging.

#### 4. **Demo Is Outside Familiar Atlassian UI**

Unlike Rovo (which appears in Jira/Confluence), the standalone app runs invisibly in the backend. Demo lives in ServiceNow. Less "wow" if the audience expects to see AI reasoning in Confluence.

### Hackathon Effort Estimate (Standalone)

1. Set up GitHub Copilot SDK headless server: 0.5–1 hour
2. Build Flask/Express webhook endpoint: 0.5–1 hour
3. Wire GitLab API client: 1 hour
4. Wire Sumo Logic API client: 1–1.5 hours
5. Wire Confluence API client: 0.5–1 hour
6. Wire ServiceNow API client (read ticket + post comment): 0.5–1 hour
7. Write correlation logic (match logs to code): 2–3 hours
8. Copilot prompt engineering + testing: 2–3 hours
9. End-to-end testing + demo polish: 1–2 hours

**Total: 9–14 hours** (most of which is linear and parallelizable).

---

## Comparison Table

| Criterion | Rovo-Native | Standalone App |
|-----------|-------------|----------------|
| **Trigger feasibility** | Requires custom bridge (not built) | Native via ServiceNow Business Rule |
| **Orchestration control** | Limited (action-based) | Full (code-based) |
| **Response time** | Slow (API chaining) | Faster (parallel queries) |
| **Hackathon effort** | 16–21 hours | 9–14 hours |
| **LLM access** | Copilot via Forge action (unclear) | Direct via SDK |
| **Demo impressiveness** | "Agent running in Jira" | "Auto-triage in ServiceNow" |
| **Fallback if stalled** | Broken | Partial solution still works |
| **Production-ready feel** | High (Atlassian governance) | Medium (DIY) |
| **Team familiarity risk** | Forge/Rovo unknown to most teams | REST APIs + backend known pattern |

---

## The Real Issue with Rovo

The fatal flaw isn't Rovo's capabilities—it's the **trigger problem**. You want "new ServiceNow ticket → agent auto-runs." Rovo cannot be called from ServiceNow. So you'd:

1. Build ServiceNow → webhook bridge (standalone code)
2. Bridge calls Rovo agent
3. Rovo agent runs its actions
4. Results posted back to ServiceNow

But at step 1, you've already written the hard part (a backend service). Why not just have that backend orchestrate GitLab + Sumo + Copilot directly? You skip the Rovo indirection, reduce latency, and keep debugging in one place.

---

## Recommendation: PATH 2 (Standalone App)

**Why?**

1. **Faster to demo.** ServiceNow trigger is proven and simple.
2. **Fewer unknowns.** GitHub Copilot SDK is official and documented.
3. **Better control.** Code-based orchestration is easier to debug than Rovo's action model.
4. **Lower risk.** If Copilot setup stalls, fallback still delivers value.
5. **Hackathon timeline.** 9–14 hours is feasible for a 2–3 day hackathon.

**What to validate ASAP:**
- Which corporate Copilot is approved (GitHub or M365)?
- Can a ServiceNow admin help set up the Business Rule trigger?
- Do we have read-only API access to production Sumo Logic logs?

If any of those blockers appear, Rovo becomes more attractive. But as-is, standalone app wins.
