# J15 — The Run Contract (ports, endpoints, and a runbook that matches the scripts)

**State**: 🟢 Built (2026-08-05) — the runbook's terminal table and fallback flip are
re-derived from what the scripts actually do, both stale `application.yml says 8080`
rationales are corrected, and the 8080 fallback now verifies 8080 is free before using it.
Verified by executing the scripts' own port-selection logic: the old T3 spelling resolved to
port 80 (colliding with the primary), the corrected one resolves to 8081 · **Complexity**: Simple ·
**Priority**: HIGH ·
**Depends on**: J1 (Spring Boot app + `application.yml`), J2 (ADK path + Copilot proxy) ·
**Amends**: J7 (RC6 demo safety — the two-engine standby procedure it carries) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 3 confirmed
findings + 1 unverified tail item

## Essence

**Where the app listens, and where it talks, is decided in exactly one place per
question — and every script, doc and rehearsal reads that place rather than restating
it.** The demo's whole insurance policy is "two engines, two ports, flip the browser
tab." That policy is currently written against ports the scripts stopped using on
2026-08-04, so the standby the presenter would flip to does not exist. This card makes
the port contract and the proxy-endpoint contract single-sourced and re-derives the
runbook from them.

## Why this is a concept, not four small fixes

Fix the runbook alone and it drifts again the next time a port changes — the drift is
what happened, not the defect. Three of the four findings are the *same* structural
fault seen at different distances:

| Question | How many places claim to answer it | Actual authority |
|---|---|---|
| What port does the app default to? | 4 — `application.yml:36`, `run-deterministic.sh:50-55`, `run-adk.sh:128-129`, `DEMO-RUNBOOK.md:113-116` | `application.yml:36` (`port: 80`) |
| How do I pin a port? | 3 — the scripts' parser, `CUSTOM-DOMAIN.md:81`, `DEMO-RUNBOOK.md:114` | the scripts' parser (`--server.port=N`) |
| Where is the Copilot proxy? | 3 — `run-adk.sh:45-46`, `bin/e2-proxy-spike.sh:20`, `secrets.properties` | `secrets.properties` (`AdkModelFactory.java:28` reads it) |

In every row the *derived* copies outnumber the authority, and in every row at least one
copy is already wrong. Patching the wrong copies leaves the ratio unchanged. The
concept is: **name one authority per question, make the others derive or point, and add
one cheap check that fails when a copy is re-introduced.**

The port question has a second structural half that no single finding states: the
scripts' bindability pre-check exists *because* a port failure should be a one-line note
rather than a Spring stack trace 20 s in (`run-deterministic.sh:25-26`) — but it is
applied to exactly one of the three ports the scripts can end up on. A contract that
guarantees "you will always be told which port you got, before Maven starts" is a
different, stronger thing than a check on port 80.

## Evidence — what the review found

| # | Severity | What | Where | Failure |
|---|---|---|---|---|
| 1 | **HIGH** | The rehearsed fallback flip targets a port nothing serves | `docs/design-java/DEMO-RUNBOOK.md:114` (also `:101`, `:113`, `:116`, `:164-168`; `docs/design-java/DEMO.md:14`, `:75`) | T3 is documented as `./run-deterministic.sh -Dspring-boot.run.arguments=--server.port=8081`. The script's parser is `case "$arg" in --server.port=*)` (`run-deterministic.sh:64-66`) — the `-D…` form does not match, so **T3 is not pinned to 8081**; it takes the default and the `-D` string is forwarded to Spring as an ignored non-option arg. On stage, "just move the browser to `http://localhost:8081`" (`:146`) lands on connection-refused. |
| 2 | LOW *(corrected down from MEDIUM)* | The bindability pre-check is not applied to the fallback it selects | `run-adk.sh:142` + `:153`, `run-deterministic.sh:70` + `:81` | `port_is_bindable 80` runs; on failure both scripts hard-set `PORT="8080"` with no second check. If 8080 is also held, the exact failure the check exists to prevent — `Port 8080 was already in use`, ~20 s into Maven, after the proxy has already been started — happens anyway. |
| 3 | LOW | The scripts' stated port rationale is the inverse of the configuration | `run-deterministic.sh:50-55`, `run-adk.sh:128-129` | The comment says "application.yml deliberately still says 8080 … defaulting the APP to 80 would make the test suite and everyday `mvn` runs need sudo." `application.yml:36` says `port: 80`. `run-adk.sh:129` defers to that note as the authority ("see the long note there"), so the one place a reader is sent is the wrong one. |
| 4 | LOW — **unverified (tail)** | The proxy the script health-checks is not necessarily the proxy the app calls | `run-adk.sh:45-46`, `bin/e2-proxy-spike.sh:20` | Both hardcode `http://localhost:4000/v1`; the app reads `triage.integrations.llm.base-url` (`AdkModelFactory.java:28`). Both scripts already grep `secrets.properties` for the *model* (`e2-proxy-spike.sh:28-32`) but not the base-url, so a proxy moved to another port green-lights a spike and an auto-start for an endpoint the app never uses. **One-look check before acting.** |

