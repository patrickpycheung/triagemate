# GitLab API call failed after token extraction fix

**Date:** 2026-08-05
**Flow:** Deterministic (`run-deterministic-real.sh`)
**Related to:** `docs/gitlab-skipped-issue.md`

---

## (1) Issue

If we apply the fix from `gitlab-skipped-issue.md` (parsing level from `_raw`), the
GitLab API would be called — but the diagnosis run crashes with a 500 error. Two separate
faults are observed:

**Fault A — wrong search term (`errorToken=GNAF_FRONTAGE`)**
```
step 6 · SUMO · ... → 20 line(s); errorToken=GNAF_FRONTAGE
[GitLab] searching order-payments/payment-service for code matching "GNAF_FRONTAGE"
```
`GNAF_FRONTAGE` is a location data constant from the SQL constraint detail row in the
exception message. It is not a code identifier — searching GitLab for it will return zero
hits for the wrong reasons.

**Fault B — GitLab API 404 crashes the diagnosis run**
```
404 Not Found: "{"message":"404 Project Not Found"}"
at com.company.triage.gateway.real.RealGitLabGateway.searchCode(RealGitLabGateway.java:68)
at com.company.triage.orchestration.DeterministicDiagnosisEngine.diagnose(...)
```
The project `order-payments/payment-service` does not exist in the real GitLab instance.
The exception propagates uncaught all the way to the servlet, returning a 500 to the UI.

---

## (2) Cause

### Fault A — SCREAMING_SNAKE_CASE pattern hits data value before exception class

The `ERROR_TOKEN` pattern (`\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b`) selects the **first**
SCREAMING_SNAKE_CASE match in the full log message. For this exception:

```
org.springframework.dao.DataIntegrityViolationException: could not execute statement
[ERROR: null value in column "facility_id" ... Detail: Failing row contains (... GNAF_FRONTAGE, null)
```

- `DataIntegrityViolationException` (position 61) — a real code identifier — is CamelCase
  and is not matched by `ERROR_TOKEN` at all.
- `GNAF_FRONTAGE` (position 488) — a geographic classification constant from the failing
  DB row's data — is the only SCREAMING_SNAKE_CASE match and is selected as the token.

The exception class fallback added in the prior fix only fires when `errorToken == null`.
Since `GNAF_FRONTAGE` is non-null, the fallback never fires, and the wrong term is used.

**Root cause:** the exception class pattern should take **priority** over the
SCREAMING_SNAKE_CASE pattern (not be a fallback), because an exception class name is a
reliable reference to code in the project, while an arbitrary SCREAMING_SNAKE_CASE string
can be — and here is — a piece of runtime data embedded in the error message.

### Fault B — Two separate problems

**B1 — `application.yml` allowlist contains only the mock project**

The `triage.gitlab.allowed-projects` list in `application.yml` contains only:
```yaml
allowed-projects:
  - order-payments/payment-service
```
This is the seeded demo project for the mock/offline flow. It does not exist in the real
GitLab instance. The real project for the delivery-hazards application needs to be added.
No matter what token is extracted, any search against this project on real GitLab will 404.

**B2 — `RealGitLabGateway.searchCode` does not handle HTTP errors**

`RealGitLabGateway.searchCode` calls the GitLab API with no error handling:
```java
JsonNode hits = http.get()
        .uri(...)
        .retrieve().body(JsonNode.class);   // 4xx/5xx throws unchecked
```
A 404 (project not found, token rate-limited, project private, etc.) throws an unchecked
`HttpClientErrorException` that propagates through `DeterministicDiagnosisEngine.diagnose`
and is unhandled — crashing the entire diagnosis run with a 500 rather than degrading
gracefully.

Note: `recentCommitters` in the same class already uses a `try/catch(Exception)` with a
best-effort empty-list return. The same resilience was not applied to `searchCode`.

---

## (3) Suggested fix

### Fix A — Prefer exception class name over SCREAMING_SNAKE_CASE

In `DeterministicDiagnosisEngine`, swap the priority so that `EXCEPTION_CLASS` runs
**first** and `ERROR_TOKEN` is the fallback:

```java
// Try exception class name first — it is a reliable code identifier.
// SCREAMING_SNAKE_CASE is only tried as fallback, since it can match
// runtime data values embedded in the log message (e.g. GNAF_FRONTAGE).
String errorToken = firstMatch(EXCEPTION_CLASS, errorLine.message());
if (errorToken == null) {
    errorToken = firstMatch(ERROR_TOKEN, errorLine.message());
}
```

For the delivery-hazards incident this yields `DataIntegrityViolationException` (position 61
in the message), which is a genuine code identifier present in `HazardService.java`.

### Fix B1 — Add the real delivery-hazards GitLab project to the allowlist

In `application.yml`, add the actual GitLab project path for the delivery-hazards application
to `triage.gitlab.allowed-projects`. The correct path must be confirmed against the real
GitLab instance. Example:

```yaml
triage:
  gitlab:
    allowed-projects:
      - order-payments/payment-service        # demo/mock project
      - auspost/delivery-hazards              # real project — confirm path in GitLab
```

### Fix B2 — Make `RealGitLabGateway.searchCode` resilient to HTTP errors

Wrap the API call in a try/catch consistent with how `recentCommitters` already handles
errors in the same class:

```java
@Override
public List<CodeSearchResult> searchCode(String project, String searchTerm) {
    log.info("[GitLab] searching {} for code matching \"{}\"", project, searchTerm);
    try {
        JsonNode hits = http.get()
                .uri(uri -> uri.path("/api/v4/projects/{id}/search")
                        .queryParam("scope", "blobs")
                        .queryParam("search", searchTerm)
                        .build(project))
                .retrieve().body(JsonNode.class);
        List<CodeSearchResult> out = new ArrayList<>();
        if (hits != null) hits.forEach(h -> out.add(new CodeSearchResult(
                project,
                h.path("path").asText(),
                h.path("startline").asInt(0),
                h.path("data").asText(""))));
        return out;
    } catch (Exception e) {
        log.warn("[GitLab] searchCode failed for project '{}': {}", project, e.getMessage());
        return List.of();   // best-effort — degrade rather than crash the diagnosis run
    }
}
```

A failed code search should produce zero hits (and the trace will show "0 hit(s)"), not a
500 to the end user. The resilience pattern is already established in `recentCommitters` in
the same file.

### Fix order

- Fix A and Fix B2 are code changes.
- Fix B1 requires knowing the real GitLab project path — confirm with the delivery-hazards
  team before updating `application.yml`.
- Fix B2 should be applied regardless of Fix B1, as it protects against any future
  project misconfiguration.
