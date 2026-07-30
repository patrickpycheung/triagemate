# E3 — ToS & licensing feasibility (the gating constraint)

**Bias**: Risk-averse / constraint-driven. **Verdict**: 🟠 Uncertain — the true blocker;
needs an IT/legal answer, not engineering. **Applies to BOTH E1 and E2.**

## The question
Does the **corporate** GitHub Copilot subscription permit using the seat to power an
application's reasoning — especially **unattended / automated** (polling-driven) triage,
and via **unofficial proxies** (LiteLLM/copilot-api) or scripted `copilot -p`?

## What the public terms say (L6)
- GitHub's abuse-detection **flags excessive automated/scripted Copilot use**; repeated
  flags → **suspension**.
- ToS narrowly permits **one machine-user account used exclusively for automated tasks** —
  i.e. automation isn't blanket-forbidden, but it's constrained and account-specific.
- Copilot does **not** publish an official public LLM API for this; proxy access is
  "use responsibly, within acceptable-use" — explicitly **not guaranteed**.

## Why this gates everything
- Both E1 (scripted `copilot -p`) and E2 (proxy) run the seat **programmatically**. At
  demo scale (a human running it) that's low-risk; at **unattended polling scale** it
  drifts toward "excessive automated use".
- A **corporate/enterprise** agreement may say more (allow, forbid, or require a specific
  service/machine account) than the public individual terms — only the company knows.

## Decision impact
- **Interactive** use (engineer runs it per incident) is the **safe** reading of the terms.
- **Headless/unattended** use is the risk zone → keep in scope but **contingent on an
  explicit IT/legal ok** (or a sanctioned machine account + rate caps).

## Trust / risk
📚 on the public terms · 🟠 Uncertain on the corporate agreement → **operator/IT/legal
spike** (can't be resolved by code). This is an **ADM-4 / operator** item.
