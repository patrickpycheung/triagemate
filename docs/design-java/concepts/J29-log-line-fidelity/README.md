# J29 — Log-line fidelity (a real log line must yield a level and a searchable identifier)

**State**: 🔴 Designed, not built — HIGH · **Complexity**: Simple · **Priority**: HIGH
**Depends on**: J6 (knowledge tools / Real Sumo gateway), J3 (gateway contracts), J22 (real-gateway contract tests)
**Amends**: J6 (`LogEvidence.level` contract), J13 (the confidence tier that reads `level`)
**Source**: teammate field report — [`docs/Patrick_gitlab-skipped-issue.md`](../../../Patrick_gitlab-skipped-issue.md),
by **cheungp** (patrick.cheung@auspost.com.au), commit `6cc2f1c`, from a live
`run-deterministic-real.sh` against the real Sumo instance (delivery-hazards, 2026-08-05).
Every claim below re-verified against that instance on 2026-08-05 before this card was written.

## Essence

**A real Spring Boot log line must survive the parse with both of the things the engine needs
from it: its severity, and an identifier worth searching code for.** Today it survives with
neither. `RealSumoGateway` reads the severity from a structured Sumo field that this estate
does not populate, so every row arrives at level `""`; and the error-token regex matches only
`SCREAMING_SNAKE_CASE`, which on a real stack trace picks a *data value* out of a SQL detail
row rather than the exception class.

The visible symptom is that both GitLab steps skip and the demo's log-to-code citation — the
thing C3 calls "the wow" — never happens on real data.

## Why this is a concept, not a two-line regex fix

Because the blank level is **not confined to the GitLab gate**. It silently feeds a second
consumer that has nothing to do with GitLab, and fixing only the gate leaves that one wrong:

- [`DeterministicDiagnosisEngine.java:420`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L420)
  scores every log-derived candidate system `"ERROR".equals(l.level()) ? 0.70 : 0.45`. With
  `level` permanently `""` on real Sumo, **every** candidate falls to the 0.45 tier. The
  report the app posts onto a real ticket therefore understates its own confidence on every
  live run, and nothing in the trace says why. The field report does not mention this.

And because the two gates must move **together**, in order. Fix the level alone and GitLab is
called with `GNAF_FRONTAGE` — a street-address field name — as the search term, which is a
worse failure than skipping: it produces a confident, cited, wrong log-to-code link. Fix the
token alone and it is never reached. This is one decision about *what the engine is allowed to
believe a real log line told it*, hence a card.

