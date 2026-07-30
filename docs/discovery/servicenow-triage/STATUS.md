> # ⏸️ SUSPENDED — 2026-07-23 (Rovo-native track paused)
> This DDS explored and chose the **Rovo-native, AI-first** direction. Requirements
> changed to a **local Spring Boot + Google ADK** POC. This DDS is **paused, not
> deleted** — its problem statement, constraints, and RC1/RC3/RC5/RC6 findings are
> reused. A **follow-on DDS round** for the Java pivot lives in
> **`docs/discovery/servicenow-triage-java/`**. See **`PIVOT.md`** at repo root.

# STATUS — ServiceNow Technical Triage Assistant

**Current Phase**: ✅ DDS COMPLETE — concepts confirmed, ready for CDS handoff
**Trigger DECIDED**: chat-invoked demo (operator-confirmed); autonomous webhook = future vision, out of hackathon scope
**Started**: 2026-07-22

## One-line problem
Automatically triage a newly-created technical ServiceNow ticket by pulling code (GitLab master), logs (Sumo Logic), and docs (Confluence), correlating log lines to code, and posting a root-cause hypothesis (ideally pointing at the responsible code) back as a ticket work-note.

## Nature
Hackathon prototype. Optimize for demo wow + ease of build. Proper system via CDS later if approved.

## Phase Progress
- [x] Phase 1: ELICIT — problem, constraints, success criteria (understanding checkpoint passed)
- [x] Phase 2: DIVERGE — 4 explorations (A platform / B log→code / C plumbing / D demo) + Gemini research + verification spike
- [x] Phase 3: SYNTHESIZE — 6 patterns, 1 conflict resolved (Copilot headless = ⚠️ Uncertain)
- [x] Phase 4: DECIDE — decision + concepts-extracted (C1–C7)
- [x] doc-test dds consistency check — Phase 1 PASS (fixed G1: 2-diverge/README); conflict pass 6 findings (0 HIGH), fixed C4 Rovo-backend inconsistency + folded R1–R5 into concepts for CDS
- [x] Phase 4 user validation checkpoint PASSED (Round 2) → concepts confirmed, trigger locked → **ready for CDS**

## Chosen direction (Round 2 — after operator redirect)
**Rovo-native, AI-first**: one Rovo agent (Rovo Studio + Forge Actions), invoked in Rovo chat, reasons over ticket + GitLab master + Sumo + native Confluence to localize the failing code, posts a work-note back. Concepts RC1–RC6; standalone demoted to alternative RC7.

## Spiral log
- Round 1 → standalone + deterministic correlation. Operator redirected at Phase-4 checkpoint (reconsider platform / AI-first / "Rovo is the real AI").
- Round 2 → Rovo-native AI-first (Gemini research + Exploration E). Copilot-headless blocker removed (Rovo's own LLM). New top constraint: Rovo restricts autonomous writes → post-back via chat-confirm or Automation layer.

## Known deviations / notes
- Codex CLI unavailable this session (stream closed 1.4s) → external reads via Gemini; synthesis is Claude reconciliation. Acceptable per skill fallback.
- **Honest caveat for pitch**: demo autonomy = agent reasoning + actions, NOT literal auto-on-ticket-creation (presenter invokes in chat). Chat-invoked demo, webhook-triggered future.
