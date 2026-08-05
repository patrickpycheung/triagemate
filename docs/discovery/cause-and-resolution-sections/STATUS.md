# STATUS — DDS: Cause & Resolution Sections

**Problem**: The triage report says *what* broke and *who* owns it, but never *why*
it broke or *how* to fix it. Add two sections: likely cause, likely resolution.

**Phase**: 4 — DECIDE ✅ complete; awaiting the operator's Phase 4 concept checkpoint
**Rigor**: Hackathon/RAPID (inherited from `docs/design-java/STATUS.md`)
**Feeds**: `docs/design-java/` as a new concept (provisionally **J26**), amending J4/J5/J7.

## Phase progress

| Phase | State | Output |
|-------|-------|--------|
| 1 — Elicit | ✅ Complete | `1-elicit/` |
| 2 — Diverge | ✅ Complete | 7 explorations + 1 spike |
| 3 — Synthesize | ✅ Complete | 7 patterns, 6 conflicts |
| 4 — Decide | ✅ Complete | **J26** + **J27** → `4-decide/concepts-extracted.md` |

## Verification

Structure matches all seven peer discovery workspaces; no missing READMEs; J26/J27 free
(highest existing concept is J25).

**Full `/doc-test dds` was not run** — skipped under the RAPID rigor condition, consistent
with the two Triple-Perspective skips in Phase 2/3. The structural checks it would perform
(README presence at every level, size limits, phase completeness, peer-workspace shape,
concept-ID collision) were run directly instead; results above.

**Accepted deviations**: four exploration READMEs run 505–688 words against the 500-word
guideline, and two `exploration.md` run 3074/3254 against 3000. The overage is citations
and verbatim example strings — the load-bearing content of the prior-art and user-centric
explorations. Trimming would remove evidence, not padding.

## Defects surfaced (filed to `/FOUND-ISSUES.md`, backlog 0 → 4)

FND-84 (fake "50% similar" on real tickets) · FND-85 (first-word matching) · FND-86
(non-UTF-8 byte breaks `grep`) · FND-87 (FND-67 self-poisoning unfixed on the ADK path).
Three of four are invisible to the mock profile — that clustering is itself the finding.

## Scope decision (ADM-2, agent-decided 2026-08-05)

Both sections go on the **J4 `DiagnosisReport`** and render to **both** the
ServiceNow diagnosis note and the UI.

**Why**: plainest reading of the request; the report is the spine and sections on it
render everywhere by design. J9's UI-only treatment exists for named-individual PII,
which does not transfer to cause/fix. `toDiagnosisNote()` already emits directive
advice (`recommendedNextAction`), so this is a difference of degree, not kind.

**Watch-item**: if explorations show ungrounded fix advice in a customer-retained
journal is the dominant risk, fall back to a J9-style UI-only gate or a config flag.
Surfaced at the Phase 4 checkpoint.

## 🔬 Spike result — the analogy is broken in production

`verification-similar-incidents/` (run automatically per the DDS auto-spike rule):

`RealServiceNowGateway.findSimilarIncidents` matches on **the first whitespace token of
the short description** (`firstKeyword` = `split("\\s+")[0]`) and stamps **every** result
with a hardcoded `similarity = 0.5`. The mock supplies realistic values (`0.91`), so the
demo looks excellent while the live path cites near-arbitrary tickets.

**Consequence**: "cite the most similar past incident" — the cheapest, most grounded,
LLM-free design — is not supported by real data today. Fixing `findSimilarIncidents`
becomes part of this concept, and until it is fixed the wording must not claim a
similarity the code cannot compute (and must never render `0.5` as "50% similar").

## The finding that shapes everything

`ResolvedIncident.resolutionCode` and `.resolutionNotes` are **already fetched by both
the mock and the real ServiceNow gateway** (`close_code` / `close_notes`,
`RealServiceNowGateway:135-140`) and **read by nothing** in main source. A grounded
cause/fix source is already in hand, unused. Trust: 🔬 Spiked (verified by grep).
