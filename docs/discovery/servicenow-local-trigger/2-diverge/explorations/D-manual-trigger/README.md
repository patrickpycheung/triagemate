# Exploration D — Manual / human trigger (fallback)

**Bias**: Minimum-viable / risk-averse. **Verdict**: ✅ Always-available fallback.

## Idea
No automation of the trigger at all: an engineer supplies the incident number and the
app runs — the current demo UI (`POST /api/diagnose/{number}`) or a CLI one-liner.
Optionally a ServiceNow **UI Action** ("Run AI triage") that deep-links/handoffs, but
the actual run is still initiated locally by the human.

## Role
This is the guaranteed floor — it needs nothing but the app running and outbound access
to ServiceNow (to read the ticket + post comments). It's what we ship if even polling
proves undesirable, and it maps to the "interactive/human-run" branch of the Copilot DDS.

## Trade-off
Loses the "no human in the loop" property. Perfectly fine for a hackathon demo (you
*want* a human driving the live demo anyway) and for a cautious first production step.

## Trust / risk
🔬 effectively proven — this already works today. 🟢 None.
