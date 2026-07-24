# Phase 2 — Diverge

Two **independent** choices. Pick one from each column.

## Choice A — the trigger mechanism (how ServiceNow fires the call)

| Option | How | Pros | Cons |
|---|---|---|---|
| **A1 · Flow Designer** (recommended) | Trigger *Record Created* on **Incident** + a **condition filter** → a *REST / Send HTTP Request* step. | No-code, visual condition builder; native **auth, retry, and step-by-step execution logs**; process owners can maintain it; async by nature. | The native REST step needs an **IntegrationHub** subscription (a scripted-action fallback via a Script Include + RESTMessageV2 exists if unlicensed). |
| **A2 · Business Rule + RESTMessageV2** (fallback) | *after / async* Business Rule on Incident with a condition → `RESTMessageV2` POST (best via an **event → Script Action** so it doesn't block the user transaction). | No IntegrationHub needed; precise scripted control; works on any instance. | Hand-written script; **must be made async** or it blocks incident creation; weaker built-in observability/retry. |

Both call our app the same way: `POST /api/diagnose/{number}` (or a small JSON body
`{ "number": "INC…" }`). Fire on **insert only** so our own comment writes don't re-trigger.

## Choice B — reachability (how the call reaches our locally-run app) — **the real constraint**

| Option | How | Fit |
|---|---|---|
| **B1 · Public tunnel** (recommended for the demo) | Expose the local app with **ngrok / Cloudflare Tunnel**; point the ServiceNow trigger at the public HTTPS URL. | Fastest; zero infra; perfect for a laptop demo. Ephemeral URL; protect the endpoint with a shared secret. |
| **B2 · MID Server** (production-correct) | Install ServiceNow's on-prem **MID Server** (a Java agent) inside the network; route the REST call through it (ECC Target on the REST Message, or the MID Server field on the Flow REST step). ServiceNow never connects *in* — the MID Server calls *out*. | The right way to reach an **internal** app with no inbound firewall holes. Heavier setup; overkill for the hackathon. |
| **B3 · Deploy the app** | Put the app somewhere ServiceNow can already reach (internal K8s / VM / an approved cloud env). | Clean, but out of scope — we deliberately run locally for the pitch. |

## The matrix
- **Demo**: **A1 (Flow) or A2 (Business Rule)** × **B1 (tunnel)**.
- **Production**: **A1 (Flow Designer)** × **B2 (MID Server)**.
