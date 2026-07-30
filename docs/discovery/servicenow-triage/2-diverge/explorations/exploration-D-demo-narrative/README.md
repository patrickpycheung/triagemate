# Exploration D: Demo Narrative & MVP Scope

**Biases**: User-centric 👤 + Minimum-Viable ⚡ + Demo-lens 🎬

**Research Question**: What is the smallest end-to-end slice that produces a convincing 3-minute demo, and what's the demo script?

## The Exploration

This exploration focuses on **demo theater**: what happens on stage, what the audience *sees* and *believes*, and what's actually running live vs staged/pre-seeded to guarantee a flawless narrative.

For a hackathon demo to land, the "wow beat" must be crystal clear: judges must see (a) AI autonomy in action, (b) evidence that multiple sources were actually consulted, and (c) the signature technique (log-to-code correlation) visibly working. Everything else is supporting color.

## Key Findings (Bullets)

**The Wow Beat** 🎯  
The payoff is a **work-note appearing on a ServiceNow ticket** with:
- A **quoted log line** from Sumo Logic (proof source 1)
- A **GitLab file/line reference** (proof source 2)
- A **visible correlation reason** ("matched log template at line 847")
- **Confidence and next-step suggestion** (grounds the hypothesis)

This happens **autonomously after ticket creation**, in ~30–60 seconds. That's the moment judges believe.

**MVP Scope: What Must Be Live** ✅  
1. **Ticket creation** (or pre-created ticket shown) — demonstrates trigger
2. **Agent runs** (real inference, no mocking) — demonstrates autonomy
3. **Work-note posted to ServiceNow** — demonstrates integration
4. **Log-to-code correlation visible** (real match shown in note) — demonstrates signature technique

**MVP Scope: What Can Be Pre-Seeded** 🎭  
1. **GitLab project + seeded bug** — a simple demo repo with a prepared error (e.g., an exception handler that logs a specific error message)
2. **Sumo Logic logs** — pre-indexed failure logs in a known window for this bug; agent queries a small, focused time range
3. **Confluence docs** — optional; one page is enough if included, but not critical for wow
4. **Code snippet in agent context** — can pull full master at demo time, but safe to pre-fetch and cache

**What's Skipped (Demo Cut)** ✂️  
- Multi-project ranking logic (only demo one project)
- Confluence deep-dive (one citation is sufficient)
- Complex log parsing (focus on one failure pattern)
- Security/access control hardening (acceptable for prototype)

**The Wow Beats the Skepticism** 💪  
Judges often think "the AI just hallucinated this." Grounding every claim in **actual log lines + actual code line numbers + actual timestamps** makes it unfakeable. A single well-executed example > 10 vague claims.

## Demo Day Setup (Physical/Technical)

- **Laptop on stage** with browser showing ServiceNow, GitLab, Sumo Logic (tabs open, pre-authenticated)
- **Terminal visible** showing agent invocation (optional; helps transparency)
- **Network**: stable WiFi; fallback: phone hotspot for resilience
- **Timing**: 30–60 sec for agent to run; script paced so the audience watches the ticket, then the work-note appears

## Success Metrics (for Demo Day)

1. ✅ **Ticket created** → work-note appears in <90 seconds
2. ✅ **Work-note contains real log line** (quoted verbatim from Sumo)
3. ✅ **Work-note references real code** (GitLab file + line number)
4. ✅ **Correlation reason is visible** (not a black-box guess)
5. ✅ **Judges believe** (post-demo: "would save us real time")

## File Structure

```
exploration-D-demo-narrative/
├── README.md (this file)
├── exploration.md (detailed storyboard, sample output, risk mitigations)
└── findings.md (structured findings, trust signals, risks + fallbacks)
```

## Next Steps (for Implementation)

1. **Spike 1**: Build demo GitLab repo with seeded bug + Sumo logs pre-indexed
2. **Spike 2**: Test agent end-to-end with live ticket creation
3. **Spike 3**: Script the demo narrative and timing (rehearse)
4. **Spike 4**: Network resilience + fallbacks (pre-cached logs, mock API responses)
