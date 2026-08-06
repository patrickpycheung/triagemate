# J21 — Network Exposure Posture (who may reach the mutating endpoint)

**State**: 🟢 **Built** — all four NEP rules (2026-08-06) — NEP-1 (loopback bind) and NEP-2 (required non-safelisted header on the mutating endpoint, sent by the UI) landed. **NEP-3 landed 2026-08-06** — the banner gains bound: and writeback: lines, verified in a real run; writeback stays ON by default per the card. **NEP-4 landed 2026-08-06** — no config key, per the card; the hatch is --server.address=0.0.0.0 at launch, and anything that is not loopback now WARNS, so an escape hatch cannot be taken quietly. **All four rules built.** · **Complexity**: Simple · **Priority**: MEDIUM ·
**Depends on**: J1 (endpoint + error contract), J5 (writeback), J7 (browser UI), J11 (the
`X-Triage-Run-Id` protocol this must *not* overload) ·
**Amends**: J1 (the `POST /api/diagnose/{n}` request contract gains one required header),
J7 (the UI must send it) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 1 confirmed finding

## Essence

TriageMate's one mutating endpoint writes advisory comments to a **real** ServiceNow ticket
whenever `triage.connectors.servicenow=real`, and today anything that can send an HTTP
request to the laptop can fire it. This card fixes the **trust boundary** — *the laptop, and
only code the presenter started* — and makes it enforced in two places rather than assumed in
prose: the socket the app binds, and a header a cross-origin browser cannot attach without a
preflight the app will never answer.

## Why one card, not one config line

The obvious fix is `server.address: 127.0.0.1`. It closes exactly **one** of the two reachable
paths, and the closed one is the *less* likely to happen on stage.

- **LAN peer**: a colleague on the corp subnet `curl`s the laptop's IP. Loopback binding kills
  this outright.
- **Drive-by from the presenter's own browser**: a page the presenter merely visits mid-demo
  runs `fetch('http://localhost/api/diagnose/INC0010005', {method:'POST'})`. That request
  **originates on the laptop**, so loopback binding does nothing about it. The browser will
  refuse to let the page *read* the response — but the request is sent, the run executes, and
  the comments post.

Two different origins of the same write need two different controls, and a third thing is
needed so a future reader knows which of them the demo actually relies on. Fixing only the
bind address would leave the review item looking closed while the more probable path stays
open — which is worse than leaving it visibly open.

## Evidence — what the review found

