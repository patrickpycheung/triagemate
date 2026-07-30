# Synthesis (Phase 3)

## The one pattern that decides everything: direction of the connection
On a locked-down corp laptop, **only the laptop can initiate a connection**. Every
option sorts by who dials whom:

| Option | Who initiates | Works behind corp firewall? | Effort | Automated? |
|--------|---------------|-----------------------------|--------|------------|
| A Push + tunnel | ServiceNow → laptop (via tunnel) | ❌ tunnel blocked / uninstallable | — | (would be) |
| **B Outbound polling** | **laptop → ServiceNow** | **✅ same channel app already needs** | **small** | **✅** |
| C MID Server | laptop-side agent → ServiceNow | ✅ (outbound) but install-gated by IT | large | ✅ |
| D Manual | human → laptop | ✅ | none | ❌ |

**B and C are the same physics** (laptop-initiated outbound pull); B achieves it in the
app for ~1% of C's effort. A is impossible without IT opening a sanctioned ingress.

## Dead ends (and why — kept so we don't rediscover them)
- **Any push/webhook to the laptop** — needs inbound; corp NAT + blocked tunnels forbid it.
- **Port-forwarding / public IP** — not available on a corp-managed laptop.
- **ngrok** — same category as Cloudflare/Tailscale; assume blocked (C5/C7).

## What this means for the FIRST (Copilot-CLI) DDS
Connectivity does **not** force interactive-only. "Headless/automated" stays on the
table via polling. The remaining gates on headless are then that DDS's job, not a network
limit — and there are **three**, not one: **C6** (licensing/ToS for unattended use),
**C1** (proxy authenticates with the corp seat + binary permitted by endpoint policy) and
**C2** (end-to-end run). **C6 alone does not clear headless.**

> Framing note: at the time this was written the assumed runtime was *Copilot CLI*. That
> DDS since chose **E2 — Copilot as an LLM backend behind a local proxy** — so the gate is
> about driving the seat programmatically, not about Copilot CLI specifically.

## Residual unknown — ✅ CLOSED 2026-07-29
Does outbound HTTPS to the ServiceNow instance succeed from the **real** corp laptop
(directly or via corporate proxy)? Everything — even manual — depends on it.

> **Resolved.** The operator ran the spike on the real corp laptop: direct outbound
> HTTPS + basic auth **works**, no proxy needed. K1 outbound polling is viable.
> See `STATUS.md` and `4-decide/concepts-extracted.md`.
