# Phase 1: ELICIT — Summary

**Status**: ✅ Complete (understanding checkpoint passed)

## Problem in one line
Auto-triage a new technical ServiceNow ticket by pulling GitLab `master` code, Sumo Logic logs, and Confluence docs, correlating log lines to code to localize the failure, and posting a root-cause hypothesis back as a ticket work-note.

## Nature
**Hackathon prototype** demonstrating agentic AI. Optimize for demo wow + ease of build. Proper system designed later if approved.

## The signature idea
Match runtime log lines (Sumo) ↔ logging statements in source (GitLab `master`) → reconstruct execution path → point at responsible code.

## Locked constraints
- AI engine ∈ {corporate **Copilot**, Atlassian **Rovo**} — both approved.
- In corporate network; sources = ServiceNow, GitLab, Sumo Logic, Confluence.
- Output = ServiceNow ticket comment/work-note.
- Ease-of-build wins; cost not a factor; security = prototype-level.

## Success axis
Demo wow: live end-to-end, visible use of all 4 sources, the log→code trick lands convincingly.

## Biggest unknowns → Phase 2 must resolve
1. Can corporate **Copilot** be called programmatically from a backend? Which Copilot (GitHub vs M365)?
2. Can **Rovo** agents reach external APIs (GitLab/Sumo/ServiceNow) and at what effort?
3. Cleanest **ServiceNow trigger** mechanism.
4. Most demo-able **log→code correlation** technique.

## Files
- problem-statement.md · constraints.md · success-criteria.md