**Two narrowings from the adversarial verifier, both kept:**

- Finding 2's original claim said the documented side-by-side comparison walks into the
  collision. It does not — `DEMO-RUNBOOK.md:114` explicitly assigns the second instance
  its own port, and `adk-mode.md`'s "side-by-side" is a comparison table, not a
  run-both instruction. Only off-runbook usage (a forgotten first server, an unrelated
  dev server on 8080) hits it. Severity corrected **MEDIUM → LOW**.
- Finding 3 attributed the `port: 80` flip to `3e8bb1b`. Wrong commit: the yml change
  landed in **`f947ee3`** ("default to port 80 app-wide", 2026-08-04); `3e8bb1b` is the
  sysctl setup that makes 80 bindable unprivileged. Both postdate the runbook's last edit,
  which is *why* the runbook is stale.

**Verified while writing this card, and worth recording:**

- `README.md:41`/`:50`/`:106` and `e2e/playwright.config.js:22-29` were both updated for
  port 80 correctly. The drift is confined to `docs/design-java/` and the two script
  comment blocks — this is doc rot in a specific corner, not a repo-wide inconsistency.
- `e2e/playwright.config.js:29` sets `reuseExistingServer: true` against
  `http://localhost:8080`. A deterministic standby left running on the 8080 fallback is
  silently adopted as the e2e backend — a further reason 8080 must not be an
  unannounced, unchecked landing spot. It is a contended port with two owners.
- `docs/design-java/CUSTOM-DOMAIN.md:1`/`:4` still frame the doc as "instead of
  `localhost:8080`", but its Option A/B body (`:78-118`) is correct — it passes
  `--server.port=80` explicitly, which the parser accepts. Framing rot only.

## Design

### PC-1 — One authority per question, and the other places point at it

| Question | Authority | Everyone else |
|---|---|---|
| App default port | `src/main/resources/application.yml:36` — and its existing comment block, which is already accurate | Scripts and docs say "see `application.yml`", they do not restate the value or the rationale |
| Demo port selection + fallback | the run scripts (PC-2) | Docs describe the *behaviour* ("the script prints the URL it actually got"), never a literal port |
| Copilot proxy endpoint | `secrets.properties` → `triage.integrations.llm.base-url` | `run-adk.sh` and `bin/e2-proxy-spike.sh` derive from it (PC-5) |

**Rejected: revert `application.yml` to 8080** to make the stale comments true again.
That would undo the deliberate demo property the port-80 work bought — a URL that reads
`http://localhost` with no port tail, "like a deployed service, not a dev server"
(`application.yml:27-28`). The comment is wrong; the config is right. Fix the comment.

**Rejected: have the scripts parse `application.yml`** to learn the default — a YAML
dependency in bash for a value that already has a correct comment. The scripts own the
*demo* port policy; the yml owns the *framework* default. Keep the split, delete the
prose that misdescribes it.

Concretely: `run-deterministic.sh:50-55` collapses to one accurate sentence — *the app
defaults to 80 (`application.yml`, deliberate); this script pre-checks bindability and
falls back with a printed note; `--server.port=N` always wins*. `run-adk.sh:128-129`
keeps deferring to it, which is now correct.

### PC-2 — The port ladder: every port the script may choose is checked before Maven starts

The rule: **the script never launches Maven on a port it has not proved bindable, and the
port it prints is the port it got.**

Mechanism — replace the single check + hard-set with a short fixed ladder:

```
explicit --server.port=N  →  check N.  Bindable: use it.  Not bindable: FAIL FAST (see below).
no explicit port          →  try 80, then 8080, then 8081, then 8082.
                             First bindable wins; print which and why the earlier ones were skipped.
                             None bindable: exit 1, naming the holder.
```

Three decisions inside that:

1. **An explicit port is never silently moved.** Today an explicit port bypasses the
   check entirely (`run-deterministic.sh:64-66` sets `PORT` and skips the `if` block),
   so an occupied explicit port dies in Spring 20 s later. It must be checked — but on
   failure it must **exit**, not ladder. The presenter asked for that port because a
   browser tab, a curl, or a second terminal is already pointed at it; moving it would
   break the thing that made them pin it. Failing in one line lets them fix it in five
   seconds.
2. **The ladder is fixed and short (80 → 8080 → 8081 → 8082), not an unbounded scan.**
   An unbounded scan produces an unpredictable URL and would happily start the demo on
   :8137. Four rungs covers the real cases (privileged 80, a busy 8080, a second
   instance, an unrelated dev server) and keeps the possible URLs memorable.
