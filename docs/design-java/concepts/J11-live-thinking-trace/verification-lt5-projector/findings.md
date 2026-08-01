# LT5 — Projector legibility of the in-progress affordance

**Status**: 🟡 **PARTIAL — desk analysis done, projector run OUTSTANDING**
**Trust**: 🔬 Spiked for the contrast half (measured); ❓ Unknown for the legibility half
**Blocked on**: a projector and a human at the back of a room. Not answerable from a desk,
and **not** to be inferred — J11's honesty contract exists precisely to stop that.

## What was answered without a projector

Contrast ratios (WCAG relative luminance) for each candidate against its own theme's card
background, using the **real** palettes from `static/index.html`:

| Theme | Glow-pulse ring<br>(accent @ α .22, animation peak) | Solid accent<br>(spinner / border, α 1.0) |
|---|---|---|
| midnight | 1.50 : 1 | **6.64 : 1** |
| auspost | 1.45 : 1 | **4.98 : 1** |
| hackathon | 1.31 : 1 | **4.05 : 1** |
| paper | 1.37 : 1 | **5.19 : 1** |
| projector | 1.69 : 1 | **13.58 : 1** |

Reduced-motion static ring (α .35): 1.57–2.55 : 1.

**Every glow-pulse value is below 3:1 — WCAG's minimum for non-text UI — in every theme.**
The solid-accent alternative is **3–9× higher**. And this is the *optimistic* number: it is
measured on a pixel-perfect display, whereas a projector adds ambient-light wash, lower
native contrast, and colour shift, all of which attack the low-alpha end hardest.

### What that does and does not settle

✅ **Settled: the glow-pulse cannot be the *only* in-progress signal.** A 1.3–1.7:1 ring will
not survive projection. No projector run is needed to know this — the measurement is
sufficient on its own.

⚠️ **Not settled: whether glow-pulse *contributes* anything.** Contrast math does not model
**motion salience** — the eye detects change at contrast where it cannot resolve detail, so a
pulsing badge may still draw attention even when the ring itself is not consciously visible.
That is a real effect and the maths cannot rule it out. It needs eyes.

### The reframe this produces

The open question was recorded as *"glow-pulse **vs** spinner"*. Reading the sketch, that is
not actually the choice on the table — the active row already carries **three** signals:

| Signal | Alpha | Contrast |
|---|---|---|
| left border, accent | 1.0 | 4.05–13.58 : 1 ✅ |
| shimmer bar, accent gradient | 1.0 | 4.05–13.58 : 1 ✅ |
| badge glow-pulse ring | .22 | 1.31–1.69 : 1 ❌ |

So the glow-pulse is **the weakest of three signals already present**, and the two that carry
real contrast are already doing the work. The live question is therefore not "which one" but
**"does the glow-pulse earn its place at all, or is it noise we could drop?"** — which is a
cheaper, more answerable question than the one originally written down.

### One bug found while preparing the test

`sketch.html` hardcodes the pulse as `rgba(90,169,255,…)` — **the midnight accent, literally
blue**, not `var(--acc)`. On the auspost (red), hackathon (purple) or projector (yellow)
themes it would have pulsed blue regardless of theme. The test card below fixes this by
deriving the ring from the live `--acc`; **`sketch.html` itself still has the bug**, so do not
copy its pulse rule into J7 as-is.

## The projector run (≈2 minutes)

Artifact: **`projector-test.html`** in this directory — self-contained, no server, no build.
Four candidates side by side at the real 32 px badge size, with all five real themes.

```
# on the laptop driving the projector
xdg-open docs/design-java/concepts/J11-live-thinking-trace/verification-lt5-projector/projector-test.html
```

| Candidate | What it is |
|---|---|
| **A** | glow-pulse only (the sketch's badge treatment, isolated) |
| **B** | spinner only (solid accent arc) |
| **C** | the sketch as actually written — border + shimmer + glow |
| **D** | spinner + border, no glow (only what the contrast math says survives) |

Controls: `1`–`5` switch theme, `R` restarts the animations, the slider shrinks the card as a
rough desk-side distance proxy. Clicking a verdict logs a paste-able line to the console.

### Protocol

1. Project it. Set the theme you will actually present in (**decide this first** — it changes
   the answer; contrast varies 4.05–13.58:1 across themes).
2. Walk to **where the back row will actually sit**. Do not lean in.
3. Answer one question per candidate, without being told which row is running:
   **can you tell which row is currently in progress?**
4. Then a second pass at ~2 m to check nothing looks broken close up.

### Pass / fail

- **PASS** — the running row is identifiable at the back within ~2 s, unprompted.
- **MARGINAL** — identifiable only once you know what to look for. Treat as fail; the
  audience will not know what to look for.
- **FAIL** — cannot tell.

### What each outcome means

| Result | Decision |
|---|---|
| C passes, A fails | Expected. Keep the sketch as written; the glow is harmless decoration carried by border+shimmer. |
| C and D both pass, A fails | **Drop the glow** — D is simpler and loses nothing. |
| A passes | Motion salience beat the contrast maths. Worth recording as a genuine finding — say so explicitly, it is the interesting outcome. |
| Nothing passes | Escalate beyond CSS: bigger badges, a full-row background tint, or present on the `projector` theme (13.58:1, by far the highest). |

**This is an ADM-1 call to make at the projector**, not a design fork — every branch above is
a CSS change, none alters a contract, type or transport. That is why J11 is *not* blocked on
it (see the card's Open/risks).

## Why the latency spike raised the stakes

`verification-lt4-latency/`: real steps are **8.0 s ± 2.6** apart, and the final-report window
is **13.1 s**. The affordance is therefore on screen **20–30× longer** than the ~0.4 s replay
cadence assumed when this risk was first written. If it does not read, the audience stares at
an ambiguous screen for ten-plus seconds at a time — which is the whole failure J11 exists to
prevent, arriving by a different door.
