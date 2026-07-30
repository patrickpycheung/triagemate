# Problem Statement — ServiceNow Technical Triage Assistant

## The problem
When a **technical** ticket is created in ServiceNow (tickets are logged there *only* for technical problems), a human has usually done a little initial investigation. The engineer who picks it up then spends significant time re-gathering context: finding the project, reading code, hunting logs, checking docs. We want to **automate the deep-triage step** so that by the time an engineer looks at the ticket, an AI agent has already:

1. Identified the relevant project (from the project code in the ticket).
2. Pulled the production code (GitLab `master`).
3. Queried the logs (Sumo Logic) for the failure window/behaviour.
4. Read the relevant documentation (Atlassian wiki: requirements, design, release notes).
5. **Correlated the log lines from Sumo against the `log(...)` statements in the source** to reconstruct the sequence of execution and localize the failure.
6. Produced a **root-cause hypothesis that ideally points at the responsible file/function**, posted back as a comment/work-note on the ticket.

## Signature technique
The differentiating idea: **match log messages emitted at runtime (Sumo) to the logging statements in the code**. Because the code contains the literal/format strings that produce those log lines, we can:
- Reconstruct the actual execution path that led to the failure.
- Pinpoint which code branch/line emitted the last-good and first-bad messages.
- Narrow "responsible code" to a specific region.

## Context
- **Trigger**: new technical ticket in ServiceNow.
- **Consumer**: the assigned engineer — findings posted as a ticket comment/work-note (human still fixes).
- **Environment**: corporate network; internal systems (ServiceNow, GitLab, Sumo Logic, Atlassian/Confluence).
- **Nature**: a **hackathon prototype** to demonstrate the possibility of AI agents. If approved, a proper system is designed later (via CDS).

## Explicit non-goals (for the prototype)
- Not auto-fixing or auto-closing tickets.
- Not production-grade security/hardening.
- Not supporting every project/language perfectly — a convincing end-to-end path is enough.
