# Exploration C — Visual design & platform logos

**Bias**: the visual/UX layer and asset practicalities. Transport (SSE vs poll vs
replay) and the honesty/pacing debate are owned by other explorations; where a
choice here depends on them I say so and stay agnostic.

**Read first**: `src/main/resources/static/index.html` (138 lines, verified),
`docs/design-java/concepts/J7-demo-ui-and-dataset/README.md`,
`docs/design-java/concepts/J4-diagnosis-report/README.md`,
`DeterministicDiagnosisEngine.java` lines 63–177 (the trace emitter).

---

## 0. What the existing UI actually gives us to match

Verified from `index.html`:

- Tokens: `--bg:#0f1420 --card:#182030 --ink:#e8edf6 --muted:#93a1b5
  --acc:#5aa9ff --ok:#3ecf8e --warn:#ffb454`. No `--bad`/red token exists —
  the degraded banner hardcodes `#f2b25c` / `#2a1e0c`. **Add `--bad` + `--dim`
  rather than hardcoding more hex.**
- Idiom: `.card` (bg `--card`, 1px `#24304a`, radius 12), `.card h2` = 13px
  uppercase muted label, `.ev` = evidence row with 3px left accent border on
  `#0e1626`, `.pill`, `.bar > i` confidence bar, `.trace` = 12px `ui-monospace`
  `white-space:pre-wrap`.
- The trace is rendered **last**, as one `<div class="trace">` of
  `t.map(esc).join('\n')`. That's the element this work replaces.
- Everything renders in one shot from `render(data)`; there is no incremental
  DOM. Any live panel is therefore **new** code, not a refactor.
- `body` font is `system-ui,sans-serif` at 15px. On a projector 15px body is
  already marginal; the trace at 12px is unreadable past ~2 m. **The thinking
  panel must be its own type scale (16–18px), not inherit `.trace`'s 12px.**

---

## 1. Logo sourcing & legality

### What I verified (not assumed)

I downloaded `data/simple-icons.json` from simple-icons `master`
(457,996 bytes, **3,450 icons**) and queried it directly. Results, verbatim:

| Title | hex | in simple-icons? | per-icon `license` field |
|---|---|---|---|
| Confluence | `172B4D` | yes | **absent** |
| GitLab | `FC6D26` | yes | **absent** |
| Sumo Logic | `000099` | yes | **absent** |
| ServiceNow | — | **NOT PRESENT AT ALL** | n/a |
| (Atlassian) | `0052CC` | yes | absent |

Two findings that matter:

1. **ServiceNow has no simple-icons entry.** Searching all 3,450 titles for
   `servicenow` returns `[]`. The single most important platform in this demo —
   it's the incident system of record and appears in 4 of the 9 trace lines —
   has **no available drop-in mark**. Any "just use simple-icons" plan is
   already broken for a quarter of the set.
2. **The prompt's premise that "GitLab's is CC-BY-SA-4.0" does not hold on
   current master.** 223 of 3,450 icons carry a `license` object (29 of them
   `CC-BY-SA-4.0`); GitLab is **not** one of them. Its entry is
   `{"title":"GitLab","hex":"FC6D26","source":".../press/press-kit/",
   "guidelines":".../trademark-guidelines/"}`. So the repo-level CC0-1.0
   nominally applies to the path data — which is exactly the case
   simple-icons' own `DISCLAIMER.md` warns about.

**simple-icons' disclaimer, verified**: the project is CC0-1.0, but that
"doesn't imply that all icons within the project are also CC0"; per-icon
licence data is community-maintained and "may be outdated". CC0 waives
*copyright*, and explicitly disclaims responsibility for clearing "rights of
other persons that may apply". **Trademark is not copyright.** A CC0 SVG of a
trademarked logo is a CC0 *drawing* of somebody else's *mark*.

**ServiceNow specifically** (searched; `servicenow.com/trademarks.html`,
`servicenow.com/logousage.html`): plain-text nominative references are
generally fine when correctly formatted and attributed; **logo use requires a
licence or written permission**, logo files are obtained by request form, and
ServiceNow retains a vendor that proactively monitors for marks and files
takedowns. That is an unusually active enforcement posture, and it is the one
mark we'd have to hand-trace anyway.

### Verdict for an internal hackathon demo

Honest read of the risk gradient:

