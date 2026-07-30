# Log-to-Code Correlation Engine: Exploration B

## Problem Statement

Auto-triage of ServiceNow tickets requires linking runtime logs (from Sumo Logic) to the source code locations that emitted them. Given a log line like:

```
2026-07-22T14:32:10Z ERROR Order 12345 failed to charge: insufficient funds
```

The engine must locate the exact logging statement in the GitLab repository (e.g., `payment_service.py:87`) that produced this line, then trace the execution context to determine root cause.

## Why This Matters

- **Localization**: Pinpoints the exact file/function/line responsible for the failure
- **Context**: Retrieves surrounding code to reason about the failure
- **Demo Impact**: Highlighting matched log lines next to their source locations is visually compelling

## The Spectrum of Approaches

This exploration evaluates four primary strategies plus hybrids:

1. **Naive Substring Matching** - grep-based; fast but brittle
2. **Log Template Mining** - reverse-parse logs into templates (Drain-style), match against code patterns
3. **Static Code Analysis** - extract logging statements from AST, pre-compute templates
4. **LLM-Driven Correlation** - hand LLM the log window + candidate code files
5. **Hybrids** - combine fast filtering (grep/templates) with LLM-based reasoning

## Recommended Approach

**Hybrid: AST-extracted templates + Sumo-based filtering + LLM reasoning**

- **Phase 1 (offline)**: Parse source code with language-specific AST tools to extract all logging statements and their templates
- **Phase 2 (at triage time)**: Use Sumo Logic's existing log filtering + fuzzy matching to shortlist candidate files
- **Phase 3 (LLM-driven)**: Feed LLM the log excerpt + shortlisted code sections to perform semantic correlation and root-cause reasoning

This is **buildable in hackathon time** (2-3 days), **demo-friendly** (shows matched lines + highlighted code), and **robust** (avoids brittle regex, uses semantic reasoning).

## Read Next

- **exploration.md** - Detailed algorithm sketches and build effort analysis
- **findings.md** - Confidence ratings and risk assessment per approach
