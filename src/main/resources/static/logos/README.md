# Vendor logo assets

Operator ruling 2026-07-30/31 (DDS `live-thinking-trace-ui`, LT6): **use real vendor logos
for all four providers.** Scope: an internal, offline, throwaway hackathon demo — the marks
identify which system each triage step consulted.

| File | Source | Form | Renders as |
|---|---|---|---|
| `servicenow.svg` | vectorlogo.zone | square icon (loop mark) | green loop — clear |
| `confluence.svg` | simple-icons (CC0 drawing) | square glyph | blue mark — clear |
| `sumologic.svg` | vectorlogo.zone | square icon | blue icon — clear |
| `gitlab.svg` | simple-icons (CC0 drawing) | square glyph | orange tanuki — clearest |

All four normalised to a single `<path fill="currentColor">` so the badge tints them from
the `--sn` / `--cf` / `--sl` / `--gl` CSS variables, matching the existing UI idiom.
Each keeps its own `viewBox` (note `sumologic.svg` uses a non-zero origin,
`22.84 23.58 64 64` — don't "tidy" it to `0 0 64 64`, that would crop the mark).

## Why these sources

`simple-icons` has Confluence and GitLab as clean single-path glyphs, but **has no
ServiceNow entry at all** (all 3,450 titles searched) and ships **Sumo Logic only as a
full wordmark** — 2,026 path chars of letterforms that rendered as an illegible smudge at
badge size. `vectorlogo.zone` supplies proper square *icons* for both, which is why the
badge could stay a uniform 32 px square instead of needing a 76 px wordmark slot.

## Provenance / licensing note (record, not a warning)

Each mark remains the trademark of its owner and is used nominatively here to identify the
systems consulted. simple-icons' CC0 covers the *drawing*, not the trademark. Reassess only
if this stops being internal — i.e. if the repo is made public, the deck is published, or a
recording is distributed externally.

## Offline safety (RC6)

Local files served from `static/`. No CDN, no runtime network fetch.
