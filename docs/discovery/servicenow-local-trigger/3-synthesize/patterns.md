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

> ⚠️ **`C*` namespace warning** (added 2026-07-30 by `/doc-test dds`, gap G5). This file uses
> `C*` ids in **two different senses without qualifying them**, which is genuinely
> ambiguous to a reader:
> - **Above** (`C5`/`C7`, and `C1`/`C2` in `1-elicit/constraints.md`) — this workspace's own
>   **CONSTRAINTS** (C1 cloud-hosted, C2 local laptop, C5/C7 blocked tunnels, C6 SN outbound
>   REST).
> - **Below** (`C6`, `C1`, `C2` in the next section) — **CONCEPTS/SPIKES of the
>   `copilot-cli-runtime` DDS** (C6 ToS gate, C1 proxy sidecar, C2 end-to-end run).
>
> Note that **`C6` means two different things** across that boundary — a local constraint
> *and* the Copilot ToS gate. `STATUS.md` does qualify it ("all in `copilot-cli-runtime`");
> this file did not. A third meaning exists elsewhere: `servicenow-triage`'s Round-1
> concepts C1–C7 (later renamed RC1–RC7). Always qualify `C*` with its workspace.

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
