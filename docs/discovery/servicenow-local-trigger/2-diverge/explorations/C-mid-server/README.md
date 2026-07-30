# Exploration C — ServiceNow MID Server

**Bias**: Prior-art / risk-averse (enterprise-sanctioned pattern). **Verdict**: 🟡 Viable
in principle, too heavy for the hackathon; a production answer.

## Idea
The MID Server is ServiceNow's official on-prem integration agent: a Java service
installed **inside** the corp network that makes an **outbound-only** HTTPS(443)
connection to the instance and polls the **ECC queue** for work — no inbound firewall
holes. It's the sanctioned way for cloud ServiceNow to drive something behind a
corporate firewall. It can then call an internal endpoint (e.g. the local app on
`localhost`) or run scripts.

## Why it's not the pick for now
- **Same install barrier** as tunnels: standing up a MID Server on a locked-down corp
  laptop needs IT approval + service install rights (C5). Not a hackathon-week thing.
- **Heavyweight**: separate service, ECC-queue plumbing, MID config in the instance —
  large surface for a demo whose brain is one Spring app.
- It still ends up **pull-based** (outbound poll of ECC), so it buys us the same
  direction-inversion that Exploration B gets with ~1% of the effort.

## When it wins
The right answer if this graduates to a **sanctioned production** deployment: IT-blessed,
auditable, no per-app polling of the Table API, and reusable for other integrations.
Record as the "productionization" path, not the hackathon path.

## Trust / risk
📚 Documented · 🟢 Low technical risk, 🟠 Uncertain on *whether IT permits install*.
