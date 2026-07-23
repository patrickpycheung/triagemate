# CDS Workspace — ServiceNow Technical Triage Assistant (Rovo-native)

**Design methodology**: Convergent Design System (CDS). This workspace turns the
confirmed DDS concepts (RC1–RC6) into an implementation-ready design for a
**hackathon prototype**. Optimize for demo-wow + ease of build.

## Problem (from DDS)
Triage a newly-created technical ServiceNow ticket by pulling GitLab `master` code,
Sumo Logic logs, and Confluence docs, correlating log lines to code with an LLM, and
posting a root-cause hypothesis (ideally the responsible **file:line**) back as a
ticket work-note.

## Direction
A single **Rovo agent** ("Triage Agent"), invoked in Rovo chat (`Triage INC0012345`),
whose own LLM reasons over ticket + GitLab master + Sumo + native Confluence to
localize the failing code and post a work-note back. Standalone app = documented
alternative (RC7, not built).

## Concepts
| ID | Concept | Maps to DDS |
|----|---------|-------------|
| C1 | [rovo-agent](concepts/rovo-agent/) — the Triage Agent + playbook prompt | RC1 ⭐ |
| C2 | [forge-actions](concepts/forge-actions/) — external connectors | RC2 |
| C3 | [log-code-reasoning](concepts/log-code-reasoning/) — AI log↔code correlation | RC3 ⭐ |
| C4 | [trigger](concepts/trigger/) — chat-invoked demo | RC4 |
| C5 | [work-note-postback](concepts/work-note-postback/) — structured note write-back | RC5 |
| C6 | [demo-safety](concepts/demo-safety/) — scope, fixtures, fallbacks | RC6 |

See [STATUS.md](STATUS.md) for the convergence matrix.

## Honest caveat (carried from DDS, must be in the pitch)
Demo "autonomy" = the agent's reasoning + multi-system actions, NOT literal auto-fire
on ticket creation. Presenter invokes in chat. **Chat-invoked demo, webhook-triggered
future.**
