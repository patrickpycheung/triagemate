# Phase 3: Patterns Across Explorations

> **⏸️ ROUND-1 SYNTHESIS — superseded within this same DDS.** P1 ("a standalone
> orchestrator is unavoidable") and P2 ("correlation is largely DETERMINISTIC") were
> reconciled from Explorations A–D **before** Round 2 added Exploration E (Rovo-native).
> Round 2 inverted both, and `4-decide/concepts-extracted.md` extracted the **Rovo-native,
> LLM-first** concepts RC1/RC3 instead — see `3-synthesize/README.md` for the spiral.
> Then the 2026-07-23 pivot (`PIVOT.md`) superseded the Rovo answer in turn, landing on a
> local Spring Boot + ADK app. **Read this file as the Round-1 record, not as guidance.**

Reconciled from Explorations A–D + Gemini research + verification spike. Overlap = HIGH confidence.

## P1 — A standalone orchestrator is unavoidable (HIGH — A, C, Gemini agree)
ServiceNow cannot trigger a Rovo agent directly, and Rovo agents cannot be called by external systems. Every viable design therefore needs a **small standalone service** to receive the "new ticket" event and drive the workflow. This collapses much of the "Rovo vs standalone" debate: you're building a standalone component regardless. Trust: 🔬/📚.

## P2 — The correlation engine (the WOW) is largely DETERMINISTIC (HIGH — B, reinforced by D)
Localizing the failing code does NOT require the LLM. The reliable signal chain is:
1. **Stack-trace parse** (if the log carries file:line) → ~0.95 confidence.
2. **AST log-template index**: parse the repo, extract every logging call, normalize `"Order {id} failed"` → template.
3. **Drain3 template mining** on the Sumo lines → fuzzy-match to code templates → ~0.8.
4. **grep fallback** for literal substrings.
The LLM is only a **tie-breaker + narrative writer** on the top-N candidates. → The demo's core is robust to the LLM-platform uncertainty (P5).

## P3 — Demo safety = pre-seed the flaky sources, keep the impressive parts live (HIGH — C, D agree)
- **LIVE**: ServiceNow trigger, GitLab master fetch, work-note post-back (these are the visible "autonomy" beats and are reliable/low-rate-limit).
- **MOCK/CACHE**: Sumo Logic (4 req/s rate limit + egress risk → pre-seed a JSON fixture from a real query) and Confluence (cache pages at startup).
- Judges accept staged fixtures; real evidence (clickable GitLab line, real log string) keeps it credible.

## P4 — ServiceNow trigger = Business Rule / Flow → Outbound REST; polling as fallback (HIGH — A, C, Gemini)
No native outbound webhooks. Business Rule + Outbound REST Message is the most broadly available (no plugin dependency); Flow Designer is cleaner if IntegrationHub is installed. Table-API polling every ~30s is the zero-admin fallback and is perfectly fine for a demo.

## P5 — The LLM engine should be PLUGGABLE, not load-bearing (HIGH synthesis)
Because (a) the correlation is deterministic and (b) Copilot-headless-auth is uncertain, treat the LLM as a swappable reasoning/summarization layer behind one interface. Primary candidate: GitHub Copilot SDK headless (if that's the approved "Copilot"). The design must not die if that auth doesn't land in time.

## P6 — The wow beat is a single autonomous artifact (HIGH — D)
~60s after ticket creation, a work-note appears naming the responsible **file + line**, quoting the **exact matched log line**, with a confidence score and a clickable GitLab link. That one beat is the whole pitch.