3. **Failure names the holder.** `exit 1` with `ss -ltnp sport = :8080` (Linux) or
   `lsof -i :8080` (macOS) output, falling back to a plain message if neither is
   available. "Port 8080 is held by pid 12345 (java)" ends the investigation; "port in
   use" starts one.

Side-effect worth naming, because it is the load-bearing one for the demo: **launching
the second script with no arguments now lands on 8081 by itself** when the first already
holds 80/8080. The dual-server layout stops depending on the presenter remembering a flag.

**Rejected: exit immediately when 80 is unbindable**, forcing an explicit port. It is
the correct purist answer and the wrong demo answer — an un-setup laptop would refuse to
start the offline safety net, which is the one path that must never need anything.

### PC-3 — The runbook is re-derived from the scripts, not edited around them

`DEMO-RUNBOOK.md` §1–§4 is rewritten so that every command in it is a command the parser
accepts and every URL is a URL something serves:

- **T2 / T3 commands** carry no port literal in the common case: `./run-adk.sh` then
  `./run-deterministic.sh`, and the presenter reads the URL each script prints as its
  last line before Maven. Where a literal is genuinely wanted, the syntax is
  `./run-deterministic.sh --server.port=8081` — the form `run-deterministic.sh:22`
  already documents and the parser already accepts. The `-Dspring-boot.run.arguments=`
  form is **deleted from all documentation**: it is a Maven-plugin property the scripts
  set themselves (`run-deterministic.sh:90`), never a user-facing argument.
- **The port column** (`:110-116`) becomes "whatever the script printed", with the
  ladder from PC-2 named once. The browser-tab row and the smoke-test curl (`:101`)
  follow: `curl -s -X POST http://localhost/api/diagnose/INC0010005` with an explicit
  note to add the port if the script fell back — the phrasing `README.md:106` already uses.
- **The pre-flight checklist** (`:164-168`) stops naming 8080/8081 and instead asserts the
  observable: *both instances answered a warm-up POST on the URL they printed*, and
  *both browser tabs are open on those two URLs*. That is checkable without knowing the
  ports in advance, which is the property that makes it survive the next port change.
- **`DEMO.md:14` and `:75`** drop `:8080` the same way.
- **`CUSTOM-DOMAIN.md:1`/`:4`** reframes from "instead of `localhost:8080`" to "instead
  of `localhost`". Body unchanged — it is already correct.

**The rewrite is not done until the flip has been rehearsed once against the real
scripts on a real machine** — start both, kill nothing, move the tab. Finding 1 exists
because a procedure was edited without being run.

### PC-4 — `mvn` is not assumed present: commit the Maven Wrapper

Raised by the review's grouping notes as a decision that belongs here, because both run
scripts hard-fail on it. Today `run-deterministic.sh:15-19` and `run-adk.sh:33-37` exit
with `sudo apt install openjdk-21-jdk maven`, and `DEMO-RUNBOOK.md:163` pre-flights
`mvn -v`. On the actual target — a locked-down corporate laptop with no admin rights —
`sudo apt install` is not an available remedy, so that error message is a dead end on
the one machine that matters.

**Decision: commit the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`), and have both
scripts prefer `./mvnw` when present, falling back to `mvn` on `PATH`.** A JDK is still
required (a JRE cannot build `-Padk`, per `DEMO-RUNBOOK.md:163`) — the wrapper removes
the *Maven* install, not the *JDK* install, and the runbook must keep saying so.

Honest caveat that must ship with it: the wrapper **downloads Maven on first use**, and
that download goes through the corporate proxy. So the pre-flight item becomes
`./mvnw -v` **succeeded on this laptop** — not "Maven is installed" — and the one-time
prep section gains a line telling the presenter to run it once, on the demo machine,
during prep. A wrapper that first downloads at 09:58 on demo day is worse than no wrapper.

**Rejected: vendoring a Maven distribution into the repo.** ~10 MB of binary in a
throwaway hackathon repo to avoid one prep-day command.

### PC-5 — The proxy endpoint comes from `secrets.properties` *(covers the unverified finding)*

⚠️ **Finding 4 is from the unverified tail — confirm `run-adk.sh:45-46` and
`e2-proxy-spike.sh:20` against the files before implementing.** The reading below matched
the code when this card was written, but it did not go through the adversarial verifier.

Rule: **the endpoint the scripts probe, auto-start against, and validate is the endpoint
the app will call.** `AdkModelFactory.java:28` reads
`triage.integrations.llm.base-url`; that is the authority.

Mechanism, mirroring the model lookup those scripts already do
(`e2-proxy-spike.sh:28-32`):

- `run-adk.sh` greps `^triage\.integrations\.llm\.base-url=` from `secrets.properties`,
  derives `PROXY_BASE` from it and `PROXY_PORT` from its port component, and falls back
  to `http://localhost:4000/v1` only when the key is absent.
