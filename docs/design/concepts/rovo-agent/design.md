# C1 — Rovo Agent — Design

The buildable spec: the `manifest.yml` shape + the playbook prompt. This is the core
deliverable; everything else is glue.

## manifest.yml (sketch)
```yaml
app:
  id: <ari>
  runtime: { name: nodejs24.x }

permissions:
  scopes:
    - read:confluence-content.summary   # so the agent's native Rovo knowledge can ground step 4
  external:
    fetch:
      backend:
        - 'https://<instance>.service-now.com'   # get-ticket + post-worknote
        - 'https://gitlab.example.com'           # get-source
        # Sumo omitted — get-logs reads the mock fixture (demo safety, C6).
        # => TWO egress domains total (keep C6's checklist in sync).
# Confluence is native Rovo knowledge: the payments runbook page must be in the SAME
# Atlassian site and indexed by Rovo. If it isn't wired, step 4 silently no-ops — so
# the playbook treats Confluence as BEST-EFFORT (see step 4), never a hard dependency.

modules:
  rovo:agent:
    - key: triage-agent
      name: Triage Agent
      description: Triages a technical ServiceNow ticket by correlating Sumo logs to GitLab code.
      prompt: <see playbook below>
      conversationStarters:
        - Triage INC0012345
      actions: [get-ticket, get-source, get-logs, post-worknote]

  action:
    - { key: get-ticket,    function: getTicket,    actionVerb: GET,    description: "...", inputs: { ticketId: {type: string, required: true} } }
    - { key: get-source,    function: getSource,    actionVerb: GET,    description: "...", inputs: { project: {type: string, required: true} } }  # returns files + repoUrl + ref (for C5's GitLab link)
    - { key: get-logs,      function: getLogs,      actionVerb: GET,    description: "...", inputs: { orderId: {type: string, required: true} } }
    - { key: post-worknote, function: postWorknote, actionVerb: CREATE, description: "...", inputs: { ticketId: {type: string, required: true}, noteBody: {type: string, required: true}, confirmed: {type: boolean, required: true} } }  # confirmed MUST be true — function refuses to write otherwise (G4)

  function:
    - { key: getTicket,    handler: index.getTicket }
    - { key: getSource,    handler: index.getSource }
    - { key: getLogs,      handler: index.getLogs }
    - { key: postWorknote, handler: index.postWorknote }
```
(Confluence = native Rovo knowledge, not an action. 4 actions ≤ 5-action cap. ✔)

## Playbook prompt (the agent's brain)
```
You are the Triage Agent. When asked to triage a ticket id (e.g. "Triage INC0012345"),
follow this procedure and NARRATE each step so the user sees your reasoning:

1. get-ticket(ticketId) → note the project, short description, and the order id.
   - If the id is malformed or get-ticket errors → ask the user for a valid ticket id. STOP.
2. get-source(project) → the current master source files (also gives repoUrl + ref).
3. get-logs(orderId) → the failure-window log lines.
   - If any action times out/errors → say which source failed and continue with what you
     have (degraded), or ask to retry. Never fabricate a source's contents.
4. BEST-EFFORT: consult Confluence for the service's known-issues / runbook. If nothing
   is indexed, skip it — do NOT claim you checked Confluence if you retrieved nothing.
5. CORRELATE: find the ERROR log line that best explains the failure. Identify the
   EXACT source line that emits it (match the log's literal format string to a
   log(...) statement). State the file:line — cite the line the literal tokens are on
   (a log call may span several lines; cite the format-string line).
   - You MUST quote the exact log line verbatim AND the exact source line it maps to.
   - If you cannot find a real emitting line, say so and give ranked candidates —
     NEVER invent a file:line. (degraded mode → use the degraded note format, C5)
5b. SELF-CHECK: re-read the source line you cited and confirm its literal tokens match
    the log line's non-variable tokens. If they don't, drop confidence to low / degrade.
6. Explain the ROOT CAUSE hypothesis in one or two sentences, referencing the responsible
   code. (You are localizing the failure site + hypothesizing cause, not proving it.)
7. Draft a work-note (see C5 format) and SHOW IT to the user. Ask "Post this to <ticket>?"
8. Only after an explicit "yes", call post-worknote(ticketId, noteBody, confirmed=true).
   Never set confirmed=true without a shown draft + explicit user yes. If post-worknote
   errors or its outcome is unknown, report that — do NOT retry blindly (avoid double-post).

Confidence: say "high" only when you quoted a real log line AND a real source line that
literally match (and 5b passed); otherwise "low".
```

## Orchestration notes
- The agent's LLM decides call order; the playbook makes it deterministic enough for a
  repeatable demo.
- Every `action` call is surfaced in Rovo chat → the audience watches 4 systems consulted
  (the free wow, no extra UI to build).

## Convergence
Playbook + manifest settled against all three spikes; hardened in R3 doc-test. → 🟢 Converged.
