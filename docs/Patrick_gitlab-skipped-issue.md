# Issue: GitLab steps skipped in deterministic flow with real connectors

**Date:** 2026-08-05
**Flow:** Deterministic (`run-deterministic-real.sh`)
**Observed in:** Tool-call trace — replay section of the demo UI (localhost:8080)

---

## (1) Issue

When running the deterministic diagnosis engine against real connectors, both GitLab steps
are skipped for the `delivery-hazards` incident:

```
gitlab.searchCode → skipped (no error token in the log lines to search code for)
gitlab.recentCommitters → skipped (no code file was located to look up committers for)
```

This occurs even though the Sumo search returns 20 log lines (`errorToken=null`), meaning
the engine received data but found nothing usable to search GitLab with.

---

## (2) Cause

The skip is caused by two gates in the engine that must both succeed before a GitLab call
is made. Both gates fail for real `delivery-hazards` logs.

### Gate A — Level field empty in real Sumo responses (root cause)

`RealSumoGateway` maps the log level from the structured Sumo field `loglevel`:

```java
f.path("loglevel").asText("")   // → "" on real Sumo instances
```

The Sumo query `_sourceCategory=... ERROR` uses `ERROR` as a keyword filter on the raw
message text, so 20 lines are returned. However, `loglevel` is a *parsed* Sumo field —
if the Tomcat log source is not configured with a field extraction rule that populates it,
the field is blank on every returned row.

With `level == ""` for every row, the engine's filter:

```java
logs.stream().filter(l -> "ERROR".equals(l.level())).findFirst()
```

…matches nothing. `errorLine` is `null`, `errorToken` is `null`, and both GitLab steps
are skipped.

**Confirmed by the real log message.** The first log row returned by Sumo for
`_sourceCategory=IDT/ITServices/Tomcat/delivery-hazards/prod/AppEvt_delivery-hazards ERROR`
is a Spring Boot Tomcat line whose `ERROR` level exists only in `_raw`:

```
2026-08-05 14:26:46.173 ERROR 1 --- [http-nio-8080-exec-546] a.c.a.h.c.e.CustomRestExceptionHandler : ...
```

### Gate B — SCREAMING_SNAKE_CASE regex too narrow (secondary cause, cascades from Gate A)

Even after Gate A is fixed, the engine extracts an `errorToken` using:

```java
Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b")
```

This only matches tokens like `PAYMENT_RECONCILE_MISMATCH`. Applied to the real
`delivery-hazards` error message, the first match is `GNAF_FRONTAGE` — a location data
value from the SQL constraint violation detail row, not a meaningful code identifier.
The exception class `DataIntegrityViolationException` (which *is* searchable in GitLab)
is not matched at all.

---

## (3) Suggested fix

### Fix 1 — `RealSumoGateway`: parse level from `_raw` when `loglevel` is blank

Add a static helper `parseLevel(loglevel, raw)` that falls back to a regex scan of `_raw`
when the structured field is empty. Spring Boot's standard log format is well-defined:

```
YYYY-MM-DD HH:mm:ss.SSS LEVEL PID ---
```

Pattern: `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+ (ERROR|WARN|INFO|DEBUG|TRACE)\b`

Replace the `loglevel` field read in the message-parsing loop:

```java
// Before
f.path("loglevel").asText("")

// After
parseLevel(f.path("loglevel").asText(""), f.path("_raw").asText(""))
```

This is the critical fix — it unblocks `errorLine` from being found, which allows
`errorToken` to be extracted and GitLab to be called.

### Fix 2 — `DeterministicDiagnosisEngine`: add CamelCase exception class fallback

After the SCREAMING_SNAKE_CASE pattern runs, add a fallback pattern for CamelCase Java
exception/violation/error class names:

```java
Pattern.compile("\\b[A-Z][a-zA-Z0-9]*(Exception|Error|Violation|Failure)\\b")
```

Apply it only when the primary pattern either finds nothing, or finds a token that will not
be meaningful to search (the engine cannot distinguish `GNAF_FRONTAGE` from
`PAYMENT_RECONCILE_MISMATCH` without domain knowledge, so the simplest improvement is
to add the exception class as a second attempt):

```java
String errorToken = firstMatch(ERROR_TOKEN, errorLine.message());
if (errorToken == null) {
    errorToken = firstMatch(EXCEPTION_CLASS, errorLine.message());
}
```

For the `delivery-hazards` incident this yields `DataIntegrityViolationException` as the
search term, which is a real identifier present in the application's source code.

### Fix order

Fix 1 must be applied first — it is the root cause. Fix 2 improves the quality of the
GitLab search term once GitLab is actually being called. Both fixes together are needed for
the `delivery-hazards` incident to produce a meaningful log-to-code citation.
