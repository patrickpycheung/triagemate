# Phase 4: DECIDE — Summary

**Status**: ✅ Concepts extracted. Pending: doc-test dds + final user checkpoint.

## Decision
Standalone Python Triage Agent, SN-ticket-triggered, deterministic log↔code correlation, pluggable LLM layer, autonomous work-note back. Rovo-native kept as alternative. (see decision.md)

## Concepts for CDS (see concepts-extracted.md)
- **C1** ServiceNow Trigger & Intake
- **C2** Source Connectors (GitLab live / Sumo mock / Confluence cached)
- **C3** Log↔Code Correlation Engine ⭐ (deterministic wow)
- **C4** LLM Reasoning Layer (pluggable; Copilot SDK candidate — carries the blocker spike)
- **C5** Work-Note Composer & Post-back
- **C6** Orchestrator / Runtime & Demo-Safety Policy
- **C7** (alt, not chosen) Rovo-native variant

## Mandatory day-1 spikes
- **S1 (BLOCKER)** corporate Copilot headless auth
- **S2** ServiceNow outbound-REST vs polling + work-note write permission
- **S3** representative Sumo query → demo fixture

## Next
1. `/doc-test dds` consistency check.
2. Phase 4 user validation checkpoint.
3. Handoff to CDS.
