## Key Findings

Rovo is an excellent choice for this hackathon demo as it natively provides LLM reasoning, a chat UI, and orchestration capabilities. However, running fully autonomous, headless write-actions via external triggers presents significant security and platform constraints.

## Autonomous trigger (what's possible / blocked)

*   **No Direct API:** There is no direct REST API to programmatically trigger a Rovo agent's prompt from an external system like ServiceNow.
*   **External Triggers (Workaround):** An external event (like a new ServiceNow ticket) *can* trigger a Rovo agent by sending an inbound webhook to an **Atlassian Automation Rule**. The Automation rule can then include an action to "Use Rovo Agent" and pass the webhook payload into the agent's prompt.
*   **Autonomous Write Actions (Blocked/Restricted):** While a Rovo agent can execute custom Forge Actions to read external data, **headless autonomous write actions are restricted**. When a Rovo agent is triggered headlessly via an Automation rule, it is restricted from invoking actions defined with `CREATE`, `UPDATE`, `DELETE`, or `TRIGGER` verbs. In a chat surface, irreversible write actions typically require human-in-the-loop confirmation. Therefore, having the agent *autonomously* POST a comment back to ServiceNow without human interaction is highly constrained by Atlassian's security model. The agent is primarily designed to return text (e.g., the root-cause hypothesis) to the chat surface or back to the Automation rule as the `{{agentResponse}}` smart value.

## Multi-action orchestration

*   **Chaining Actions:** Yes, a single Rovo agent can orchestrate multiple custom Actions. You can equip an agent with a GitLab Forge Action and a Sumo Logic Forge Action. The agent's LLM will autonomously decide to call them (sequentially or in parallel depending on the prompt), chain their results, and reason across the combined logs and code context.
*   **Limits:** 
    *   **Data limit:** Each action invocation is strictly limited to returning **5 MB** of data. You must filter logs and code snippets before returning them to the agent.
    *   **Tool limit:** Atlassian recommends assigning fewer than **5 actions/tools** per agent to maintain reliability.
    *   **Runtime:** Forge functions have a maximum runtime limit (typically 55 seconds for backend invocations).

## Forge vs Rovo Studio (effort)

*   **Easiest Path:** A **hybrid approach**—using a no-code Rovo Studio Agent equipped with custom Forge Actions—is by far the fastest path to a working hackathon demo. Rovo Studio handles the chat UI, LLM orchestration, and prompt management out-of-the-box. You only need to write the "glue" code (the Actions) to connect to external systems. Building a standalone Forge app with custom UI and AI capabilities would be significantly slower and reinvent the wheel.
*   **Building a Custom Action:** To build a custom Action that calls an external REST API (like GitLab or Sumo Logic), you must:
    1.  Write a Forge function in JavaScript/TypeScript using Atlassian's `@forge/api` and `api.fetch()`.
    2.  Define the `action` module in your `manifest.yml`.
    3.  Explicitly declare the external domains in the `manifest.yml` under `permissions.external.fetch.backend` to allow outbound egress.

## Easiest AI-first demo path

The absolute easiest and most compelling demo is a **Presenter-Invoked Chat Demo**. 

1.  The presenter opens the native Rovo chat sidebar in Jira or Confluence.
2.  The presenter types: *"Triage ServiceNow INC0012345"*.
3.  The Rovo agent autonomously reaches out via a custom Forge Action to fetch the ticket details from ServiceNow, uses another Action to fetch code from GitLab, and a third to query Sumo Logic.
4.  The agent reasons over the data and prints a formatted root-cause hypothesis in the chat.
5.  *(Optional)* The agent proposes a draft response and asks the presenter, *"Should I post this back to ServiceNow?"* Upon clicking "Confirm", a write-action executes.

**Why this is easier:** This completely bypasses the complexity of configuring inbound webhooks, Atlassian Automation rules, and fighting headless write-action security restrictions. It keeps the "star of the demo" (the AI reasoning) front and center in the native Atlassian UI, while still demonstrating complex multi-system orchestration.

## Confidence Levels

*   **High Confidence:** Rovo Studio + Forge Actions architecture, 5MB data limits, external fetch requirements in `manifest.yml`, lack of direct external API triggers, and the human-in-the-loop requirement for write actions.
*   **Medium Confidence:** Exact behavior of Automation rule restrictions on `UPDATE`/`CREATE` verbs, as Atlassian's AI platform is rapidly evolving and beta limits frequently change.