There is also a repeat-offender pattern worth naming. `MockSumoGateway` populates `level`
correctly ([`MockSumoGateway.java:22-26`](../../../../src/main/java/com/company/triage/gateway/mock/MockSumoGateway.java#L22)),
so the mock demo has always looked right. This is the **fourth** finding of the same shape —
FND-47 (`u_environment`), FND-61 (journals), J24 (reference fields), now this — where a field
the mock populates is empty on the real instance and only a live run exposed it. J22 built
offline contract tests for this class, but for **request** shapes (Confluence and GitLab); no
gateway has an offline test pinning the **response** field mapping, which is the side that
fails here.

## Evidence — verified live, 2026-08-05

Query: `_sourceCategory=IDT/ITServices/Tomcat/delivery-hazards/prod/AppEvt_delivery-hazards ERROR`
(105 messages returned).

| # | What | Where | Failure |
|---|---|---|---|
| 1 | `loglevel` is **absent**, not merely blank | [`RealSumoGateway.java:94`](../../../../src/main/java/com/company/triage/gateway/real/RealSumoGateway.java#L94) | The API response carries no `loglevel` key at all on any row (`map.loglevel` → JSON absent → `asText("")` → `""`). The severity exists only inside `_raw`. **HIGH** |
| 2 | The ERROR filter therefore matches nothing | [`DeterministicDiagnosisEngine.java:263`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L263) | `errorLine` is `null` on every real run despite 105 matching rows; `errorToken` is `null`; both GitLab steps skip with "no error token" / "no code file was located". **HIGH** |
| 3 | Every log-derived candidate is under-scored | [`DeterministicDiagnosisEngine.java:420`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L420) | 0.45 instead of 0.70, on every live run, invisibly. **Not in the field report.** **MEDIUM** |
| 4 | The token regex picks a data value over the exception | [`DeterministicDiagnosisEngine.java:44`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L44) | On the real first-ERROR line, `\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b` returns `GNAF_FRONTAGE` (4 occurrences, all from the SQL `Detail: Failing row contains (…)` tail). `DataIntegrityViolationException` — present in the source, and the identifier a human would search — is not matched. **HIGH** |

The real line, abridged:

```
2026-08-05 14:26:46.173 ERROR 1 --- [http-nio-8080-exec-546] a.c.a.h.c.e.CustomRestExceptionHandler   : …
org.springframework.dao.DataIntegrityViolationException: could not execute statement
[ERROR: null value in column "facility_id" of relation "hazard" violates not-null constraint
  Detail: Failing row contains (4630355, unsafe_assets, null, 194117, HEAD INJURY …
```

## Correction to the field report

The report's proposed level pattern is

```
^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+ (ERROR|WARN|INFO|DEBUG|TRACE)\b
```

with a **single literal space** before the level group. Spring Boot's default console layout
right-aligns the level to five characters (`%5p`), so four-character levels are preceded by
**two** spaces. Measured against the live rows:

| level | single-space pattern | `\s+` pattern |
|---|---|---|
| `ERROR` (5 ch) | HIT | HIT |
| `WARN` (4 ch) | **MISS** | HIT |
| `INFO` (4 ch) | **MISS** | HIT |

The bug the report is fixing is about ERROR, so the defect would not have shown up in its own
testing — but it would have shipped a parser that silently cannot see `WARN` or `INFO`, which
is the same class of latent blindness this card exists to remove. **Use `\s+`.**

## Design

### LLF-1 — the level is parsed from `_raw` when the structured field is absent

`RealSumoGateway` gains a package-private static `parseLevel(String structured, String raw)`:
returns `structured` when non-blank; otherwise matches
`^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+\s+(ERROR|WARN|INFO|DEBUG|TRACE)\b` against `raw`
and returns group 1; otherwise `""`.

Static and package-private so it is unit-testable against captured real lines with no HTTP,
which is how LLF-4 pins it.

**The structured field keeps priority.** An estate that *does* configure a field-extraction
rule is better served by its own parsed value than by our regex, and this must not regress
such an estate to a guess.

### LLF-2 — `""` after both attempts means "unknown", and is never treated as a severity

A row whose level cannot be determined must not silently become a non-ERROR row for the
[`:420`](../../../../src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java#L420)
confidence tier, because "we could not read the severity" and "this line is INFO" are
different facts that currently produce the same 0.45. Unknown-level rows take the 0.45 tier
**and** the run discloses that the level was unreadable, the same way J24/SFF-2 makes a blank
CI say so rather than quietly degrading.

### LLF-3 — the error token falls back to a Java class name, and the fallback is ordered

Two patterns, with distinct jobs. Both are verified against the captured real line:

| role | pattern |
|---|---|
| detector — does this line carry a thrown FQN? | `\b\w+(?:\.\w+){2,}\.\w*Exception\b` |
| extractor — what is the class called? | `\b[A-Z][a-zA-Z0-9]*(Exception\|Error\|Violation\|Failure)\b` |

**Option (a) is the chosen one** (decided during implementation): prefer the exception class
when the line carries a stack-trace shape. Option (b) — search GitLab for both terms and keep
whichever returns a hit — was rejected: it adds a network round trip per candidate on a path
already budgeted at 120s, and it can still tie.

Ordering, therefore:

```java
String errorToken = firstMatch(ERROR_TOKEN, message);
if (EXCEPTION_FQN.matcher(message).find()) {           // a thrown class outranks a free-text token
    String cls = firstMatch(EXCEPTION_CLASS, message);
    if (cls != null) errorToken = cls;
} else if (errorToken == null) {                       // a line can name an exception with no FQN
    errorToken = firstMatch(EXCEPTION_CLASS, message);
}
```

**Why the obvious detector is wrong.** `\w+(\.\w+)+Exception` — the shape this card originally
proposed — matches `a.c.a.h.c.e.CustomRestException` on the real line: Spring Boot's
*abbreviated logger name*, truncated mid-word, naming a class that exists in no source file, so
a GitLab search for it returns nothing. The `{2,}\.\w*Exception\b` form and the trailing `\b`
are what make it read the thrown FQN (`org.springframework.dao.DataIntegrityViolationException`)
instead. The extractor is likewise safe to run over the whole line: it skips
`CustomRestExceptionHandler` because there is no word boundary after `Exception` there.

On the real line this yields `DataIntegrityViolationException`, not `GNAF_FRONTAGE` — which is
finding #4, closed. On a line with a genuine `SCREAMING_SNAKE` code and no FQN (the mock's
`PAYMENT_RECONCILE_MISMATCH`) the detector does not fire and `ERROR_TOKEN` still wins.

### LLF-4 — a captured real Sumo response pins the mapping

A fixture of the real `messages` payload (level absent, level in `_raw`, mixed ERROR/WARN
rows) drives an offline test asserting the mapped `level`. This is the J22 gap that let the
mock/real divergence live: without it, `MockSumoGateway`'s well-formed rows are the only thing
the suite ever sees.

## Verification

| Check | Passes when |
|---|---|
| `parseLevel` unit test over captured rows | `ERROR`, `WARN`, `INFO` all resolve; absent-and-unparseable → `""` |
| Live `run-deterministic-real.sh` on delivery-hazards | `errorLine` non-null; both GitLab steps run instead of skipping |
| Candidate confidence on a live run | log-derived candidates reach 0.70, not 0.45 |
| Chosen LLF-3 option on the real line | search term is `DataIntegrityViolationException`, not `GNAF_FRONTAGE` |
| `mvn test` | green, live Sumo tests included |

## Out of scope

- **Configuring a Sumo field-extraction rule** so `loglevel` is populated upstream. That is
  the cleaner fix and it is not ours to make — it is an estate change on someone else's
  tenant, and the app must work against the estate as found.
- **The ADK path's search terms.** The agent chooses its own GitLab query, so LLF-3 does not
  apply to it. LLF-1 does — it shares the gateway.
- **Rewriting `ERROR_TOKEN`.** Narrowing it to exclude data values needs domain knowledge the
  engine does not have; LLF-3 adds a second attempt rather than making the first one smarter.
