# Exploration A — Push via a tunnel (webhook to the laptop)

**Bias**: First-principles / prior-art. **Verdict**: ❌ Dead end on the corp laptop.

## Idea
ServiceNow Business Rule (async, on Insert) fires an outbound `RESTMessageV2` POST to a
public URL that maps to the laptop's local app — the classic webhook. To give the
laptop a public URL you need a tunnel (ngrok / Cloudflare Tunnel / Tailscale Funnel),
since corp NAT gives no public inbound.

## Why it dies here
- Tailscale **can't be installed** (C3); Cloudflare is **blocked** (C4); ngrok is the
  same category and is routinely blocked by corp DNS/DPI (C5, C7).
- Tunnels only work by making an **outbound** connection to the provider — precisely
  the traffic corp egress filtering blocks for these providers.
- Without a tunnel there is no public inbound at all → ServiceNow cannot reach the app.

## Trust / risk
🔍 Inferred (strong) · 🔴 Unproven-positive → treat as infeasible. The ServiceNow half
(outbound REST) is 📚 fine; the **laptop-ingress half is the blocker**.

## Salvage
Only viable if IT sanctions a specific ingress (a corp API gateway / reverse proxy, or
an approved tunnel). That's an org decision, not something we can engineer around —
record as an operator/IT ask, don't design on it for the hackathon.
