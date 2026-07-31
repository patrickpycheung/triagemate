# Architecture Review: Spring Boot Triage Copilot

## Judgement Questions

**1. Is mapping bare `IllegalStateException` to 404 sound?**
No. It is brittle and risks swallowing internal bugs. `RealServiceNowGateway` currently throws `IllegalStateException` for both an "incident not found" condition and a "failed to serialize work note text" condition. Even though the serialization failure is caught by the `DiagnosisOrchestrator` before reaching the API layer, relying on a generic core exception to signal a domain-specific 404 HTTP status is a severe design gap. 

**2. Two separate startup WARNs now cover overlapping misconfiguration — coherent?**
No. Spreading string-matching configuration validation (`configuredEngine`) across the constructors of infrastructure components (`DiagnosisOrchestrator` and `IncidentPoller`) is incoherent and violates separation of concerns.

**3. Is a gateway constructor the right layer for write-field validation?**
No. Throwing an `IllegalArgumentException` in the gateway constructor mixes infrastructure setup with configuration validation. This check should occur during configuration binding so the application fails cleanly before bean instantiation.

## Identified Issues

**[SEVERITY HIGH] debt: Generic Exception mapped to HTTP 404**
- **file:line**: `src/main/java/com/company/triage/api/DiagnosisApiExceptionHandler.java:28` (and `RealServiceNowGateway.java:82`)
- **Gap**: `IllegalStateException` is mapped to 404, but the same exception is thrown for JSON serialization failures, meaning internal bugs could falsely surface as "Not Found" if not carefully caught.
- **Fix**: Introduce and throw a domain-specific `IncidentNotFoundException` instead of `IllegalStateException`.

**[SEVERITY MEDIUM] gap: `index.html` JSON parsing precedes OK check**
- **file:line**: `src/main/resources/static/index.html:53`
- **Gap**: `await res.json()` is called before `res.ok` is checked. If a proxy or Spring returns a non-200 HTML error page (e.g., 502/504), parsing will throw a `SyntaxError`, obscuring the actual HTTP status and message.
- **Fix**: Check `!res.ok` first, or verify `res.headers.get('content-type')?.includes('application/json')` before parsing.

**[SEVERITY LOW] debt: Scattered Configuration Validation**
- **file:line**: `src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java:95`
- **Gap**: `triage.engine` state checks and warnings are scattered across component constructors rather than centralized.
- **Fix**: Centralize validation into a dedicated `@ConfigurationProperties` validator or `ApplicationRunner` bean.

**[SEVERITY LOW] debt: Write-field config validation in infrastructure component**
- **file:line**: `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:64`
- **Gap**: Validating the `writeField` config value inside the gateway constructor is the wrong layer.
- **Fix**: Move validation to a `@ConfigurationProperties` class using Bean Validation API (`@Pattern(regexp = "work_notes|comments")`).

**[SEVERITY LOW] debt: JSON string concatenation**
- **file:line**: `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:149`
- **Gap**: While Jackson is used to escape the string, the final JSON payload is still constructed via manual string concatenation.
- **Fix**: Pass `Map.of(writeField, workNote)` directly to `.body()` and let `RestClient` handle the full serialization.