| Sev | What | Where | Failure |
|---|---|---|---|
| MEDIUM | `server:` sets `port: 80` and never sets `server.address`, so Boot binds `0.0.0.0`; there is no Spring Security, no `SecurityFilterChain`, no CORS config, no CSRF token anywhere in `pom.xml` or `src/`; and `triage.writeback.enabled` defaults to `true` | [`src/main/resources/application.yml:27`](../../../../src/main/resources/application.yml#L27), [`:88`](../../../../src/main/resources/application.yml#L88), [`pom.xml:30`](../../../../pom.xml#L30) | Presenter runs the documented `servicenow=real` mix (`application.yml:50-57`). A colleague or attacker on the subnet runs `curl -X POST http://<laptop-ip>/api/diagnose/INC0010005` and drives a real writeback to that ticket, no credential required. |

Verified independently against the tree, not taken on the finding's word:

- `grep` for `spring-boot-starter-security` / `SecurityFilterChain` / `CrossOrigin` / `csrf` /
  `actuator` across `*.java`, `*.xml`, `*.yml`, `*.html` returns **nothing**. `pom.xml` carries
  three Spring artifacts — `starter-web`, `starter-validation`, `starter-test`.
- `X-Triage-Run-Id` is `required = false` on the POST
  ([`DiagnosisController.java:57`](../../../../src/main/java/com/company/triage/api/DiagnosisController.java#L57)),
  by design (J11 §LT4: "no header ⇒ no buffer"). A bodyless POST with no custom header is a
  **CORS simple request** — no preflight, so the drive-by path in the failure column is real,
  not theoretical. The verifier rated this HIGH confidence on exactly that reasoning.
- It is a **known-but-open** gap, not an unnoticed one:
  [`TRIAGEMATE_APPLICATION_REVIEW.md:349`](../../../../TRIAGEMATE_APPLICATION_REVIEW.md#L349)
  ("The diagnosis endpoint has no authentication or trigger secret") lists "bind to
  `127.0.0.1`" as a pre-demo item. No code, no FND entry, and no card implements it.

One narrowing the card accepts from the verifier: severity stays **MEDIUM**, not HIGH — the
blast radius is bounded by J5/J8's advisory-only guarantee (two labelled comments; never a
reassign, close or priority change) and by connectors defaulting to `mock`.

## Design

### NEP-1 — Bind loopback. The trust boundary is the machine, not the subnet.

Set `server.address: 127.0.0.1` in `application.yml` beside `port: 80`, commented in the same
style as the port rationale directly above it (`application.yml:27-36`).

Nothing legitimate loses reach:

- The banner already prints `http://localhost` / `http://localhost:<port>`
  ([`StartupBanner.java:46`](../../../../src/main/java/com/company/triage/config/StartupBanner.java#L46)).
- The friendly hostname is a **hosts-file** entry mapping to `127.0.0.1`
  ([`bin/setup-custom-domain.sh:214`](../../../../bin/setup-custom-domain.sh#L214)) — the
  banner's own comment already says "the app never binds to it" (`application.yml:46-48`).
- The e2e suite drives real HTTP at `http://localhost:{port}`
  ([`DeterministicEndToEndTest`](../../../../src/test/java/com/company/triage/orchestration/DeterministicEndToEndTest.java)),
  which is the loopback interface.

⚠️ **Implementation hazard**: `server.address` takes a *single* `InetAddress`, so binding
`127.0.0.1` does not also bind `::1`. On a host where `localhost` resolves IPv6-first, a client
that does not fall back across resolved addresses will fail to connect. The e2e test is the
canary — if it goes red on the demo laptop, that is the cause, and the fix is to make the
client target `127.0.0.1` explicitly, **not** to widen the bind.

**Rejected — a firewall rule instead.** It lives outside the repo, does not travel with a
`git clone`, and cannot be asserted by a test. A property in `application.yml` is the only
version of this decision that a teammate inherits.

### NEP-2 — Force a CORS preflight on the mutating endpoint

Add a **required** header to `POST /api/diagnose/{incidentNumber}` — `X-Triage-Local: 1`.
Because it is a non-safelisted request header, a cross-origin `fetch` must first send an
`OPTIONS` preflight; the app has **no** CORS configuration, so no
`Access-Control-Allow-Headers` comes back and the browser never sends the POST. Same-origin
calls from `index.html` are unaffected (preflight does not apply to them).

**Decision — a dedicated header, not `X-Triage-Run-Id`.** Making the existing run-id header
`required = true` would achieve the identical CORS effect with zero new concepts, and it was
the finding's own suggestion. Rejected anyway: it welds a *trace-transport* concern
(J11 §LT4 — "streaming is purely additive… no header ⇒ no buffer", spelled out in
`DiagnosisController.java:45-53`) to a *trust-boundary* concern. The day someone makes tracing
optional again — a change with no security flavour whatsoever — the drive-by path silently
re-opens. J11's own "compose, don't conflate" rule applies: two guarantees, two headers.

**This is a preflight forcer, not authentication.** It stops a browser on the laptop; it does
**not** stop `curl` (which will happily send any header), and the card does not pretend
otherwise — that job belongs to NEP-1, which removes `curl`'s reach from off-box entirely. A
shared secret would add key distribution and a rotation story to a throwaway demo for no
additional coverage once both NEP-1 and NEP-2 are in place.

**Map the rejection explicitly.** A missing required header throws
`MissingRequestHeaderException`, which
[`DiagnosisApiExceptionHandler`](../../../../src/main/java/com/company/triage/api/DiagnosisApiExceptionHandler.java)
does not handle ("six types, four statuses… anything else still falls through to Spring's
default handling"), so it would return a whitelabel body with no `error` field. Add a seventh
handler → **400** with `{"error": "..."}` in the existing shape. Shipping the guard without the
mapping would repeat FND-58's first cut *verbatim* — that story is written into
`DiagnosisController.java:19-28`.

**Rejected — Spring Security.** `spring-boot-starter-security` brings a filter chain, a
generated console password, CSRF token plumbing through a static HTML page, and a fresh set of
ways to be locked out of the app on stage — to defend a boundary that one bind address and one
header already close.

### NEP-3 — Keep writeback on by default; make the posture *visible* instead

`triage.writeback.enabled` stays `true`. Flipping it to `false` would convert a network problem
into a demo-fidelity problem: the automatic two-comment writeback with no human in the loop is
J5's stated differentiator, and a default that has to be remembered before every demo is a
worse stage hazard than the one being avoided.

Instead the exposure becomes visible at the moment it matters. `StartupBanner` gains one line
beside the existing `engine:` / `connectors:` lines (`StartupBanner.java:57-64`):

```
   bound:      127.0.0.1 (loopback only)
   writeback:  on  →  servicenow=real  (comments WILL post to a real ticket)
```

and a **WARN** when the bound address is not a loopback address while writeback is enabled —
the same idiom `application.yml:79-83` already uses for FND-45's `unattended-llm-ack`: turn a
silent gap into a decision on record.

### NEP-4 — One documented escape hatch, taken loudly

Serving the UI to another machine is **not supported** and needs no config key. Anyone who
genuinely needs it passes `--server.address=0.0.0.0` at launch, which is deliberate, visible in
the shell history, and triggers NEP-3's WARN. Adding a `triage.demo.lan-mode` flag would create
a second supported posture that must then be tested, documented, and defended — for a scenario
the runbook does not contain (presentation output is a screen driven directly off the laptop).

## Verification

- **`NetworkExposurePostureTest`** (`src/test/java/com/company/triage/config/`) — reads
  `server.address` from the `Environment` in a `@SpringBootTest` and asserts
  `InetAddress.getByName(value).isLoopbackAddress()`. This is the regression guard: it fails if
  the property is deleted, blanked, or widened to `0.0.0.0`.
- **`DiagnosisControllerTest`** — POST without `X-Triage-Local` → **400** with a JSON body
  carrying `error`; POST with it → **200** and the unchanged `DiagnosisResult`. The second
  assertion is the one that proves NEP-2 did not amend the *response* contract, only the
  request one.
- **`DeterministicEndToEndTest`** — already sets `X-Triage-Run-Id`; add `X-Triage-Local` and
  keep the existing assertions untouched. This is the only test that exercises the guard over
  real HTTP through the real filter stack, and it doubles as NEP-1's IPv6 canary.
- **`StartupBannerTest`** — asserts the `bound:` line renders the resolved address and the
  writeback state, and that a non-loopback address with writeback enabled emits a WARN.
- Nothing here needs `-Padk`, but both profiles compile the same `application.yml` and
  controller, so both must be re-run: baseline **152** default / **201** adk, zero failures.

## Out of scope

- **Which port is chosen, pinned, or fallen back to**, and whether the run scripts agree with
  `application.yml` — J15. This card fixes the *address*; J15 owns the *port*.
- **What the banner claims about the active engine**, and boot-time validation of config — J20.
  J21 only adds the `bound:`/`writeback:` line to a banner J20 is separately correcting.
- **Bounds on tool arguments** the model or ticket text controls once a run is under way — J18.
- **Whether an arbitrary real incident should be diagnosable at all** (application review item
  #1 — fixture evidence attaching to a real ticket) — J13/J14.
- **The Copilot proxy's own listen address** on `localhost:4000` — a separate process started
  by the run scripts; J15.
