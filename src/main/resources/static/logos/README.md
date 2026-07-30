# Vendor logo assets

Operator ruling 2026-07-30: **use real vendor logos** (DDS `live-thinking-trace-ui`, LT6).

| File | Source | Licence | Form |
|---|---|---|---|
| `confluence.svg` | simple-icons | CC0-1.0 (the *drawing*) | single-path glyph |
| `gitlab.svg` | simple-icons | CC0-1.0 (the *drawing*) | single-path glyph |
| `sumologic.svg` | simple-icons | CC0-1.0 (the *drawing*) | **wordmark** — needs a wide slot, not a 32px square |
| _servicenow_ | **none — not obtainable** | — | see below |

**CC0 covers the drawing, not the trademark.** Each mark remains the property of its
owner and is used here nominatively, to identify which system a triage step consulted.

## ServiceNow — deliberately absent

Not an oversight. Verified 2026-07-30:
- **Not in simple-icons at all** (all 3,450 titles searched → no entry).
- ServiceNow's logo-usage policy requires **written permission** for third-party use
  (`legalbrandprotection@servicenow.com`), and states they cannot accommodate all requests.
- Their official download page offers **JPG/EPS only — no SVG**.
- They periodically review third-party collateral for compliance.

So the mark cannot be sourced without a permission request. Interim treatment: the
ServiceNow **name as brand-coloured text**, which their guidelines permit as nominative
use and which needs no asset. To use the real mark, obtain approved artwork via your
organisation's ServiceNow Partner/brand portal (a customer likely already has access) and
drop it in here as `servicenow.svg` — the `PLAT` table is the single swap point.

## Offline safety (RC6)

These are local files, served from `static/`. No CDN, no network fetch at runtime.
