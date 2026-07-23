# Log-to-Code Correlation: Detailed Exploration

## Approach 1: Naive Substring Matching (Grep-Based)

**Algorithm:**
1. Extract first 3-5 "meaningful" tokens from log message (skip timestamps, severity)
2. Combine into a search query (e.g., "Order failed charge")
3. Run `ripgrep` across repo for that substring
4. Return matched file/line

**Example:**
```
Log: "ERROR Order 12345 failed to charge: insufficient funds"
Query: "Order failed charge"
Match: payment_service.py:87  logger.error(f"Order {id} failed to charge: {reason}")
```

**Build Effort:** 2–4 hours. Simple string extraction + ripgrep wrapper.

**Robustness:**
- ❌ Fails when log message is reformatted (template variables replaced)
- ❌ Multiple hits for generic keywords ("failed", "error")
- ✅ Works when logging statement is literal in code

**Demo Impact:** Medium. Shows matched file/line but no confidence or context.

---

## Approach 2: Log Template Mining (Drain-Like)

**Idea:** Reverse the logging process. Recover the *template* from raw logs, then match templates to code.

**Algorithm:**
1. **Collect sample logs** from Sumo (last N hours for the ticket's time window)
2. **Apply Drain3 algorithm** to group similar logs and extract templates:
   - Build a prefix tree indexed by token count and first tokens
   - Cluster logs with high token similarity (>80%) into templates
   - Replace variable parts with placeholders: `Order <*> failed to charge: <*>`
3. **Extract code templates** (offline, pre-computed):
   - Parse repo with language-specific AST tool (ast.parse for Python, etc.)
   - Find all `logger.*/print(...)` calls
   - Extract format strings: `f"Order {id} failed to charge: {reason}"`
   - Normalize: `Order <*> failed to charge: <*>`
4. **Match** template from Sumo logs to code templates
5. **Return** matched file/line + extracted parameters

**Example:**
```
Sumo logs:
  "Order 12345 failed to charge: insufficient funds"
  "Order 67890 failed to charge: card declined"
  
Extracted template: "Order <*> failed to charge: <*>"

Code AST scan finds:
  payment_service.py:87  logger.error(f"Order {id} failed to charge: {reason}")
  payment_service.py:92  logger.warning(f"Order {id} failed to charge: {reason}")

Match → payment_service.py:87 (error level matches Sumo ERROR)
```

**Build Effort:** 1–2 days.
- Drain3: pip install (mature library)
- AST extraction: ~200 LOC per language (Python/Java/Go)
- Matching: fuzzy/Levenshtein similarity + variable counts

**Robustness:**
- ✅ Handles parameter substitution (variable parts)
- ✅ Groups related errors automatically
- ⚠️ Requires enough sample logs (Drain needs ≥5–10 examples to infer template)
- ❌ Fails on dynamically constructed log messages (e.g., `logger.error(str(obj))`)
- ❌ Ambiguous when multiple logging statements share the same template

**Demo Impact:** Good. Shows template + matched code + extracted parameters.

---

## Approach 3: Static Code Analysis + LLM

**Idea:** Extract all logging statements from source upfront (offline), build a searchable index, then use LLM to perform semantic matching at triage time.

**Algorithm:**
1. **Offline indexing** (one-time, on repo clone):
   - Parse repo with language AST tools (ast.parse for Python, etc.)
   - Extract all logging calls: line number, file, log level, format string
   - Generate embeddings (OpenAI/local) for each logging statement
   - Store index: `{"file": "payment_service.py", "line": 87, "level": "error", "msg": "Order {id} failed to charge: {reason}", "embedding": [...]}`

2. **At triage time**:
   - Extract log message from Sumo
   - Generate embedding for the Sumo log line
   - Nearest-neighbor search in code embedding index → top-3 candidates
   - Optionally filter by log level match (ERROR in code ↔ ERROR in log)

3. **LLM reasoning** (optional, for tie-breaking):
   - Feed LLM: log excerpt + top 3 candidate logging statements + surrounding code
   - Ask: "Which logging statement emitted this log? Why?"
   - Return LLM's choice + reasoning

**Example:**
```
Sumo log (embedded): "Order 12345 failed to charge: insufficient funds"

Index search:
  [1] payment_service.py:87  "Order {id} failed to charge: {reason}"  (similarity: 0.92)
  [2] order_handler.py:42    "Payment failed for order {id}"          (similarity: 0.81)
  [3] logger_debug.py:15     "Error: {error}"                         (similarity: 0.68)

Match → payment_service.py:87
```

**Build Effort:** 1.5–2 days.
- AST extraction: ~200 LOC per language
- Embedding generation: pip install + OpenAI API (or use local model)
- Nearest-neighbor: use faiss or annoy library
- LLM reasoning (optional): hand-off to Copilot/Rovo API

**Robustness:**
- ✅ Handles parameter variation naturally (embeddings are semantic)
- ✅ Tolerates reordering, rephrasing
- ✅ No dependency on log sample size
- ⚠️ Embedding quality depends on model; local models may underperform
- ❌ Slower at inference time (embedding + search) than template matching

**Demo Impact:** Excellent. Shows semantic similarity scores + LLM reasoning + highlighted code.

---

## Approach 4: Stack Trace Short-Circuit

**Idea:** If logs include stack traces, extract file/line directly; otherwise fall back to other methods.

**Algorithm:**
1. **Parse stack traces** from Sumo logs:
   - Regex: `at file.py:123` or `File "payment_service.py", line 87, in <function>`
   - Extract file + line number

2. **Map to repo**:
   - If file exists in GitLab at that line, return immediately
   - Otherwise, fuzzy match filename to latest version in repo (in case line numbers drifted)

3. **Fallback:** If no stack trace, use Approach 2 or 3

**Example:**
```
Sumo log:
  ERROR Order 12345 failed to charge...
  Traceback (most recent call last):
    File "payment_service.py", line 87, in charge_order
      result = stripe_api.charge(...)

Extract: file="payment_service.py", line=87
Lookup repo: find payment_service.py:87 → charge_order() function
Match → payment_service.py:87 ✓
```

**Build Effort:** 4–6 hours.

**Robustness:**
- ✅ Exact when available (most reliable signal)
- ✅ Fast (direct lookup)
- ❌ Not all logs include stack traces
- ⚠️ Line numbers can drift between releases (use fuzzy filename matching + local context)

---

## Recommended Hybrid: "Template + LLM"

**Why this over pure approaches:**
- Grep alone is too brittle for production logs
- Pure Drain needs representative sample, slower to get accurate
- Pure embedding is slower at inference, higher latency
- Stack traces are ideal but not always present

**Suggested algorithm:**

```
TRIAGE_LOG(sumo_log, repo_master):
  
  # Phase 0: Try stack trace if present
  if stack_trace_in(sumo_log):
    file, line = parse_stack_trace(sumo_log)
    if file_line_exists_in(repo_master, file, line):
      return MATCH(file, line, confidence=0.95)
  
  # Phase 1: Fast filtering with templates
  templates = EXTRACT_LOGGING_TEMPLATES(repo_master)  # offline
  sumo_templates = DRAIN3_PARSE(sumo_log_batch)       # includes context
  
  candidates = MATCH_TEMPLATES(sumo_templates, templates)
  
  if len(candidates) == 1:
    return MATCH(candidates[0], confidence=0.85)
  
  if len(candidates) == 0:
    candidates = TOP_K_BY_SUBSTRING(sumo_log, repo, k=5)
  
  # Phase 2: LLM reasoning for disambiguation
  if len(candidates) > 1:
    logloc = LLM_REASON(
      log_excerpt=sumo_log,
      candidates=candidates,
      repo_context=FETCH_CODE_CONTEXT(candidates)
    )
    return MATCH(logloc, confidence=0.80, reasoning=logloc.reasoning)
  
  # Phase 3: Fallback to embeddings
  matches_by_embedding = EMBEDDING_SEARCH(sumo_log, templates)
  return MATCH(matches_by_embedding[0], confidence=0.70)
```

**Execution at hackathon:**

**Day 1:**
- Set up Sumo Logic log fetcher for a ticket's time window
- Parse Java/Python logging statements with AST (Approach 3: indexing phase)
- Implement naive substring matching for fallback (4 hours)

**Day 2:**
- Implement Drain3-based template matching (6 hours)
- Integrate with Copilot/Rovo API for LLM reasoning (4 hours)
- Test on 3-5 ServiceNow tickets with known root causes

**Day 3:**
- Demo: show matched logs highlighted next to source code
- Benchmark: measure latency, accuracy, false positive rate
- Stretch: add call-chain reconstruction (trace execution path via code analysis)

**Total build effort: 2–2.5 days**

---

## Risk Factors

| Factor | Mitigation |
|--------|-----------|
| **Log format variance** (structured vs. unstructured) | Use Drain3 which is format-agnostic; pre-process structured JSON logs |
| **Third-party library logs** | Fallback to substring matching; consider adding library symbol tables |
| **Line number drift** (deployed code ≠ repo master) | Fuzzy file matching + local context hash (first 10 tokens around line) |
| **LLM cost/latency** | Cache embeddings; use cheaper model (e.g., gpt-3.5-turbo) for non-critical reasoning |
| **No representative sample for Drain** | Combine Drain with static AST extraction as fallback |

---

## Comparison Table

| Approach | Build Time | Robustness | Demo | Latency | Notes |
|----------|-----------|-----------|------|---------|-------|
| Naive Grep | 2–4h | Low | Medium | <100ms | Brittle, baseline |
| Drain3 Template | 1–2d | Medium | Good | ~200ms | Needs sample logs |
| Static AST + Embedding | 1.5–2d | High | Excellent | 200–500ms | Higher quality, slower |
| Stack Trace (when present) | 4–6h | Very High | Excellent | <50ms | Limited applicability |
| **Hybrid (recommended)** | **2–2.5d** | **High** | **Excellent** | **100–300ms** | **Best trade-off** |

---

## Prior Art & Inspiration

- **Drain3** ([github.com/logpai/Drain3](https://github.com/logpai/Drain3)): Production-ready log template mining
- **Sentry code mappings** ([docs.sentry.io code mappings](https://docs.sentry.io/cli/code-mappings)): Automatic source linking for stack traces
- **LLM-based log parsing** ([arXiv:2406.06156](https://arxiv.org/html/2406.06156v1)): Newer research on LLM-driven correlation
- **Palantir static code analysis** for log extraction: Similar AST-based approach
