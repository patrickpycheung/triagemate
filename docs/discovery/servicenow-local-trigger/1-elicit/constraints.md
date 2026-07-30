# Constraints & evidence (Phase 1)

## Environment: the REAL target is a locked-down corporate laptop
(NOT the dev box, which has Tailscale + cloudflared and is unrepresentative.)

| # | Constraint | Trust | Source |
|---|-----------|-------|--------|
| C1 | ServiceNow instance is **cloud-hosted** | 📚 | product context |
| C2 | App runs **locally on a corporate laptop** on the corp network | 📚 | operator |
| C3 | **Tailscale cannot be installed** on the corp laptop | 📚 | operator (2026-07-29) |
| C4 | **Cloudflare / cloudflared / trycloudflare is blocked** | 📚 | operator (2026-07-29) |
| C5 | No public inbound to the laptop (corp NAT/firewall); assume egress via corporate proxy + DPI; restricted software-install rights | 🔍 | inferred from C3/C4 + standard corp posture |
| C6 | ServiceNow **can** make outbound REST calls (Business Rule → `RESTMessageV2`, async on Insert) to any reachable URL | 📚 | [SN community](https://www.servicenow.com/community/developer-forum/setting-up-an-outgoing-webhook-to-an-external-url-on-table/m-p/2011845) |
| C7 | Tunnels bypass inbound firewalls only via an **outbound** connection to the tunnel provider — which is exactly what C3/C4 block | 📚 | [Twilio tunnels guide](https://www.twilio.com/en-us/blog/expose-localhost-to-internet-with-tunnel) |
| C8 | ServiceNow **MID Server** is an outbound-only Java agent installed inside the network that polls the ServiceNow ECC queue (no inbound needed) | 📚 | [SN MID Server basics](https://www.servicenow.com/content/dam/servicenow-assets/public/en-us/doc-type/success/quick-answer/mid-server-basics.pdf) |

## The physics (why push is impossible here)
ServiceNow lives in the cloud; the laptop lives behind corporate NAT with no inbound
and no permitted tunnel. **A connection can only be initiated from the laptop outward.**
Therefore any "trigger" must ultimately ride a laptop-initiated outbound connection —
i.e. the app pulls, or an outbound agent (MID Server) pulls. ServiceNow cannot push.

## Success criteria
1. A new ServiceNow incident causes the local app to run triage **without a human**, OR
   we conclude push-automation is infeasible and fall back to manual — **with evidence**.
2. The chosen mechanism needs **no inbound firewall change, no blocked tunnel, and no
   software the corp laptop can't install**.
3. One clearly-identified test the operator can run **on the real laptop** to confirm.
