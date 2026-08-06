# J20 — Startup Truth & Validation

**State**: 🟢 **Built** — all six STV rules (2026-08-06) — STV-1 (banner reports effective engine + connector state by asking the orchestrator, and shouts when triage.engine=adk was requested but no ADK engine is wired), STV-3 (@NotNull/@NotEmpty on the three fields whose absence throws downstream) and STV-5 (mechanism tests, not annotation tests) landed. **STV-4 landed 2026-08-06** (null guard in rankAllowlist — a blast shield independent of the binding-time validation). **STV-2 and STV-6 landed 2026-08-06** — LLM readiness warns in the banner and hard-stops in run-adk.sh (replacing that script's contradictory "Continuing anyway", and firing before the proxy starts); the hostname probe is bounded to 250ms on a daemon thread. **All six rules built.** · **Complexity**: Moderate · **Priority**: MEDIUM ·
**Depends on**: J1, J2, J8 ·
**Amends**: J2 (engine construction gains a readiness check), J8 (extends the
`TriageProperties` "booted clean means valid" contract to the allowlists it owns) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 2 confirmed
findings + 4 unverified tail items

## Essence

**Boot is the only cheap place to be wrong.** Everything the app needs in order to do
what it says it is doing must be *checked* before it accepts a request, and the last
thing the console prints must describe **what is actually running**, not what was
configured. Today the console's final, framed, authoritative word is read straight from
config intent, and three classes of missing config survive boot to fail mid-run — one of
them mid-demo, in front of an audience.

This is FND-49/FND-36/FND-56 (config intent vs. actuality) and FND-57 (bind and validate
once, at startup) applied to the two places they were not: **the startup banner** and
**the config the banner cannot see**.

## Why this is a concept, not six bug fixes

The six findings look independent — a banner string, a missing `@NotNull`, an NPE, a
missing test, a slow DNS lookup. Fixed piecemeal they produce a *contradictory* startup
story, because each remedy pulls in a different direction on the same unstated question:
**what may the app boot without?**

- Fix the banner alone and it now reports the effective engine honestly — while the LLM
  config that makes that engine *useful* is still unchecked, so `engine: adk` (truthfully
  active) still degrades on the first run.
- Fix the LLM check alone, in the obvious way (throw from the constructor), and you
  contradict the FND-49 precedent recorded five lines from that code — `DiagnosisOrchestrator.java:138-143`
  explicitly chose "log the mismatch loudly at startup; do NOT fail fast (a hackathon
  build shouldn't refuse to boot over this)" — and you break two existing `-Padk`
  construction sites.
- Add `@NotNull` to the allowlists alone and `IncidentSignals.rankAllowlist` keeps its
  latent NPE for every direct caller, including its own unit test.
- Add the null guards alone and the config error becomes a *silently empty* allowlist —
  which is the failure this card exists to close, wearing a different hat.

The decision that has to be made once, and then applied consistently to all six, is a
three-line policy: **refuse to boot on config that is wrong; warn loudly and say so in
the banner on config that is merely absent; never let either become a mid-run
exception.** STV-1…STV-6 below are that policy applied.

## Evidence — what the review found

| # | What | Where | Severity | Failure |
|---|---|---|---|---|
| 1 | Banner prints **configured** engine, not the active one | [`StartupBanner.java:49`](../../../../src/main/java/com/company/triage/config/StartupBanner.java#L49) | MEDIUM (confirmed) | `mvn spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk` **without** `-Padk`: the FND-49 WARN fires during context refresh and scrolls away; the banner — deliberately the last thing printed, on `ApplicationReadyEvent` — closes with `engine: adk`. The presenter narrates a live model over a scripted run. That is FND-8, reached through the banner. |
| 2 | LLM config validated at first diagnosis, not at startup | [`AdkModelFactory.java:27-34`](../../../../src/main/adk/java/com/company/triage/agent/AdkModelFactory.java#L27) called only from [`AdkDiagnosisEngine.java:279`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L279) | MEDIUM (confirmed) | `secrets.properties` recreated with `api-key` but no `model`: the app boots clean, the banner says ADK, and the failure arrives as `DEGRADED_TO_DETERMINISTIC` on the first live run (`DiagnosisOrchestrator.java:307-322`). |
| 2b | `secrets()` caches an **empty** `Properties` forever when the file was absent on first read | [`AdkModelFactory.java:81-96`](../../../../src/main/adk/java/com/company/triage/agent/AdkModelFactory.java#L81) | MEDIUM (confirmed verbatim by the verifier) | Creating or fixing `secrets.properties` while the app is running can never take effect; the error message does not say a restart is required. `run-adk.sh` explicitly prints "Continuing anyway" when the file is missing, so this path is *rehearsed*. |
| 3 | `Sumo.sourceCategoryPattern` / `Sumo.allowedEnvironments` / `GitLab.allowedProjects` carry no `@NotNull` | [`TriageProperties.java:84-109`](../../../../src/main/java/com/company/triage/config/TriageProperties.java#L84) | LOW (**unverified** tail — one-look check) | A profile that keeps the parent block but omits the list binds `null`, passes `@Validated`, and NPEs at first tool call: `TriageMateTools.searchLogs` `allowedEnvs.contains(env)` (`TriageMateTools.java:125-127`) and `Sumo.sourceCategoryFor`'s `pattern.replace` (`TriageProperties.java:102`). |
| 4 | `rankAllowlist` NPEs on a null allowlist, at the worst moment | [`IncidentSignals.java:159`](../../../../src/main/java/com/company/triage/orchestration/IncidentSignals.java#L159) | LOW (**unverified** tail) | `new ArrayList<>(allowlist)` with no guard; reached from `DeterministicDiagnosisEngine.java:212` **only inside `if (errorToken != null)`** (line 206) — i.e. only on an incident whose logs actually produced an ERROR line, which is exactly the demo incident. The sibling Sumo list *is* null-guarded three lines from the GitLab assignment (`DeterministicDiagnosisEngine.java:71-73` vs `:77`), so the omission is an inconsistency, not a policy. |
| 5 | No test proves the FND-57 validation **mechanism** fires | [`RealServiceNowGatewayTest.java:58-68`](../../../../src/test/java/com/company/triage/gateway/real/RealServiceNowGatewayTest.java#L58) | LOW (**unverified** tail — *narrowed by our own read, see below*) | Nothing binds a bad `triage.*` value through Spring and asserts the context fails. |
| 6 | `resolves()` claims a no-network lookup but can hit DNS/mDNS | [`StartupBanner.java:87-95`](../../../../src/main/java/com/company/triage/config/StartupBanner.java#L87) | LOW (**unverified** tail) | `InetAddress.getByName` falls through to the system resolver precisely on the branch it exists to detect (name *not* in hosts). The configured name is `triagemate.auspost.local` (`application.yml:48`) — a `.local` name, routed to mDNS on macOS and many Linux resolvers, multi-second timeout — blocking the `ApplicationReadyEvent` listener on a VPN'd corp laptop, the exact machine class the demo targets. |

**Correction to finding 5 — the claim does not survive our read as written.** It states
"no `ApplicationContextRunner`/`@SpringBootTest` exercises `@Validated` rejection" (true:
grep for `ApplicationContextRunner`/`BindValidationException` across `src/test` and
`src/adk-test` returns nothing) *and* that "nothing binds a bad value and asserts
failure". The second half is too strong: `RealServiceNowGatewayTest#rejectsAnUnrecognisedWriteField`
(lines 58-68) builds a `jakarta.validation` validator by hand and asserts violations on a
bad `write-field`. What that test proves is that the **annotations** are right. What
nobody proves is that **Spring applies them** — delete `@Validated` from
`TriageProperties.java:40`, or drop `spring-boot-starter-validation`, and that test stays
green while the "fail before serving a single request" guarantee quietly stops existing.
The card designs for the real gap (wiring), not the stated one (annotations).

**Correction to finding 2 — "silent" is overstated.** The verifier's `correctedClaim`
narrows it: the degrade *is* announced (`log.warn` at `DiagnosisOrchestrator.java:307`,
plus the FND-8 UI banner). The defect is **when**, not whether: mid-demo instead of
backstage. The design below is sized to that, which is why it does not reach for a
hard boot failure.

## Design

### STV-1 — The banner reports EFFECTIVE state, and asks the component that already knows

**Rule**: no line in the startup banner may be sourced from `Environment.getProperty`
when a bean in the running context can answer the same question about what is *actually*
wired.

**Mechanism**: `StartupBanner` injects `ObjectProvider<DiagnosisOrchestrator>` and calls
the existing `isAdkActuallyActive()` (`DiagnosisOrchestrator.java:154`, added for FND-56
and already used by `IncidentPoller`). The engine line becomes:

- `props.engine() == DETERMINISTIC` → `engine:     deterministic`
- `ADK` and active → `engine:     adk (live agent)`
- `ADK` and **not** active → `engine:     adk — NOT ACTIVE (no ADK bean: build with -Padk); running DETERMINISTIC`

`ObjectProvider` rather than a hard dependency: the banner already tolerates a
non-servlet context (`StartupBanner.java:40-43`) and must keep booting in slice tests
where no orchestrator exists. Absent the bean, print the configured value with the
suffix `(unverified)` — never bare.

**Rejected**: re-deriving the identity comparison inside the banner
(`engine == fallbackEngine`). That would be a second source of truth for the one fact
FND-49 and FND-56 already fought over, and the next fix would have to land in three
places. The banner *asks*; it does not *decide*.
**Also rejected**: dropping the FND-49 constructor WARN once the banner is honest. Two
different readers — logs get grepped after the fact, the banner is seen live.

### STV-2 — LLM readiness is checked at boot, reported in the banner, and blocked in the script — not thrown from the constructor

The genuine fork, and it is a real one: **hard-fail the `AdkDiagnosisEngine` constructor**
(finding 2's suggestion) vs. **warn at boot and surface it**.

**Decision: warn + surface + a hard stop one level up, in `run-adk.sh`.** Reasons, in
order of weight:

1. The FND-49 precedent is recorded *in the file this would change*
   (`DiagnosisOrchestrator.java:138-143`: "do NOT fail fast — a hackathon build shouldn't
   refuse to boot over this"). Inverting it for the sibling misconfiguration, in the same
   release, produces two contradictory policies for one failure class.
2. Measured blast radius: `new AdkDiagnosisEngine(...)` appears at **11 sites in 3
   `-Padk` test files**. Nine of them set `LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL` as
   system properties immediately before constructing (`AdkLiveRoundTripTest:47-51,117-121,181-185,275-279`;
   `AdkToolEdgeSafetyTest:146-150,187-191,227-231`) — but **`AdkAllowlistVisibilityTest`
   constructs the engine twice with the LLM properties deliberately unset**
   (`AdkAllowlistVisibilityTest.java:42-45` and `:74-76`), because it only reads
   `instruction()`. A throwing constructor breaks that test for no gain to it.
3. Once STV-1 lands, "warn" is no longer a line that scrolls away — the banner *is* the
   report surface, and it is the last thing on screen.
4. The presenter-backstage guarantee the finding actually wants is bought more cheaply in
   `run-adk.sh`, which already checks `secrets.properties` **exists** (lines 40-45) and
   then says "Continuing anyway". Extending that check from *file present* to *the three
   keys present* stops the run before Maven starts — before the presenter is on stage.

**Mechanism (three parts):**

- `AdkModelFactory` gains `public static List<String> missingKeys()` — the same three
  `cfg()` lookups as `require()`, returning the absent `LLM_*` names, **constructing no
  model and throwing nothing**. `fromEnv()` keeps its per-run `require()` calls verbatim;
  this is an additional read, not a refactor of the existing one.
- A neutral interface in `src/main/java` — `com.company.triage.orchestration.EngineReadiness`
  with `List<String> missingConfig()` — implemented by `AdkDiagnosisEngine`, whose
  constructor calls `missingKeys()`, logs a WARN naming them, and stores the result.
  `src/main/adk` may import `src/main/java`; the reverse stays forbidden (J11 LT1). The
  banner injects `ObjectProvider<EngineReadiness>` and appends
  `— MISSING CONFIG: LLM_MODEL (will degrade to deterministic on first run)` to STV-1's
  engine line.
- `run-adk.sh`: after the existing file check, assert the three
  `triage.integrations.llm.{base-url,api-key,model}` keys are non-blank *or* supplied as
  env vars, and **exit non-zero** naming the missing one, reusing the wording
  `AdkModelFactory.require()` already produces. This is the only hard stop in STV-2, and
  it is in the layer where stopping is free.

**Also in this decision — the `secrets()` negative cache (finding 2b).** Two changes,
because they cover different cases: (a) re-stat when the cached `Properties` is *empty*,
so a file created after boot is picked up (the `run-adk.sh` "continuing anyway" path
becomes recoverable); (b) append `(if you just edited secrets.properties, restart — it is
read once at startup)` to the `require()` message, which covers the *present-but-edited*
case that re-statting cannot. Cost of (a) is one `Files.isReadable` per run, on a path
that already makes a network call to a model.

### STV-3 — Config the app cannot run without is `@NotNull @NotEmpty`, and an empty allowlist is an explicit choice

**Rule**: if a field's absence produces an exception anywhere downstream, it is validated
at the boundary. `TriageProperties`' own javadoc already promises this — "bound and
validated once at startup", "fail before serving a single request"
(`TriageProperties.java:13-31`) — the three fields in finding 3 simply were not brought
along.

**Mechanism**:

| Field | Annotation | Why |
|---|---|---|
| `Sumo.sourceCategoryPattern` | `@NotNull` | `sourceCategoryFor` calls `pattern.replace` unguarded (`:102`) |
| `Sumo.allowedEnvironments` | `@NotNull @NotEmpty` | `TriageMateTools.searchLogs` calls `allowedEnvs.contains` (`:124-126`); empty ⇒ every environment denied |
| `GitLab.allowedProjects` | `@NotNull @NotEmpty` | `TriageMateTools.searchCode` calls `gitLabAllowlist.contains` (`:157`); empty ⇒ every project denied |
| `Sumo.sourceCategoryOverrides` | compact-constructor default `Map.of()` | genuinely optional — `sourceCategoryFor` **already** null-guards it (`:99-101`), so a `@NotNull` here would forbid a config that works |

**The `@NotEmpty` is the load-bearing half, and it is a decision, not a nicety.** An
empty allowlist is representable and means *deny everything* — a silently zero-capability
run, which is the same "looks fine, does nothing" failure the banner work exists to kill.
A deployment that genuinely wants no code search sets `triage.connectors.gitlab=mock`,
which is the knob that already exists for it.

**Rejected**: normalizing `null → List.of()` in the record's compact constructor for the
two allowlists. It makes the app boot on a config that cannot work, and moves the
diagnosis from a named property in a startup error to "why did search_code reject
everything?" mid-run.

### STV-4 — The null guard is a blast shield, not the guarantee

STV-3 makes `rankAllowlist(app, null)` unreachable *through Spring binding*. It stays
reachable through every direct caller — `IncidentSignalsTest` calls it directly at four
sites (`IncidentSignalsTest.java:80,82,94,96`), and it is package-private static, so any
future caller inherits the hazard.

**Mechanism**: one line in `IncidentSignals.rankAllowlist` — treat `null` as `List.of()`
— making the function total. With an empty list the existing loop
(`DeterministicDiagnosisEngine.java:213-216`) simply does not execute, `codeHits` stays
`List.of()`, and the already-honest trace line prints `projects=[] → 0 hit(s)`.

**Stated explicitly so a later reader does not delete the wrong one**: STV-3 is the
guarantee; STV-4 is defence in depth. Removing STV-3 because "the null is handled" would
restore exactly the silent-empty-allowlist run STV-3 rejects.

### STV-5 — Prove the mechanism, not the annotations

The existing hand-built-validator test (finding 5, corrected above) proves the
annotations. Nothing proves Spring applies them, and that wiring is exactly what a
dependency bump or a "this annotation looks unused" refactor removes silently.

**Mechanism**: a new `TriagePropertiesValidationTest` in `src/test`, using
`ApplicationContextRunner` with `@EnableConfigurationProperties(TriageProperties.class)`,
one case per validated shape:

- `triage.engine=adkk` → context fails, failure message names `engine`
- `triage.servicenow.write-field=descriptionz` → fails, names `writeField`
- `triage.orchestrator.timeout-ms=0` → fails (`@Min(1)`)
- `triage.sumo.allowed-environments=` (empty) → fails (STV-3's `@NotEmpty`)
- a clean case mirroring `application.yml`'s values → context starts, `props.engine()` is
  `DETERMINISTIC`

The last case matters as much as the failures: without it, a test suite that only asserts
rejection passes just as happily if *everything* is rejected.

### STV-6 — The ready-event listener does no unbounded I/O

`resolves()` is documented "Cheap: a hosts-file lookup, no network"
(`StartupBanner.java:87`). `InetAddress.getByName` consults the system resolver whenever
the name is absent from hosts — which is the branch the method exists to detect. With
`triagemate.auspost.local` (`application.yml:48`) that is an mDNS/multicast query.

> **Update (2026-08-05):** the configured hostname is now
> `triagemate.auspost.com.au`, so the specific *mDNS* leg of this finding no longer
> applies — `.com.au` goes to the ordinary DNS resolver, not multicast. The finding and
> its decision **stand unchanged**: an absent name still reaches the system resolver, and
> a corporate DNS server or proxy can be slower to answer than mDNS is, so the 250 ms
> bound below is still what makes the listener's cost predictable. The text above is left
> as written — it recorded what was true when the finding was made.

**Decision: bound it, don't remove it.** Run the lookup on a short-lived daemon thread
with a **250 ms** budget; on timeout treat the name as unresolved — which is the truthful
answer for a name that does not resolve promptly — and print the existing
`(not set up yet — run bin/setup-custom-domain.sh)` annotation. Fix the comment to say
what the call really does.

**Rejected**: dropping the check and always printing the URL bare — the method's javadoc
(`:67-75`) argues correctly that a bare link to nowhere is worse than an annotated one.
**Rejected**: parsing `/etc/hosts` directly — non-portable, and it would report "not set
up" for a machine where someone legitimately added a real DNS record.

## Verification

Baseline before this card: **152 default / 201 `-Padk`**, both green. Every count below
is an addition, not a replacement.

| Guarantee | Test | Profile |
|---|---|---|
| STV-1 — banner never claims an engine that is not wired | **`StartupBannerTest`** (new; the class has **no test today**): three cases — deterministic; `engine=ADK` with `isAdkActuallyActive()==true` → `adk (live agent)`; `engine=ADK` with `false` → line contains `NOT ACTIVE` and `-Padk`. Capture via a log appender or by extracting the line-building into a package-private method the test calls. | default |
| STV-1 — missing bean does not break the banner | Same class: no `DiagnosisOrchestrator` in the provider → line ends `(unverified)`, no exception from `onReady` | default |
| STV-2 — missing LLM config is known at construction, not first run | **`AdkEngineReadinessTest`** (new): clear the three `LLM_*` system properties, construct `AdkDiagnosisEngine`, assert **it does not throw** and `missingConfig()` lists all three; then set them and assert it is empty. The "does not throw" assertion is the one that protects `AdkAllowlistVisibilityTest`'s two bare construction sites. | **`-Padk`** |
| STV-2b — a `secrets.properties` created after first read is picked up | Same class: read with no file (cache goes empty), write a temp file, assert the value now resolves | **`-Padk`** |
| STV-2 — the script stops backstage | Manual, in `DEMO-RUNBOOK.md`'s pre-flight: `./run-adk.sh` with `model` commented out exits non-zero naming `LLM_MODEL`, before Maven starts | n/a |
| STV-3 / STV-5 — Spring actually enforces the annotations | **`TriagePropertiesValidationTest`** (new), five `ApplicationContextRunner` cases above. Deleting `@Validated` from `TriageProperties.java:40` must turn this red — worth checking by hand once when it is written, since a test that cannot fail is the failure mode it exists to prevent | default |
| STV-3 — no existing construction breaks | `TriagePropertiesFixture` (`src/test/.../TriagePropertiesFixture.java`) already supplies all four fields non-null and non-empty, so the whole existing suite is unaffected; `AdkAllowlistVisibilityTest:63-72` builds its own `Sumo`/`GitLab` and is likewise complete. Both re-run as-is | default + `-Padk` |
| STV-4 — `rankAllowlist` is total | `IncidentSignalsTest` gains two cases: `null` and `List.of()` → empty result, no throw | default |
| STV-6 — the ready event does not stall | `StartupBannerTest` case with an unresolvable hostname asserts `onReady` returns within a small budget (~1 s) and still prints the "not set up yet" annotation | default |

## Out of scope

- **What the browser UI says about the engine** — console-side honesty is this card;
  the in-page engine/connector labelling is **J23-live-ui-honesty**.
- **The banner's URL/port lines and the run scripts' port selection** —
  **J15-port-contract-demo-runbook**. J20 touches `run-adk.sh` only to add the LLM-key
  pre-flight check.
- **What the guardrails do once the allowlists are populated** (enforcement points,
  denial reasons, coverage) — **J18-guardrail-enforcement-completeness**. J20 only
  guarantees the lists exist and are non-empty at boot.
- **What the model is told about those allowlists** (instruction/config fidelity,
  FND-60's "name the valid values") — **J19-instruction-config-fidelity**.
- **The fallback engine's behaviour on real input** once it is running —
  **J14-fallback-real-input-robustness**. The NPE in STV-4 is a *config* defect that
  happens to surface in the fallback, not a fallback-robustness defect.
- **Which interface the server binds to, auth, CORS** — **J21-network-exposure-posture**.
- **Real-gateway credential validation at boot** — **J22-real-gateway-contract-tests**.