- **Genuinely low risk**: nominative *text* — "ServiceNow", "Confluence",
  "GitLab", "Sumo Logic" as words in a trace line. This is descriptive use of
  systems the org actually runs. Already what `index.html` does today.
- **Low-but-nonzero**: real marks, inlined, in a slide-deck-grade internal
  demo, shown once, not distributed, not on a public page, no implication of
  endorsement or partnership. This is the classic referential-use zone. It is
  *practically* what every internal integration dashboard on earth does.
- **Where it turns risky**: the repo is public or gets published; the deck is
  posted externally; the UI reads as a partner/endorsed product; a lettermark
  gets recoloured/distorted (all four brand guidelines forbid modification);
  or the marks end up in a recorded backup run that circulates.

For *this* project the deciding factor is not really legal — it's the two
practical facts above: **ServiceNow doesn't exist as an asset**, and — I
inspected the SVG — **`sumologic.svg` is the full wordmark crammed into a
`0 0 24 24` viewBox**. A wordmark at 20 px on a projector is a grey smudge.
Confluence and GitLab are true single-path marks and would render fine; the set
is therefore **inconsistent by nature**: 2 clean glyphs, 1 illegible wordmark,
1 missing. Mixing a real GitLab tanuki next to a hand-drawn ServiceNow
approximation looks *worse* than four consistent house glyphs.

### Recommendation (default)

**Ship generic house glyphs + a 2-letter lettermark, tinted with each
platform's real brand hex.** Concretely, per platform:

| Platform | glyph | lettermark | tint (real brand hex) |
|---|---|---|---|
| ServiceNow | ticket / clipboard | `SN` | `#62D84E` (Now green) |
| Confluence | open book / pages | `CF` | `#2684FF` (Atlassian blue, lifted from `172B4D` for dark-bg contrast) |
| Sumo Logic | stacked log lines | `SL` | `#4C7CFF` (lifted from `000099`, unreadable on `#0f1420`) |
| GitLab | angle-brackets / branch | `GL` | `#FC6D26` |

Why this wins on its own merits, not just as a legal dodge:

1. **Four consistent marks**, same optical weight, same 22 px box, all drawn on
   the same grid. The real set cannot be made consistent.
2. **Legible at 3 m**: a 2-letter mark at 11px/700 plus a bold glyph silhouette
   reads at distance; a tanuki at 20 px does not.
3. **Colour still does the recognition work.** Orange = GitLab, green =
   ServiceNow. Audience reads the row by hue before they read the letters.
   Using the real hex is nominative and uncontroversial — colour isn't a mark.
4. Zero asset pipeline, zero attribution footnote, zero "can we ship this"
   conversation five minutes before the demo.
