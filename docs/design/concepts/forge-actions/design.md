# C2 — Forge Actions — Design

Design is fixed by spike **S2′** ([verification-s2/README.md](verification-s2/README.md)).
Summary of locked decisions:

- 4 Forge functions, one per action: `getTicket`, `getSource`, `getLogs`, `postWorknote`.
- Each uses `api.fetch()` + a token from `process.env.*` — which IS how Forge exposes an
  encrypted variable set via `forge variables set --encrypt` (one mechanism, not two):
  `SN_TOKEN`, `GITLAB_TOKEN` (`SUMO_KEY` only if `getLogs` goes live).
- Egress domains declared in `manifest.yml` (see C1 design.md manifest sketch).
- **`getLogs` reads the local mock fixture** (`../log-code-reasoning/verification-s3/sumo-fixture.json`
  shape) rather than calling Sumo — demo safety (C6). Sumo egress therefore optional.
- Payload discipline: return only the seeded project's relevant files / the failure
  window (<5 MB cap).
- **Grounding guarantee (R3 conflict fix)**: for the seeded project, `getSource` returns
  a **fixed file set that always includes `payment_service.py`** (+ `order_api.py`), so
  C3's quote-both rule can always find the emitting line. Generic relevance-selection is a
  real-system concern, not built. See `.agent.work/cds/conflict-detection.md`.

## Function contracts
| Function | In | Out |
|----------|----|----|
| getTicket | `ticketId` | `{project, shortDesc, notes, orderId, openedAt}` |
| getSource | `project` | `{repoUrl, ref, files: [{path, content}]}` — `repoUrl`+`ref` let C5 build the GitLab master permalink to the cited line |
| getLogs | `orderId` | `{messages: [{time, level, logger, msg}]}` |
| postWorknote | `ticketId, noteBody, confirmed` | `{ok, worknoteId}` — **refuses to write unless `confirmed===true`**; returns `{ok:false, reason}` otherwise. On a network error it does NOT auto-retry (idempotency: a timed-out write may have landed). (G4) |

Convergence: 🟢 Converged (leaf concept, spike-locked).
