# Spike S2′ — Forge egress to GitLab/Sumo/ServiceNow + secret storage (RESULT)

**Question**: Can Forge Actions egress to our three external systems, and store/use the
API secrets?

**Result**: ✅ **YES, both.** Trust: 🔬 Spiked (Forge docs, 2026).

## Egress
- Declare each external domain under `permissions.external.fetch.backend` in
  `manifest.yml`. `@forge/api`'s `api.fetch()` (or `fetch`) then reaches it; undeclared
  domains fail with `REQUEST_EGRESS_ALLOWLIST_ERR`.
- `forge lint --fix` auto-adds missing egress entries.
- ⚠️ **Consent caveat**: adding a new egress/remote entry triggers a **major version
  upgrade** requiring admin re-consent. For the hackathon this is one-time up front —
  declare all three domains before the demo so there's no mid-demo re-consent.

```yaml
permissions:
  external:
    fetch:
      backend:
        - 'https://gitlab.example.com'        # get-source
        - 'https://api.<deployment>.sumologic.com'  # get-logs (or omit — mocked)
        - 'https://<instance>.service-now.com'      # get-ticket + post-worknote
```

## Secrets
- Store each token encrypted: `forge variables set --encrypt SN_TOKEN <value>`
  (also `GITLAB_TOKEN`, `SUMO_KEY`). Access in the function via `process.env.SN_TOKEN`.
- Runtime env vars are the documented home for API tokens/secrets.

## Design consequence for C2
- 4 actions, each a Forge function using `api.fetch()` + `process.env.*` token.
- Sumo domain can be omitted from egress entirely if `get-logs` reads the local mock
  fixture instead of calling Sumo (recommended for demo safety — see C6).
- Keep payloads <5 MB: `get-source` returns only the seeded project's relevant files;
  `get-logs` returns only the failure window.

## Sources
- developer.atlassian.com/platform/forge/manifest-reference/permissions/
- developer.atlassian.com/platform/forge/runtime-egress-permissions/
- developer.atlassian.com/platform/forge/manifest-reference/environment/