- `bin/e2-proxy-spike.sh` uses the same value as its default for `$1` instead of the
  hardcoded literal at `:20`, keeping the argv override.
- `run-adk.sh` **only auto-starts a proxy when the derived port is the default 4000.** If
  the operator pointed `base-url` at some other port, nothing is listening there, and the
  script cannot know what command would serve it — `copilot-api start --port 5000` may
  well be wrong. Print the mismatch (`base-url says :5000, nothing answers there`) and
  let the app fail fast on the first LLM call, which it already does. Silently spawning a
  proxy on a port the app will not call is precisely the failure being fixed.

**Rejected: making the app read the script's port.** The direction of dependency matters —
`secrets.properties` is operator-editable configuration; the scripts are convenience.
Configuration is never derived from convenience.

### PC-6 — One cheap check so the copies cannot silently re-diverge

A plain-text consistency test, `RunContractDocsTest` in
`src/test/java/com/company/triage/config/`, deliberately narrow — it asserts the shape of
the drift that actually happened, not general prose quality:

1. `application.yml`'s `server.port` is `80` *(pins the authority the docs now defer to)*.
2. No file under `docs/` invokes a run script with `-Dspring-boot.run.arguments=`
   *(the exact defect in finding 1)*.
3. Every `--server.port=` occurrence in `docs/**/*.md` and `README.md` matches
   `--server.port=[0-9]+` and is not preceded by `-D` *(the syntax the parser accepts)*.
4. `e2e/playwright.config.js` still pins `--server.port=8080` *(tests must never need root
   — the property `application.yml:33-34` promises)*.

It reads files as text, needs no Spring context, and runs under the default profile — so
it costs one test and no `-Padk` dependency.

**Rejected: a broader "docs mention no port literals" rule.** `CUSTOM-DOMAIN.md` is
*about* ports and legitimately contains them; a blanket ban would either fail honestly or
be riddled with exemptions until it meant nothing.

## Verification

- **`RunContractDocsTest`** (PC-6) — the four assertions above. Default profile,
  no `-Padk`. Expect the default count to go **152 → 153**; adk stays **201**.
- **`run-deterministic.sh` / `run-adk.sh` port ladder** — no JUnit reach into bash, so
  verify by execution, recorded in this card's directory as `verification-port-ladder.md`:
  occupy 80, confirm 8080; occupy 80+8080, confirm 8081; occupy 80+8080+8081+8082, confirm
  `exit 1` naming the holder; pass an occupied `--server.port=N`, confirm fail-fast rather
  than ladder. Four runs, `python3 -c 'import socket…'` as the occupier.
- **PC-3 rehearsal** — the flip itself, run once end-to-end on a real machine: both
  scripts started with no arguments, both printed URLs opened in tabs, tab switched
  mid-diagnosis. This is the check finding 1 proves cannot be skipped; a green test suite
  would not have caught it.
- **PC-4** — `./mvnw -v` and `./mvnw -B -Padk test` both succeed on a machine with a JDK
  and no `mvn` on `PATH`. The adk profile is the one that matters: it is the path that
  needs a compiler.
- **PC-5** — after implementing, re-run `./bin/e2-proxy-spike.sh` against
  `./bin/fake-openai-proxy.py` started on a **non-4000** port with `base-url` pointed at
  it, and confirm the spike targets that port with no argv override. Also confirm
  `run-adk.sh` prints the mismatch and does **not** spawn a proxy on 4000.

Both profiles must be green before any of this is pushed: `mvn -B test` and
`mvn -B -Padk test`. `RealSumoGatewayLiveTest` needs network — an environment failure
there is not a regression.

## Out of scope

- **What the startup banner claims and whether it is true** — the banner's URL line, the
  `public-hostname` display value, and boot-time validation belong to
  **J20-startup-truth-and-validation**. J15 owns which port is *chosen*; J20 owns whether
  what is *printed about it* is honest.
- **Which interface the server binds and who else on the network can reach it** —
  **J21-network-exposure-posture**.
- **Whether the ADK path degrades correctly when the proxy is unreachable** (the FND-7
  fallback that finding 4's scenario ends in) — **J14-fallback-real-input-robustness**.
- **Whether the real gateways behave as their contracts claim** —
  **J22-real-gateway-contract-tests**.
- **Anything the UI asserts during a live run** — **J23-live-ui-honesty** and
  **J12-live-trace-delivery**.
- **The `.gitignore` dead lines** noted alongside finding 4 in the review's direct-fix
  bucket. PC-4 answers only the Maven-wrapper question that item raised; the ignore-file
  cleanup is a direct fix, not a concept.
