# Success Criteria

Primary axis chosen by operator: **Hackathon demo wow** (compelling end-to-end demo > production hardening).

## What "wow" means here (measurable-ish)
1. **End-to-end liveness**: create a ticket in ServiceNow → within ~1–2 min a work-note appears on the ticket with AI triage. The autonomy is the wow.
2. **Visible reasoning chain**: the note shows it actually *used* the four sources — quotes a real log line from Sumo, links a real GitLab file/line, cites a real wiki page. Not a generic LLM guess.
3. **The signature trick lands**: at least one demo case where log-to-code correlation names a plausible responsible file/function, and the audience can see *why* (the matched log string).
4. **Believable, not perfect**: judges believe this would save engineers real time and could become a real system.

## Anti-goals for scoring
- Perfect accuracy across all projects — not required.
- Handling adversarial/edge tickets — not required.
- Security review passing — not required for the prototype (note it as future work).

## Guiding tension
Every design choice is judged by: *does it make the 3-minute demo more convincing while staying buildable in hackathon time?*
