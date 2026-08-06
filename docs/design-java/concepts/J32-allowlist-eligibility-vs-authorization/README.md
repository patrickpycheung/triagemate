# J32 — Allowlist eligibility vs authorization (one list is being asked two questions)

**State**: 🔵 **Proposed** (2026-08-06) · **Complexity**: Moderate
**Depends on**: J6 (the allowlist as a security bound), J30 (estate binding), J31 (sweep outcome),
J20 (config validation), J8 (guardrails), J19 (instruction/config fidelity)
**Amends**: none yet — this card exists to decide whether an amendment is warranted
**Source**: split out of [J31](../J31-allowlist-sweep-outcome/README.md) during its Round 3
conflict pass, 2026-08-06. Originally J31/ASO-3; removed there because it changes a shared
schema and J31's own out-of-scope section disclaimed exactly that blast radius.

## Essence

`triage.gitlab.allowed-projects` currently answers **two different questions** with one list:

1. **Authorization** — *may this project be called at all?* Enforced server-side in
   `TriageMateTools.searchCode` (J6/FND-38) and again in `RealGitLabGateway.requireAllowlisted`.
   This is a security bound against a model-supplied project string.
2. **Eligibility** — *is this project worth calling for this incident?* Used by
   `IncidentSignals.rankAllowlist` to build the deterministic engine's sweep candidates.

J30/GEB-1 deliberately put an entry in the list that is correct for (1) and wrong for (2): the
offline demo fixture `order-payments/payment-service` is a legitimate, allowlisted project that
the mock walkthrough cites — and a project that **cannot exist** in the real estate, so calling
it in real mode always 404s.

cheungp's field note said *update* `allowed-projects` to the real repository. J30 instead
*added* the real one and kept the fixture, with a stated reason (the offline demo must work on
stage with no network). Both are defensible readings of a list that has never distinguished its
two jobs.

## Why this is its own card

J31 could have carried this and initially did. It should not, because the blast radius is
categorically different from J31's:

| | J31 | J32 |
|---|---|---|
| Changes | control flow inside one engine loop | the **meaning of a config key** |
| Consumers affected | the deterministic engine | `TriageMateTools` validation, `RealGitLabGateway` enforcement, the ADK **instruction text** that tells the model what it may search (J19), `StartupBanner`'s GEB-4 check, `TriagePropertiesFixture`, the offline fixtures |
| Failure if wrong | a mis-reported search outcome | a **weakened security bound**, or a model told something untrue about its own limits |

A marker like `demo-only: true` on an entry is not a local edit — every consumer above has to
be told whether the marker affects *its* question, and a consumer that reads the list for
authorization must be unaffected by a flag that only means "don't rank this".

## The fork

**Option A — leave it.** The phantom costs at most one wasted call, and **not on every real
run**: the real project sorts first (stable sort, config order) and a hit short-circuits the
sweep, so the phantom is reached only when the real project returns no hits. Once
[J31](../J31-allowlist-sweep-outcome/README.md) lands, that call is skipped cleanly and named
in the report. Nothing is unsafe; it is untidy. Zero blast radius.

**Option B — separate the lists.** Eligibility becomes its own key (or a per-entry marker),
authorization keeps `allowed-projects` unchanged. Honest about the two questions; costs a
schema change across every consumer above, and creates a new way to misconfigure (a project
eligible but not authorized).

**Option C — mode-scoped eligibility.** Derive sweep candidates from the *effective GitLab
connector mode*, not the raw list: in real mode, skip entries known to be demo fixtures.

> **A trap worth recording**: the obvious form of C — "scope it to the mock Spring profile" —
> **does not work**, and J31's first draft proposed it. `application.yml` documents that the
> `real` profile flips all four connectors and that partial mixes are configured *without a
> profile* (`triage.connectors.gitlab=real`). A profile-only test leaves the phantom active in
> exactly the partial-real setup a developer is most likely to run. Any C variant must read
> `triage.connectors.gitlab`, not the profile.

## What must be true before this is decided

- **Does anything besides the deterministic sweep read the list for eligibility?** If the ADK
  path only ever authorizes (the model proposes a project, we accept or reject), then the two
  questions are cleanly separable and B/C are cheaper than they look.
- **What is the ADK instruction told?** J19 owns instruction/config fidelity; if the instruction
  enumerates the allowlist to the model, a demo entry is being advertised to a real-mode agent
  as a searchable project, which is a fidelity bug independent of the sweep.
- **Does GEB-4's banner check need to change?** It currently warns only when the demo project is
  the *sole* real-mode entry. Under B or C, "eligible entries is empty in real mode" becomes the
  more precise condition.

## Out of scope

- **The sweep's control flow and outcome reporting** — [J31](../J31-allowlist-sweep-outcome/README.md).
- **Whether the allowlist is enforced at all** — J6/J8/FND-38 settled that; it is, in two places.
- **Adding or removing estate entries** — J30/GEB-1.