5. The **platform name is spelled out in the line text anyway** ("Searching
   Confluence for a runbook…"). The badge is a scanning aid, not the label.
   That makes the real-logo upside small.

**The swap-in is a one-liner.** The sketch below keeps every mark in a single
`PLAT` table as an inline SVG string. If the operator decides real marks are
fine, replacing four `svg:` values (three from simple-icons, ServiceNow traced
from its press kit) is a 4-line diff with no other change. Build that seam
deliberately; don't fork the component.

If real marks *are* used: add one 11px muted footer line —
"Platform names and logos are trademarks of their respective owners; used here
to identify the systems consulted." That is the cheap, correct mitigation.

---

## 2. Animation vocabulary

Four states. All CSS-only — no `requestAnimationFrame`, no JS timers driving
animation (JS only flips a `data-state` attribute; see §3).

| state | reads as | technique |
|---|---|---|
| `queued` | not started, present | `opacity:.32`, badge desaturated (`filter:grayscale(1)`), no motion |
| `active` | working, now | badge `pulse` (scale+glow, 1.4 s), a 2 px accent rail sweeping under the row (`shimmer`), animated `…` |
| `done` | settled, factual | full opacity, `--ok` check, badge full colour, one 220 ms `settle` fade-in of the result text |
| `warn` / `fail` | degraded / denied | `--warn` / `--bad` left border + matching glyph (`!` / `⊘`), no motion |

Design calls for a projector:

- **Motion must be on the badge and a rail, not on the text.** Animated text is
  the single worst thing at distance — moving glyphs stop being readable.
  Pulse the 22 px badge and sweep a rail; leave the words still.
- **Glow, not spin.** A 20 px spinner at 3 m is a flickering dot. A box-shadow
  pulse changes the *area* of lit pixels, which survives projector contrast
  loss. Verified reasoning, not measured — worth one projector check in the
  dry run.
- **One active row at a time**, and it is the visually loudest thing on screen.
  Queued rows at 32 % opacity make the active row pop without adding chrome.
- **Left rail per row**, 3 px, colour-coded — reuses the existing `.ev` idiom
  exactly, so it looks native to the app.
- **`prefers-reduced-motion`**: kill `pulse`, `shimmer`, and the dot animation;
  keep a static accent-tinted badge ring so "active" is still distinguishable
  by colour + a static `▸`. State must never be conveyed by motion alone —
  that's also the accessibility floor.
- Type scale: row text **16px/1.45**, result monospace **15px**, meta 12px.
  Deliberately larger than `.trace`'s 12px.

---

## 3. The text-replacement mechanic — where both strings come from

The operator's ask is one line that starts as *"Searching Sumo Logic for the
error token…"* and becomes *"Found 4 log lines · PAYMENT_RECONCILE_MISMATCH"*.

**Today only the second string exists**, and only approximately. The emitter
produces:

```
sumo.search(scope=prod/payment, window=±10m, max=20) → 4 line(s); errorToken=PAYMENT_RECONCILE_MISMATCH
```

That is one flat `String` in `List<String> trace` on `DiagnosisResult`. There is
no start label, no platform field, no status, no duration. So **the in-progress
verb must be authored somewhere new.** Three options; I recommend a staged path.

### Option A — client-side step registry (recommended for the demo)

Author the verb labels **in the frontend**, keyed by the stable
`platform.method` prefix the emitter already writes. No backend change at all.

```js
const STEP = {
  'servicenow.getIncident':          {p:'servicenow', verb:'Reading the incident from ServiceNow'},
  'servicenow.findSimilarIncidents': {p:'servicenow', verb:'Looking for similar resolved incidents'},
  'servicenow.findOwnership':        {p:'servicenow', verb:'Resolving CI ownership'},
  'confluence.search':               {p:'confluence', verb:'Searching Confluence for a runbook'},
  'sumo.search':                     {p:'sumo',       verb:'Querying Sumo Logic for the error token'},
  'gitlab.searchCode':               {p:'gitlab',     verb:'Correlating the log line to source'},
  'servicenow.addWorkNote':          {p:'servicenow', verb:'Posting the advisory work note'},
  'understand:':                     {p:'reason',     verb:'Clarifying the reported symptom'},
  'contacts:':                       {p:'reason',     verb:'Finding who has recent context'},
  'report assembled:':               {p:'reason',     verb:'Assembling the report'},
  'adk tool call:':                  {p:'reason',     verb:'Agent choosing a tool'},
  'adk: DENIED':                     {p:'reason',     verb:'Bounds check', state:'warn'},
};
```

The **result half** is derived from the trace line by splitting on `→` and
keeping the right side (falling back to the whole line). So:

- verb label ← `STEP` table (authored, human, present-tense)
- result ← the existing trace string, unchanged and unembellished

Pros: zero backend churn; the registry is the natural home for
presentation-only wording; the raw line stays available (see §4 "Raw" toggle),
so nothing is hidden. Cons: a **two-place contract** — rename
`sumo.search` in Java and the label silently falls back to a generic
"Consulting Sumo Logic". Mitigation: one test asserting every emitted prefix
has a registry entry, and a visible generic fallback rather than a blank.

### Option B — structured steps on the wire (the right long-term shape)

Promote `List<String> trace` to `List<TraceStep>`:

```java
public record TraceStep(String id, String platform, String verb,
                        Status status, String result, long ms) {}
```

Emitter writes `verb` at call start and `result`/`status`/`ms` at completion.
Backward compatible if `DiagnosisResult` keeps a derived
`List<String> trace()` (`step -> platform + "." + method + " → " + result`) so
J5's notes and every existing test keep working. This kills the two-place
contract and gives real per-step durations, which makes the pacing look honest
instead of choreographed.

### Option C — start-event + end-event stream

Two events per step over SSE/poll. Strictly better fidelity, but it's
**transport-shaped**, and transport belongs to another exploration. Note only:
whichever transport wins, the *component below doesn't care* — it exposes
`beginStep(id)` / `resolveStep(id, result, state)` and is driven equally well
by an SSE handler, a poll loop, or a local replay.

**My call**: Option A for the demo (ships today, no Java risk), with Option B
written up as the follow-on and the component API already shaped so that
switching is a change of *driver*, not of *component*. Keep the verb strings in
`STEP` even under B — B just moves them to the server.

---

## 4. Layout

**It replaces the trace card in place — same slot, two lives.**

Sequence:

1. Submit → the output area renders **only** the thinking panel, full width, as
   a normal `.card` titled `INVESTIGATING — INC0012345`. It is the whole screen.
   Nine rows, all `queued`, so the audience sees the *plan* immediately: four
   platforms, nine steps. That preview is half the persuasive value.
2. Rows resolve top-to-bottom. Panel grows in place; no scroll jump because all
   rows exist from the start at final height (`min-height` on `.tk-row`) — this
   avoids the layout-thrash that makes these UIs feel cheap.
3. On completion the panel **collapses to a summary bar** —
   `✓ 9 steps · 4 platforms · 2.4 s` + `Show steps ▾` — and the report cards
   render below it. Collapsed by default so the report is the hero, expandable
   because the trace is the credibility exhibit.
4. The collapsed panel *is* the old "Tool-call trace" card. One card, two
   states. Keeps the page structure and J7's "viewers see it really consulted
   all four sources" promise intact.

Why not a panel above that stays expanded: nine rows at 16 px is ~380 px, which
pushes the diagnosis below the fold on a 1080p projector. Collapse is not
tidiness, it's fold management.

Why not keep both a live panel and the old trace card: two renderings of the
same data, and the audience wonders which is real. Collapsing gives one.

A **`Raw ▾`** disclosure inside the expanded panel dumps the untouched
`data.trace` in the existing `.trace` style. Costs 6 lines, and it's the answer
to "did you rewrite what the tool said?" — no, here it is.

Failure interaction: a `warn`/`fail` row stops animating but **does not stop the
run** — this matches J7's "any single tool failure degrades to evidence
omitted, never a crash". If `data.engine === 'DEGRADED_TO_DETERMINISTIC'` the
amber banner still renders above everything, unchanged; the panel is additive
and must not be given authority over that signal.

---

## 5. Runnable sketch

Self-contained. Save as `docs/discovery/live-thinking-trace-ui/2-diverge/explorations/C-visual-design/sketch.html`
and open it — it runs standalone with a canned trace (the `demo()` driver at
the bottom is the only throwaway part; `beginStep`/`resolveStep` are the API).
Three inlined **house glyph** badges + one lettermark-only, per §1.

```html
<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1.0"/>
<title>TriageMate — thinking panel sketch</title>
<style>
  :root{--bg:#0f1420;--card:#182030;--ink:#e8edf6;--muted:#93a1b5;--acc:#5aa9ff;
        --ok:#3ecf8e;--warn:#ffb454;--bad:#ff6b6b;--dim:#2a3550;
        --sn:#62d84e;--cf:#2684ff;--sl:#4c7cff;--gl:#fc6d26;}
  *{box-sizing:border-box}
  body{margin:0;font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--ink)}
  .wrap{max-width:900px;margin:0 auto;padding:28px 20px 60px}
  .card{background:var(--card);border:1px solid #24304a;border-radius:12px;padding:18px 20px;margin-bottom:16px}
  .card h2{font-size:13px;text-transform:uppercase;letter-spacing:.05em;color:var(--muted);margin:0 0 12px}
  button{padding:11px 20px;border:0;border-radius:9px;background:var(--acc);color:#04122b;font-weight:600;cursor:pointer}
  .trace{font:12px/1.5 ui-monospace,monospace;color:#9fb3d0;white-space:pre-wrap}

  /* ---- thinking panel ---- */
  .tk-row{display:flex;gap:12px;align-items:flex-start;position:relative;
          min-height:44px;padding:8px 10px 8px 12px;margin-bottom:6px;
          border-left:3px solid var(--dim);border-radius:0 8px 8px 0;
          background:#0e1626;overflow:hidden;transition:opacity .25s,border-color .25s}
  .tk-badge{flex:0 0 auto;width:32px;height:32px;border-radius:8px;display:grid;
            place-items:center;background:#1b2740;position:relative}
  .tk-badge svg{width:19px;height:19px;display:block}
  .tk-lm{position:absolute;bottom:-3px;right:-5px;font:700 10px/1 ui-monospace,monospace;
         letter-spacing:-.03em;padding:1px 3px;border-radius:4px;
         background:#0e1626;border:1px solid currentColor;color:inherit}
  .tk-body{flex:1;min-width:0}
  .tk-text{font-size:16px;line-height:1.4}
  .tk-res{font:15px/1.4 ui-monospace,monospace;color:var(--ink);word-break:break-word}
  .tk-meta{font-size:12px;color:var(--muted);margin-top:2px}
  .tk-mark{flex:0 0 auto;width:20px;text-align:center;font-size:16px;color:var(--muted)}

  .tk-row[data-state="queued"]{opacity:.32}
  .tk-row[data-state="queued"] .tk-badge{filter:grayscale(1)}
  .tk-row[data-state="active"]{border-left-color:var(--acc)}
  .tk-row[data-state="active"] .tk-badge{animation:pulse 1.4s ease-in-out infinite}
  .tk-row[data-state="active"]::after{content:"";position:absolute;left:0;bottom:0;
    height:2px;width:38%;background:linear-gradient(90deg,transparent,var(--acc),transparent);
    animation:shimmer 1.5s linear infinite}
  .tk-row[data-state="done"]{border-left-color:var(--ok)}
  .tk-row[data-state="done"] .tk-mark{color:var(--ok)}
  .tk-row[data-state="warn"]{border-left-color:var(--warn)}
  .tk-row[data-state="warn"] .tk-mark{color:var(--warn)}
  .tk-row[data-state="fail"]{border-left-color:var(--bad)}
  .tk-row[data-state="fail"] .tk-mark{color:var(--bad)}
  .tk-row[data-state="done"] .tk-res,
  .tk-row[data-state="warn"] .tk-res,
  .tk-row[data-state="fail"] .tk-res{animation:settle .22s ease-out}

  .dots i{animation:blink 1.2s infinite;opacity:.25}
  .dots i:nth-child(2){animation-delay:.2s}
  .dots i:nth-child(3){animation-delay:.4s}

  @keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(90,169,255,0)}
                   50%{box-shadow:0 0 0 5px rgba(90,169,255,.22);transform:scale(1.08)}}
  @keyframes shimmer{0%{transform:translateX(-100%)}100%{transform:translateX(360%)}}
  @keyframes settle{from{opacity:0;transform:translateY(-3px)}to{opacity:1;transform:none}}
  @keyframes blink{0%,100%{opacity:.25}50%{opacity:1}}

  @media (prefers-reduced-motion:reduce){
    .tk-row[data-state="active"] .tk-badge{animation:none;box-shadow:0 0 0 3px rgba(90,169,255,.35)}
    .tk-row[data-state="active"]::after{display:none}
    .tk-row[data-state="done"] .tk-res,
    .tk-row[data-state="warn"] .tk-res,
    .tk-row[data-state="fail"] .tk-res{animation:none}
    .dots i{animation:none;opacity:.7}
  }
  .sumbar{display:flex;gap:10px;align-items:center;font-size:14px}
  .sumbar b{color:var(--ok)}
  .link{color:var(--acc);cursor:pointer;font-size:13px;text-decoration:underline}
</style></head>
<body><div class="wrap">
  <h2 style="font:600 22px system-ui">TriageMate — thinking panel sketch</h2>
  <p style="color:var(--muted)">House glyphs + lettermarks, brand-hex tinted. No network, no CDN.</p>
  <button onclick="demo()">Run</button>
  <div id="out" style="margin-top:18px"></div>
</div>
<script>
/* ---------- platform marks: swap `svg` here for real marks if approved ---------- */
const PLAT = {
  servicenow:{name:'ServiceNow',lm:'SN',c:'var(--sn)',
    svg:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><rect x="3" y="2.5" width="18" height="19" rx="2.5"/><path d="M7.5 8h9M7.5 12h9M7.5 16h5"/></svg>'},
  confluence:{name:'Confluence',lm:'CF',c:'var(--cf)',
    svg:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5.5C9.5 3.5 6 3.5 3 4.5v14c3-1 6.5-1 9 1 2.5-2 6-2 9-1v-14c-3-1-6.5-1-9 1z"/><path d="M12 5.5v14"/></svg>'},
  sumo:{name:'Sumo Logic',lm:'SL',c:'var(--sl)',
    svg:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M4 6.5h13M4 11.5h9M4 16.5h15"/><circle cx="20.5" cy="6.5" r="1.4" fill="currentColor" stroke="none"/></svg>'},
  gitlab:{name:'GitLab',lm:'GL',c:'var(--gl)',
    svg:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><path d="M8.5 6.5 3.5 12l5 5.5M15.5 6.5 20.5 12l-5 5.5"/></svg>'},
  /* non-platform reasoning steps: no lettermark, muted */
  reason:{name:'Reasoning',lm:'',c:'var(--muted)',
    svg:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5v5l3 2"/></svg>'}
};

/* ---------- step registry: prefix -> {platform, verb}  (see §3 Option A) ------- */
const STEP = [
  ['servicenow.getIncident',          'servicenow','Reading the incident from ServiceNow'],
  ['servicenow.findSimilarIncidents', 'servicenow','Looking for similar resolved incidents'],
  ['servicenow.findOwnership',        'servicenow','Resolving CI ownership'],
  ['servicenow.addWorkNote',          'servicenow','Posting the advisory work note'],
  ['confluence.search',               'confluence','Searching Confluence for a runbook'],
  ['sumo.search',                     'sumo',      'Querying Sumo Logic for the error token'],
  ['gitlab.searchCode',               'gitlab',    'Correlating the log line to source'],
  ['understand:',                     'reason',    'Clarifying the reported symptom'],
  ['contacts:',                       'reason',    'Finding who has recent context'],
  ['report assembled:',               'reason',    'Assembling the report'],
  ['adk tool call:',                  'reason',    'Agent choosing a tool'],
  ['adk: DENIED',                     'reason',    'Bounds check'],
];
function classify(line){
  for (const [pre,p,verb] of STEP) if (line.startsWith(pre)) return {p,verb,pre};
  const dot = line.indexOf('.'), guess = dot>0 ? line.slice(0,dot) : 'reason';
  return {p: PLAT[guess] ? guess : 'reason', verb:'Consulting '+((PLAT[guess]||PLAT.reason).name), pre:''};
}
function esc(s){return (s??'').toString().replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));}
/* result half = right of the arrow. Arrow-less lines (understand:, contacts:,
   report assembled:) have their registry prefix stripped so the label isn't
   repeated back at the audience. */
function resultOf(line){
  const i = line.indexOf('→');
  if (i >= 0) return line.slice(i+1).trim();
  const {pre} = classify(line);
  return (pre && line.startsWith(pre) ? line.slice(pre.length) : line).trim();
}

/* ---------- component ---------- */
function ThinkingPanel(host, lines, incident){
  const steps = lines.map((l,i)=>({i, line:l, ...classify(l)}));
  host.innerHTML = `
    <div class="card" id="tk-card">
      <h2 id="tk-title">Investigating — ${esc(incident)}</h2>
      <div id="tk-rows">${steps.map(s=>{
        const P = PLAT[s.p];
        return `<div class="tk-row" data-state="queued" data-i="${s.i}" style="color:${P.c}">
          <div class="tk-badge">${P.svg}${P.lm?`<span class="tk-lm">${P.lm}</span>`:''}</div>
          <div class="tk-body">
            <div class="tk-text" style="color:var(--ink)"><span class="tk-verb">${esc(s.verb)}</span><span class="dots"><i>.</i><i>.</i><i>.</i></span></div>
          </div>
          <div class="tk-mark">·</div>
        </div>`;}).join('')}
      </div>
      <div id="tk-raw" hidden style="margin-top:12px">
        <div class="trace">${steps.map(s=>esc(s.line)).join('\n')}</div>
      </div>
    </div>`;
  const rows = [...host.querySelectorAll('.tk-row')];
  const t0 = performance.now();
  return {
    beginStep(i){ const r=rows[i]; if(!r) return;
      r.dataset.state='active'; r.querySelector('.tk-mark').textContent='▸'; },
    resolveStep(i, result, state){ const r=rows[i]; if(!r) return;
      state = state || 'done';
      r.dataset.state = state;
      r.querySelector('.tk-mark').textContent =
        state==='done' ? '✓' : state==='warn' ? '!' : '⊘';
      r.querySelector('.tk-body').innerHTML =
        `<div class="tk-res">${esc(result)}</div>
         <div class="tk-meta">${esc(PLAT[steps[i].p].name)}</div>`; },
    collapse(){
      const ms = Math.round(performance.now()-t0);
      const plats = new Set(steps.filter(s=>s.p!=='reason').map(s=>s.p)).size;
      host.querySelector('#tk-title').textContent = 'Tool-call trace';
      const rowsEl = host.querySelector('#tk-rows'), rawEl = host.querySelector('#tk-raw');
      rowsEl.hidden = true;
      const bar = document.createElement('div');
      bar.className = 'sumbar';
      bar.innerHTML = `<b>✓</b><span>${steps.length} steps · ${plats} platforms · ${(ms/1000).toFixed(1)}s</span>
        <span class="link" id="tk-tog">Show steps ▾</span><span class="link" id="tk-rawtog">Raw ▾</span>`;
      host.querySelector('#tk-card').insertBefore(bar, rowsEl);
      bar.querySelector('#tk-tog').onclick = () => { rowsEl.hidden = !rowsEl.hidden; };
      bar.querySelector('#tk-rawtog').onclick = () => { rawEl.hidden = !rawEl.hidden; };
    }
  };
}

/* ---------- throwaway driver (replace with SSE handler / poll / replay) -------- */
const TRACE = [
 "servicenow.getIncident(INC0012345) → CI=payment-recon, env=Production",
 "understand: symptom clarified; orderId=SO-88213",
 "servicenow.findSimilarIncidents → 2 hits",
 "servicenow.findOwnership(payment-recon) → Payments Platform Support",
 "confluence.search → 1 page(s)",
 "sumo.search(scope=prod/payment, window=±10m, max=20) → 4 line(s); errorToken=PAYMENT_RECONCILE_MISMATCH",
 "gitlab.searchCode('PAYMENT_RECONCILE_MISMATCH') → 1 hit(s) (log↔code citation)",
 "contacts: 2 suggested (from 1 doc(s) + 1 code file(s), merged across sources)",
 "report assembled: 2 candidates, 5 evidence items, assignment=Payments Platform Support"
];
function demo(){
  const out = document.getElementById('out');
  const panel = ThinkingPanel(out, TRACE, 'INC0012345');
  let i = 0;
  (function next(){
    if (i >= TRACE.length) { panel.collapse(); return; }
    const k = i++;
    panel.beginStep(k);
    setTimeout(() => { panel.resolveStep(k, resultOf(TRACE[k])); next(); }, 420 + Math.random()*520);
  })();
}
</script></body></html>
```

Validity notes: no framework, no build, no external fetch, no `<img src>`.
All glyphs are inline `<svg>` with `currentColor`, so the row's `style="color:"`
tints both glyph and lettermark from one place. The only JS "animation" is
`setTimeout` in the throwaway driver — every visual transition is CSS on
`data-state`. Escaping reuses `index.html`'s `esc()` verbatim.

Integration into `index.html`: (a) add `--bad`/`--dim` and the `.tk-*` block to
the existing `<style>`; (b) paste `PLAT`, `STEP`, `classify`, `resultOf`,
`ThinkingPanel`; (c) in `run()`, replace
`out.innerHTML = '<div class="card">Investigating…</div>'` with a
`ThinkingPanel(out, …)` — which needs the step list *before* the response, so
under Option A the panel is constructed from a static expected-step list and
reconciled against `data.trace` on arrival; under Option B/C it's built from the
stream. That reconciliation seam is where this exploration hands off to the
transport exploration.

---

## 6. Open questions for synthesis

1. Under Option A the panel is built from an *expected* step list before the
   response exists — which steps run is data-dependent (`gitlab.searchCode` is
   conditional in the emitter, line 127). Either show a conservative 6-step
   skeleton and append, or don't pre-render the plan. **The pre-rendered plan is
   the best part of the design**, so this needs a real answer.
2. Whether replaying a completed trace with staggered timers is acceptable is
   the honesty question — not mine, but the component must work under either
   ruling, and it does (`beginStep`/`resolveStep` are transport-agnostic).
3. Real-vs-house marks needs one operator yes/no if the repo will ever be
   public. Default to house glyphs; the swap is 4 lines.
4. One projector dry-run to confirm 16 px rows and the glow-pulse read at
   distance. Everything in §2 is reasoned, not measured.
