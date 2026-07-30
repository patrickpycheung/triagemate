# Exploration C: Integration Plumbing for Auto-Triage

## Overview

This exploration maps the concrete trigger and integration pathway for a ServiceNow auto-triage agent. The agent must: listen for new tickets, fetch context (code, logs, docs), analyze, and post back a triage comment—all in real time with <5 min latency for demo.

**Scope:** Five system integrations (ServiceNow trigger, GitLab fetch, Sumo Logic search, Confluence docs, ServiceNow post-back) analyzed for simplicity, reliability, and demo-day risk.

## Why This Matters

A live demo lives or dies on integration reliability. Each system has a different failure surface (auth, rate limits, network egress, API versioning). Choosing the wrong trigger mechanism or trying to make all systems live could blow the demo if one auth token expires or a rate limit is hit.

## Key Findings

| System | Simplest Approach | Auth Risk | Rate Limit Risk | Live/Mock Recommendation |
|--------|---|---|---|---|
| **ServiceNow Trigger** | Flow Designer with REST action (not Business Rule) | Low (platform token) | Low | LIVE—no external dependency |
| **GitLab Fetch** | Repository Files API + PAT | Medium (PAT expiry) | Medium (size-based limits) | LIVE—small files only |
| **Sumo Logic Search** | Async Search Job API + access ID/key | Medium (key rotation) | **HIGH** (4 req/s limit) | MOCK + seeded results |
| **Confluence Fetch** | CQL search + API token | Medium (token expiry) | Low | LIVE—cached results |
| **ServiceNow Post-back** | Table API PATCH to incident + work_notes | Low (same auth) | Low | LIVE—single write |

## What You'll Learn Here

- **Trigger choice:** Flow Designer (not Business Rule) for demo simplicity and better debugging
- **Call sequences:** Minimal step-by-step for each system
- **Demo strategy:** What to keep live vs. pre-seed with mock data
- **Failure modes:** Top risk for each—and how to mitigate for demo day

## Document Structure

- **exploration.md** — detailed analysis with minimal call sequences per system
- **findings.md** — trust levels, risk assessment, demo recommendations
