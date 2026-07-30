# C5 — Work-Note Post-Back

**Level**: 🔧 Plumbing · **Complexity**: 🟨 Moderate · **Convergence**: 🟢 Converged

## One-liner
The agent composes a structured root-cause work-note and writes it back onto the
ServiceNow ticket via the `post-worknote` Forge Action (with in-chat confirm).

## Write-path constraint (DDS)
Rovo restricts autonomous headless writes. Two paths:
- **Demo**: chat agent calls `post-worknote` with a confirm step. ← used
- **Production**: Automation layer writes using `{{agentResponse}}` (narrated).
→ **Spike S1′** confirms the chat write with confirm actually works.

## Note structure (draft)
```
🔎 Triage hypothesis (AI-generated, review before acting)
Root cause: <one line>
Responsible code: <file>:<line>  — <repo link>
Evidence:
  • Log: "<exact matched log line>"  (Sumo, <timestamp>)
  • Maps to: <file>:<line> — `<the log(...) statement>`
Checked: ticket, GitLab master, Sumo window, Confluence <doc>
Confidence: <high|low>
```

## Carried DDS items folded here
- **R5** (creds): write scope for the ServiceNow integration user (shared with C2).

## Open questions
- Work-note vs comment field in ServiceNow (work-note = internal, right audience).
- Confirm UX: does the agent show the draft note before writing? (lean: yes — safer +
  better demo narration).

## Depends on
C1 (composes note), C3 (evidence), C2 (`post-worknote` Action).
