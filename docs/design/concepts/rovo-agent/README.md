# C1 — Rovo Agent (Triage Agent)

**Level**: 🛣️ Highway · **Complexity**: 🟧 Complex · **Convergence**: 🟢 Converged

## One-liner
A single Rovo Studio agent whose LLM orchestrates the four Forge Actions and reasons
over their output to localize failing code, then posts a work-note back.

## Responsibility
- Own the **playbook / system prompt** that drives the triage flow.
- Decide which Actions to call, in what order, with what args.
- Narrate its reasoning in chat (this narration IS the demo wow).
- Produce a structured root-cause hypothesis → hand to C5 for post-back.

## Rough flow (playbook)
1. Parse the ticket id from the chat prompt (`Triage INC0012345`).
2. Call `get-ticket` (C2) → project + **order id** + human notes + timestamp.
   (the order id keys the `get-logs` call in step 4)
3. Call `get-source` (C2) → GitLab master files for the project.
4. Call `get-logs` (C2) → Sumo failure-window logs (mock fixture).
5. (Confluence: native Rovo access — no Action needed.)
6. Reason (C3): match a log line → emitting `log(...)` → **file:line** + path.
7. Compose note → call `post-worknote` (C5) with confirm.

## Open questions (Round 1)
- Playbook as one agent prompt vs multiple sub-instructions? (lean: one prompt)
- Does Rovo cap at <5 Actions/agent? We have exactly 4 + native Confluence — fits, but
  confirm in C2 spike.
- How much of the reasoning to force into visible narration vs let the model choose?

## Depends on
C2 (Actions), C3 (reasoning method), C5 (post-back). Triggered by C4.
