# Confluence — getting credentials

Used by: real Confluence gateway (`triage.connectors.confluence=real`).
Fills `.env` vars `CONFLUENCE_BASE_URL`, `CONFLUENCE_EMAIL`, `CONFLUENCE_API_TOKEN`.

Auth is Basic (email + API token) against the Confluence Cloud REST API.

## 1. Get an API token

1. Log in to Atlassian with the account you want the service to use (ideally a
   shared/bot account, not a personal one, if this will run unattended).
2. Go to https://id.atlassian.com/manage-profile/security/api-tokens.
3. **Create API token** → name it (e.g. `triagemate-demo`) → **Create**.
4. Copy the token immediately — it's shown once.

## 2. Find your base URL

Your Confluence Cloud site URL, e.g. `https://your-org.atlassian.net/wiki`
(the `/wiki` suffix depends on your instance — check the URL bar when browsing
Confluence).

## 3. Confirm access

```bash
curl -u you@example.com:<api-token> \
  "https://your-org.atlassian.net/wiki/rest/api/content?limit=1"
```

A `200` with JSON content means it's working.

## 4. Fill `.env`

```
CONFLUENCE_BASE_URL=https://your-org.atlassian.net/wiki
CONFLUENCE_EMAIL=you@example.com
CONFLUENCE_API_TOKEN=<token from step 1>
```

## Notes

- The account only needs **read** access to the spaces the app will search — no
  write/admin permissions required.
- API tokens don't expire by default but can be revoked any time from the same page
  if compromised.
